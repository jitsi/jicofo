/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
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
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(ParticipantChannelAllocator.class);

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
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
            boolean reInvite)
    {
        super(meetConference, bridgeSession, participant, startMuted, reInvite);
        this.participant = participant;
        this.logger = Logger.getLogger(classLogger, meetConference.getLogger());
    }

    @Override
    protected List<ContentPacketExtension> createOffer()
    {
        EntityFullJid address = participant.getMucJid();

        // Feature discovery
        List<String> features = DiscoveryUtil.discoverParticipantFeatures(
            meetConference.getXmppProvider(), address);
        participant.setSupportedFeatures(features);


        List<ContentPacketExtension> contents = new ArrayList<>();

        JitsiMeetConfig config = meetConference.getConfig();

        boolean disableIce = !participant.hasIceSupport();
        boolean useDtls = participant.hasDtlsSupport();
        boolean useRtx
            = config.isRtxEnabled() && participant.hasRtxSupport();
        boolean enableRemb = config.isRembEnabled();
        boolean enableTcc = config.isTccEnabled();

        JingleOfferFactory jingleOfferFactory
            = FocusBundleActivator.getJingleOfferFactory();

        if (participant.hasAudioSupport())
        {
            contents.add(
                jingleOfferFactory.createAudioContent(
                    disableIce, useDtls, config.stereoEnabled(),
                    enableRemb, enableTcc));
        }

        if (participant.hasVideoSupport())
        {
            contents.add(
                jingleOfferFactory.createVideoContent(
                    disableIce, useDtls, useRtx,
                    enableRemb, enableTcc,
                    config.getMinBitrate(),
                    config.getStartBitrate()));
        }

        // Is SCTP enabled ?
        boolean openSctp = config.openSctp() == null || config.openSctp();
        if (openSctp && participant.hasSctpSupport())
        {
            contents.add(
                jingleOfferFactory.createDataContent(disableIce, useDtls));
        }

        return contents;
    }

    @Override
    protected ColibriConferenceIQ doAllocateChannels(
        List<ContentPacketExtension> offer)
        throws OperationFailedException
    {
        return bridgeSession.colibriConference.createColibriChannels(
            participant.hasBundleSupport(),
            participant.getEndpointId(),
            participant.getStatId(),
            true /* initiator */,
            offer);
    }

    @Override
    protected void invite(List<ContentPacketExtension> offer)
        throws OperationFailedException
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
            OperationSetJingle jingle = meetConference.getJingle();
            boolean ack;
            JingleSession jingleSession = participant.getJingleSession();
            if (!reInvite || jingleSession == null)
            {
                // will throw OperationFailedExc if XMPP connection is broken
                ack = jingle.initiateSession(
                        participant.hasBundleSupport(),
                        address,
                        offer,
                        meetConference,
                        startMuted);
            }
            else
            {
                // will throw OperationFailedExc if XMPP connection is broken
                ack = jingle.replaceTransport(
                        participant.hasBundleSupport(),
                        jingleSession,
                        offer,
                        startMuted);
            }
            if (!ack)
            {
                // Failed to invite
                logger.info(
                        "Expiring " + address + " channels - no RESULT for "
                        + (reInvite ? "transport-replace" : "session-initiate"));
                expireChannels = true;

                // TODO: let meetConference know that our Jingle session failed,
                // so it can either retry or remove the participant?
            }
        }

        if (expireChannels || canceled)
        {
            // Whether another thread intentionally canceled us, or there was
            // a failure to invite the participant in the jingle level, we will
            // not trigger a retry here.
            // In any case, try and expire the channels on the bridge.
            bridgeSession.terminate(participant);
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
     * {@inheritDoc}
     */
    @Override
    protected List<ContentPacketExtension> updateOffer(
            List<ContentPacketExtension> offer,
            ColibriConferenceIQ colibriChannels)
    {
        boolean useBundle = participant.hasBundleSupport();

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
                IceUdpTransportPacketExtension transport;

                if (useBundle)
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

                    transport = bundle.getTransport();

                    if (!transport.isRtcpMux())
                    {
                        transport.addChildExtension(
                                new RtcpmuxPacketExtension());
                    }
                }
                else
                {
                    transport = channel.getTransport();
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
                IceUdpTransportPacketExtension transport;

                if (useBundle)
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

                    transport = bundle.getTransport();
                }
                else
                {
                    transport = sctpConn.getTransport();
                }

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
                if (useBundle)
                {
                    // rtcp-mux
                    rtpDescPe.addChildExtension(
                            new RtcpmuxPacketExtension());
                }

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
