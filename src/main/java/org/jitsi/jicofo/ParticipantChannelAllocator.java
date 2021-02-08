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
package org.jitsi.jicofo;

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.exception.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.JingleUtils;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jxmpp.jid.*;

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
            JitsiMeetConferenceImpl.BridgeSession bridgeSession,
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
    protected List<ContentPacketExtension> createOffer()
        throws UnsupportedFeatureConfigurationException
    {
        // Feature discovery
        List<String> features = meetConference.getClientXmppProvider().discoverFeatures(participant.getMucJid());
        participant.setSupportedFeatures(features);

        JitsiMeetConfig config = meetConference.getConfig();

        OfferOptions offerOptions = new OfferOptions();
        OfferOptionsKt.applyConstraints(offerOptions, config);
        OfferOptionsKt.applyConstraints(offerOptions, participant);

        return JingleOfferFactory.INSTANCE.createOffer(offerOptions);
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
    protected void invite(List<ContentPacketExtension> offer)
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
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy());
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
     * @param contents the list of contents to include.
     * @return {@code false} on failure.
     * @throws SmackException.NotConnectedException if we are unable to send a packet because the XMPP connection is not
     * connected.
     */
    private boolean doInviteOrReinvite(
        Jid address, List<ContentPacketExtension> contents)
        throws SmackException.NotConnectedException
    {
        OperationSetJingle jingle = meetConference.getJingle();
        JingleSession jingleSession = participant.getJingleSession();
        boolean initiateSession = !reInvite || jingleSession == null;
        boolean ack;
        JingleIQ jingleIQ;

        if (initiateSession)
        {
            // will throw OperationFailedExc if XMPP connection is broken
            jingleIQ = JingleUtilsKt.createSessionInitiate(jingle.getOurJID(), address, contents);
        }
        else
        {
            jingleIQ = JingleUtilsKt.createTransportReplace(jingle.getOurJID(), jingleSession, contents);
        }

        JicofoJingleUtils.addBundleExtensions(jingleIQ);
        if (startMuted[0] || startMuted[1])
        {
            JicofoJingleUtils.addStartMutedExtension(jingleIQ, startMuted[0], startMuted[1]);
        }

        // Include info about the BridgeSession which provides the transport
        jingleIQ.addExtension(
            new BridgeSessionPacketExtension(
                    bridgeSession.id, bridgeSession.bridge.getRegion()));

        if (initiateSession)
        {
            logger.info("Sending session-initiate to: " + address);
            ack = jingle.initiateSession(jingleIQ, meetConference);
        }
        else
        {
            logger.info("Sending transport-replace to: " + address);
            // will throw OperationFailedExc if XMPP connection is broken
            ack = jingle.replaceTransport(jingleIQ, jingleSession);
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
    protected List<ContentPacketExtension> updateOffer(
            List<ContentPacketExtension> offer,
            ColibriConferenceIQ colibriChannels)
    {
        MediaSourceMap conferenceSSRCs
            = meetConference.getAllSources(reInvite ? participant : null);

        MediaSourceGroupMap conferenceSSRCGroups
            = meetConference.getAllSourceGroups(reInvite ? participant : null);

        for (ContentPacketExtension cpe : offer)
        {
            String contentName = cpe.getName();
            ColibriConferenceIQ.Content colibriContent
                = colibriChannels.getContent(contentName);

            if (colibriContent == null)
                continue;

            // Channels
            for (ColibriConferenceIQ.Channel channel
                    : colibriContent.getChannels())
            {
                ColibriConferenceIQ.ChannelBundle bundle
                    = colibriChannels.getChannelBundle(
                    channel.getChannelBundleId());

                if (bundle == null)
                {
                    logger.error(
                        "No bundle for " + channel.getChannelBundleId());
                    continue;
                }

                IceUdpTransportPacketExtension transport = bundle.getTransport();

                if (!transport.isRtcpMux())
                {
                    transport.addChildExtension(
                            new RtcpmuxPacketExtension());
                }

                try
                {
                    // Remove empty transport PE
                    IceUdpTransportPacketExtension empty
                        = cpe.getFirstChildOfType(
                                IceUdpTransportPacketExtension.class);
                    cpe.getChildExtensions().remove(empty);

                    cpe.addChildExtension(
                            IceUdpTransportPacketExtension
                                .cloneTransportAndCandidates(transport, true));
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
            // SCTP connections
            for (ColibriConferenceIQ.SctpConnection sctpConn
                    : colibriContent.getSctpConnections())
            {
                ColibriConferenceIQ.ChannelBundle bundle
                    = colibriChannels.getChannelBundle(
                            sctpConn.getChannelBundleId());

                if (bundle == null)
                {
                    logger.error(
                        "No bundle for " + sctpConn.getChannelBundleId());
                    continue;
                }

                IceUdpTransportPacketExtension transport = bundle.getTransport();

                try
                {
                    // Remove empty transport
                    IceUdpTransportPacketExtension empty
                        = cpe.getFirstChildOfType(
                                IceUdpTransportPacketExtension.class);
                    cpe.getChildExtensions().remove(empty);

                    IceUdpTransportPacketExtension copy
                        = IceUdpTransportPacketExtension
                            .cloneTransportAndCandidates(transport, true);

                    // FIXME: hardcoded
                    SctpMapExtension sctpMap = new SctpMapExtension();
                    sctpMap.setPort(5000);
                    sctpMap.setProtocol(
                            SctpMapExtension.Protocol.WEBRTC_CHANNEL);
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
            RtpDescriptionPacketExtension rtpDescPe
                = JingleUtils.getRtpDescription(cpe);
            if (rtpDescPe != null)
            {
                // rtcp-mux is always used
                rtpDescPe.addChildExtension(
                        new RtcpmuxPacketExtension());

                // Copy SSRC sent from the bridge(only the first one)
                for (ColibriConferenceIQ.Channel channel
                        : colibriContent.getChannels())
                {
                    SourcePacketExtension ssrcPe
                        = channel.getSources().size() > 0
                            ? channel.getSources().get(0) : null;
                    if (ssrcPe == null)
                        continue;

                    try
                    {
                        SourcePacketExtension ssrcCopy = ssrcPe.copy();

                        // FIXME: not all parameters are used currently
                        ssrcCopy.addParameter(
                                new ParameterPacketExtension("cname","mixed"));
                        ssrcCopy.addParameter(
                                new ParameterPacketExtension(
                                        "label",
                                        "mixedlabel" + contentName + "0"));
                        ssrcCopy.addParameter(
                                new ParameterPacketExtension(
                                        "msid",
                                        "mixedmslabel mixedlabel"
                                            + contentName + "0"));
                        ssrcCopy.addParameter(
                                new ParameterPacketExtension(
                                        "mslabel", "mixedmslabel"));

                        // Mark 'jvb' as SSRC owner
                        SSRCInfoPacketExtension ssrcInfo
                            = new SSRCInfoPacketExtension();
                        ssrcInfo.setOwner(SSRCSignaling.SSRC_OWNER_JVB);
                        ssrcCopy.addChildExtension(ssrcInfo);

                        rtpDescPe.addChildExtension(ssrcCopy);
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC error", e);
                    }
                }

                // Include all peers SSRCs
                List<SourcePacketExtension> mediaSources
                    = conferenceSSRCs.getSourcesForMedia(contentName);

                for (SourcePacketExtension ssrc : mediaSources)
                {
                    try
                    {
                        rtpDescPe.addChildExtension(ssrc.copy());
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC error", e);
                    }
                }

                // Include SSRC groups
                List<SourceGroup> sourceGroups
                    = conferenceSSRCGroups.getSourceGroupsForMedia(contentName);

                for(SourceGroup sourceGroup : sourceGroups)
                {
                    rtpDescPe.addChildExtension(sourceGroup.getPacketExtension());
                }
            }
        }

        return offer;
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
