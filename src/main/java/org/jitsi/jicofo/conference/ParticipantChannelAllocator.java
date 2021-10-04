/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.conference;

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.conference.colibri.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.JingleUtils;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 * An {@link AbstractChannelAllocator} which invites an actual participant to
 * the conference (as opposed to e.g. allocating Colibri channels for
 * bridge-to-bridge
 * communication).
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class ParticipantChannelAllocator extends AbstractChannelAllocator
{
    /**
     * The constant value used as owner attribute value of
     * {@link SSRCInfoPacketExtension} for the SSRC which belongs to the JVB.
     */
    public static final Jid SSRC_OWNER_JVB;

    static
    {
        try
        {
            SSRC_OWNER_JVB = JidCreate.from("jvb");
        }
        catch (XmppStringprepException e)
        {
            // cannot happen
            throw new RuntimeException(e);
        }
    }

    private final Logger logger;

    /**
     * Override super's AbstractParticipant
     */
    private final Participant participant;

    /**
     * {@inheritDoc}
     */
    public ParticipantChannelAllocator(
            JitsiMeetConferenceImpl meetConference,
            BridgeSession bridgeSession,
            Participant participant,
            boolean[] startMuted,
            boolean reInvite,
            Logger parentLogger)
    {
        super(meetConference, bridgeSession, participant, startMuted, reInvite, parentLogger);
        this.participant = participant;
        logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("participant", participant.getChatMember().getName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Offer createOffer()
        throws UnsupportedFeatureConfigurationException
    {
        // Feature discovery
        List<String> features = meetConference.getClientXmppProvider().discoverFeatures(participant.getMucJid());
        participant.setSupportedFeatures(features);

        JitsiMeetConfig config = meetConference.getConfig();

        OfferOptions offerOptions = new OfferOptions();
        OfferOptionsKt.applyConstraints(offerOptions, config);
        OfferOptionsKt.applyConstraints(offerOptions, participant);
        // Enable REMB only when TCC is not enabled.
        if (!offerOptions.getTcc() && participant.hasRembSupport())
        {
            offerOptions.setRemb(true);
        }

        return new Offer(new ConferenceSourceMap(), JingleOfferFactory.INSTANCE.createOffer(offerOptions));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ColibriConferenceIQ doAllocateChannels(
        List<ContentPacketExtension> offer)
        throws ColibriException
    {
        return bridgeSession.colibriConference.createColibriChannels(
            participant.getEndpointId(),
            participant.getStatId(),
            true /* initiator */,
            offer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void invite(Offer offer)
        throws SmackException.NotConnectedException
    {
        /*
           This check makes sure that when we're trying to invite
           new participant:
           - the conference has not been disposed in the meantime
           - he's still in the room
           - we have managed to send Jingle session-initiate
           We usually expire channels when participant leaves the MUC, but we
           may not have channel information set, so we have to expire it
           here.
        */
        boolean expireChannels = false;
        Jid address = participant.getMucJid();

        ChatRoom chatRoom = meetConference.getChatRoom();
        if (chatRoom == null)
        {
            // Conference disposed
            logger.info(
                    "Expiring " + address + " channels - conference disposed");

            expireChannels = true;
        }
        else if (meetConference.findMember(address) == null)
        {
            // Participant has left the room
            logger.info(
                    "Expiring " + address + " channels - participant has left");

            expireChannels = true;
        }
        else if (!canceled)
        {
            if (!doInviteOrReinvite(address, offer))
            {
                expireChannels = true;
            }
        }

        if (expireChannels || canceled)
        {
            // Whether another thread intentionally canceled us, or there was
            // a failure to invite the participant on the jingle level, we will
            // not trigger a retry here.
            meetConference.onInviteFailed(this);
        }
        else if (reInvite)
        {
            // Update channels info
            // FIXME we should include this stuff in the offer
            bridgeSession.colibriConference.updateChannelsInfo(
                    participant.getColibriChannelsInfo(),
                    participant.getRtpDescriptionMap(),
                    participant.getSources());
        }

        if (chatRoom != null && !participant.hasModeratorRights())
        {
            // if participant is not muted, but needs to be
            if (chatRoom.isAvModerationEnabled(MediaType.AUDIO))
            {
                meetConference.muteParticipant(participant, MediaType.AUDIO);
            }

            if (chatRoom.isAvModerationEnabled(MediaType.VIDEO))
            {
                meetConference.muteParticipant(participant, MediaType.VIDEO);
            }
        }
    }

    /**
     * Invites or re-invites (based on the value of {@link #reInvite}) the
     * {@code participant} to the jingle session.
     * Creates and sends the appropriate Jingle IQ ({@code session-initiate} for
     * and invite or {@code transport-replace} for a re-invite) and sends it to
     * the {@code participant}. Blocks until a response is received or a timeout
     * occurs.
     *
     * @param address the destination JID.
     * @param offer The description of the offer to send (sources and a list of {@link ContentPacketExtension}s).
     * @return {@code false} on failure.
     * @throws SmackException.NotConnectedException if we are unable to send a packet because the XMPP connection is not
     * connected.
     */
    private boolean doInviteOrReinvite(Jid address, Offer offer)
        throws SmackException.NotConnectedException
    {
        OperationSetJingle jingle = meetConference.getJingle();
        JingleSession jingleSession = participant.getJingleSession();
        boolean initiateSession = !reInvite || jingleSession == null;
        boolean ack;
        List<ExtensionElement> additionalExtensions = new ArrayList<>();

        if (startMuted[0] || startMuted[1])
        {
            StartMutedPacketExtension startMutedExt = new StartMutedPacketExtension();
            startMutedExt.setAudioMute(startMuted[0]);
            startMutedExt.setVideoMute(startMuted[1]);
            additionalExtensions.add(startMutedExt);
        }

        // Include info about the BridgeSession which provides the transport
        additionalExtensions.add(new BridgeSessionPacketExtension(bridgeSession.id, bridgeSession.bridge.getRegion()));

        if (initiateSession)
        {
            logger.info("Sending session-initiate to: " + address);
            ack = jingle.initiateSession(
                    address,
                    offer.getContents(),
                    additionalExtensions,
                    meetConference,
                    offer.getSources(),
                    ConferenceConfig.config.getUseJsonEncodedSources() && participant.supportsJsonEncodedSources());
        }
        else
        {
            logger.info("Sending transport-replace to: " + address);
            // will throw OperationFailedExc if XMPP connection is broken
            ack = jingle.replaceTransport(
                    jingleSession,
                    offer.getContents(),
                    additionalExtensions,
                    offer.getSources(),
                    ConferenceConfig.config.getUseJsonEncodedSources() && participant.supportsJsonEncodedSources());
        }

        if (!ack)
        {
            // Failed to invite
            logger.info(
                "Expiring " + address + " channels - no RESULT for "
                    + (initiateSession ? "session-initiate"
                    : "transport-replace"));
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Offer updateOffer(Offer offer, ColibriConferenceIQ colibriChannels)
    {
        ConferenceSourceMap conferenceSources = meetConference.getSources()
                .copy()
                .strip(ConferenceConfig.config.stripSimulcast(), true)
                .stripByMediaType(participant.getSupportedMediaTypes());
        // Remove the participant's own sources (if they're present)
        conferenceSources.remove(participant.getMucJid());

        for (ContentPacketExtension cpe : offer.getContents())
        {
            String contentName = cpe.getName();
            ColibriConferenceIQ.Content colibriContent = colibriChannels.getContent(contentName);

            if (colibriContent == null)
                continue;

            // Channels
            for (ColibriConferenceIQ.Channel channel : colibriContent.getChannels())
            {
                ColibriConferenceIQ.ChannelBundle bundle
                    = colibriChannels.getChannelBundle(channel.getChannelBundleId());

                if (bundle == null)
                {
                    logger.error("No bundle for " + channel.getChannelBundleId());
                    continue;
                }

                IceUdpTransportPacketExtension transport = bundle.getTransport();

                if (!transport.isRtcpMux())
                {
                    transport.addChildExtension(new RtcpmuxPacketExtension());
                }

                try
                {
                    // Remove empty transport PE
                    IceUdpTransportPacketExtension empty
                        = cpe.getFirstChildOfType(IceUdpTransportPacketExtension.class);
                    cpe.getChildExtensions().remove(empty);

                    cpe.addChildExtension(IceUdpTransportPacketExtension.cloneTransportAndCandidates(transport, true));
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
            // SCTP connections
            for (ColibriConferenceIQ.SctpConnection sctpConn : colibriContent.getSctpConnections())
            {
                ColibriConferenceIQ.ChannelBundle bundle
                    = colibriChannels.getChannelBundle(sctpConn.getChannelBundleId());

                if (bundle == null)
                {
                    logger.error("No bundle for " + sctpConn.getChannelBundleId());
                    continue;
                }

                IceUdpTransportPacketExtension transport = bundle.getTransport();

                try
                {
                    // Remove empty transport
                    IceUdpTransportPacketExtension empty
                        = cpe.getFirstChildOfType(IceUdpTransportPacketExtension.class);
                    cpe.getChildExtensions().remove(empty);

                    IceUdpTransportPacketExtension copy
                        = IceUdpTransportPacketExtension.cloneTransportAndCandidates(transport, true);

                    // FIXME: hardcoded
                    SctpMapExtension sctpMap = new SctpMapExtension();
                    sctpMap.setPort(5000);
                    sctpMap.setProtocol(SctpMapExtension.Protocol.WEBRTC_CHANNEL);
                    sctpMap.setStreams(1024);

                    copy.addChildExtension(sctpMap);

                    cpe.addChildExtension(copy);
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
            // Existing peers SSRCs
            RtpDescriptionPacketExtension rtpDescPe = JingleUtils.getRtpDescription(cpe);
            if (rtpDescPe != null)
            {
                // rtcp-mux is always used
                rtpDescPe.addChildExtension(new RtcpmuxPacketExtension());

                // Copy SSRC sent from the bridge(only the first one)
                for (ColibriConferenceIQ.Channel channel : colibriContent.getChannels())
                {
                    SourcePacketExtension ssrcPe
                        = channel.getSources().size() > 0 ? channel.getSources().get(0) : null;
                    if (ssrcPe == null)
                    {
                        continue;
                    }

                    MediaType mediaType = MediaType.parseString(contentName);

                    conferenceSources.add(
                            SSRC_OWNER_JVB,
                            new EndpointSourceSet(
                                    new Source(
                                            ssrcPe.getSSRC(),
                                            mediaType,
                                            // assuming either audio or video the source name: jvb-a0 or jvb-v0
                                            "jvb-" + mediaType.toString().charAt(0) + "0",
                                            "mixedmslabel mixedlabel" + contentName + "0",
                                            false)));
                }
            }
        }

        return new Offer(conferenceSources, offer.getContents());
    }

    /**
     * @return the {@link Participant} associated with this
     * {@link ParticipantChannelAllocator}.
     */
    @Override
    public Participant getParticipant()
    {
        return participant;
    }
}
