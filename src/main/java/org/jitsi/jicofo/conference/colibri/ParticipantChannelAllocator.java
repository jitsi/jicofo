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
package org.jitsi.jicofo.conference.colibri;

import org.checkerframework.checker.nullness.qual.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.conference.*;
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

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * An {@link Runnable} which invites a participant to a conference.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class ParticipantChannelAllocator implements Runnable
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
     * The {@link JitsiMeetConferenceImpl} into which a participant will be
     * invited.
     */
    private final JitsiMeetConferenceImpl meetConference;

    /**
     * The {@link BridgeSession} on which
     * to allocate channels for the participant.
     */
    private final BridgeSession bridgeSession;

    /**
     * A flag which indicates whether channel allocation is canceled. Raising
     * this makes the allocation thread discontinue the allocation process and
     * return.
     */
    private volatile boolean canceled = false;

    /**
     * Whether to include a "start audio muted" extension when sending session-initiate.
     */
    private final boolean startAudioMuted;

    /**
     * Whether to include a "start video muted" extension when sending session-initiate.
     */
    private final boolean startVideoMuted;

    /**
     * Indicates whether or not this task will be doing a "re-invite". It
     * means that we're going to replace a previous conference which has failed.
     * Channels are allocated on new JVB and peer is re-invited with
     * 'transport-replace' Jingle action as opposed to 'session-initiate' in
     * regular invite.
     */
    private final boolean reInvite;

    /**
     * The colibri channels that this allocator has allocated. They'll be
     * cleaned up if the allocator is canceled or failed at any point.
     */
    private ColibriConferenceIQ colibriChannels;

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
            boolean startAudioMuted,
            boolean startVideoMuted,
            boolean reInvite,
            Logger parentLogger)
    {
        this.meetConference = meetConference;
        this.bridgeSession = bridgeSession;
        this.startAudioMuted = startAudioMuted;
        this.startVideoMuted = startVideoMuted;
        this.reInvite = reInvite;
        this.participant = participant;
        logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("participant", participant.getChatMember().getName());
    }

    /**
     * Entry point for the {@link ParticipantChannelAllocator} task.
     */
    @Override
    public void run()
    {
        try
        {
            doRun();
        }
        catch (Throwable e)
        {
            logger.error("Channel allocator failed: ", e);
            cancel();
        }
        finally
        {
            if (canceled && colibriChannels != null)
            {
                bridgeSession.colibriConference.expireChannels(colibriChannels);
            }

            if (participant != null)
            {
                participant.channelAllocatorCompleted(this);
            }
        }
    }

    private void doRun()
    {
        Offer offer;

        try
        {
            offer = createOffer();
        }
        catch (UnsupportedFeatureConfigurationException e)
        {
            logger.error("Error creating offer", e);
            return;
        }
        if (canceled)
        {
            return;
        }

        colibriChannels = allocateChannels(offer.getContents());
        if (canceled)
        {
            return;
        }

        if (colibriChannels == null)
        {
            logger.error("Channel allocator failed: " + participant);

            // Cancel this task - nothing to be done after failure
            cancel();
            return;
        }

        if (participant != null)
        {
            participant.setColibriChannelsInfo(colibriChannels);
        }

        offer = updateOffer(offer, colibriChannels);
        if (canceled)
        {
            return;
        }

        try
        {
            invite(offer);
        }
        catch (SmackException.NotConnectedException e)
        {
            logger.error("Failed to invite participant: ", e);
        }
    }

    /**
     * Allocates Colibri channels for this {@link ParticipantChannelAllocator}'s
     * {@link Participant} on {@link #bridgeSession}.
     *
     * @return a {@link ColibriConferenceIQ} which describes the allocated
     * channels, or {@code null}.
     */
    private ColibriConferenceIQ allocateChannels(
            List<ContentPacketExtension> contents)
    {
        Jid jvb = bridgeSession.bridge.getJid();
        if (jvb == null)
        {
            logger.error("No bridge jid");
            cancel();
            return null;
        }

        // The bridge is faulty.
        boolean faulty;
        // We want to re-invite the participants in this conference.
        boolean restartConference;
        try
        {
            logger.info(
                    "Using " + jvb + " to allocate channels for: "
                            + (participant == null ? "null" : participant.toString()));

            ColibriConferenceIQ colibriChannels = doAllocateChannels(contents);

            // null means canceled, because colibriConference has been
            // disposed by another thread
            if (colibriChannels == null)
            {
                cancel();
                return null;
            }

            bridgeSession.bridge.setIsOperational(true);
            meetConference.colibriRequestSucceeded();
            return colibriChannels;
        }
        catch (ConferenceNotFoundException e)
        {
            // The conference on the bridge has likely expired. We want to
            // re-invite the conference participants, though the bridge is not
            // faulty.
            restartConference = true;
            faulty = false;
            logger.error(jvb + " - conference ID not found (expired?):" + e.getMessage());
        }
        catch (BadRequestException e)
        {
            // The bridge indicated that our request is invalid. This does not
            // mean the bridge is faulty, and retrying will likely result
            // in the same error.
            // We observe this when an endpoint uses an ID not accepted by
            // the new bridge (via a custom client).
            restartConference = false;
            faulty = false;
            logger.error(
                    jvb + " - the bridge indicated bad-request: " + e.getMessage());
        }
        catch (ColibriException e)
        {
            // All other errors indicate that the bridge is faulty: timeout,
            // wrong response type, or something else.
            restartConference = true;
            faulty = true;
            logger.error(
                    jvb + " - failed to allocate channels, will consider the "
                            + "bridge faulty: " + e.getMessage(), e);
        }

        // We only get here if we caught an exception.
        if (faulty)
        {
            bridgeSession.bridge.setIsOperational(false);
            bridgeSession.hasFailed = true;
        }

        cancel();

        // If the ColibriConference is in use, and we want to retry,
        // notify the JitsiMeetConference.
        if (restartConference &&
                isNotBlank(bridgeSession.colibriConference.getConferenceId()))
        {
            meetConference.channelAllocationFailed(jvb);
        }

        return null;
    }

    /**
     * Raises the {@code canceled} flag, which causes the thread to not continue
     * with the allocation process.
     */
    public void cancel()
    {
        canceled = true;
    }

    @Override
    public String toString()
    {
        return String.format(
                "%s[%s, %s]@%d",
                this.getClass().getSimpleName(),
                bridgeSession,
                participant,
                hashCode());
    }

    /**
     * {@inheritDoc}
     */
    private Offer createOffer()
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
    private ColibriConferenceIQ doAllocateChannels(
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
    private void invite(Offer offer)
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

        if (startAudioMuted || startVideoMuted)
        {
            StartMutedPacketExtension startMutedExt = new StartMutedPacketExtension();
            startMutedExt.setAudioMute(startAudioMuted);
            startMutedExt.setVideoMute(startVideoMuted);
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
    private @NonNull Offer updateOffer(Offer offer, ColibriConferenceIQ colibriChannels)
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
                    transport.addChildExtension(new IceRtcpmuxPacketExtension());
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
                rtpDescPe.addChildExtension(new JingleRtcpmuxPacketExtension());

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
    public Participant getParticipant()
    {
        return participant;
    }
}
