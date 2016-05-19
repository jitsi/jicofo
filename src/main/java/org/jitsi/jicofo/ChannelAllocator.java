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

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;

import java.util.*;

/**
 * The class is a thread that does the job of allocating Colibri channels on
 * the bridge and invites participant with Jingle 'session-initiate'. Also
 * bridge election happens here with some help of {@link BridgeSelector}.
 *
 * @author Pawel Domas
 */
public class ChannelAllocator implements Runnable
{
    /**
     * Error code used in {@link OperationFailedException} when there are no
     * working videobridge bridges.
     * FIXME: consider moving to OperationFailedException ?
     */
    private final static int BRIDGE_FAILURE_ERR_CODE = 20;

    /**
     * The logger instance used in this class.
     */
    private final static Logger logger
        = Logger.getLogger(ChannelAllocator.class);

    /**
     * Parent {@link JitsiMeetConference}.
     */
    private final JitsiMeetConference meetConference;

    /**
     * <tt>ColibriConference</tt> instance used by this thread to allocate
     * channels on the bridge.
     */
    private final ColibriConference colibriConference;

    /**
     * A participant that is to be invited by this instance to the conference.
     */
    private final Participant newParticipant;

    /**
     * First argument stands for "start audio muted" and the second one for
     * "start video muted". The information is included as a custom extension in
     * 'session-initiate' sent to the user.
     */
    private final boolean[] startMuted;

    /**
     * Indicates whether or not this thread will be doing a "re-invite". It
     * means that we're going to replace previous conference which has failed.
     * Channels are allocated on new JVB and peer is re-invited with
     * 'transport-replace' Jingle action as opposed to 'session-initiate' in
     * regular invite.
     */
    private final boolean reInvite;

    /**
     * Creates new instance of <tt>ChannelAllocator</tt> which is meant to
     * invite given <tt>Participant</tt> into the given
     * <tt>JitsiMeetConference</tt>.
     *
     * @param meetConference <tt>JitsiMeetConference</tt> where
     * <tt>newParticipant</tt> will be invited
     * @param colibriConference <tt>ColibriConference</tt> instance valid for
     * the invite to be performed by the instance being created
     * @param newParticipant to be invited participant
     * @param startMuted an array which must have the size of 2 where the first
     * value stands for "start audio muted" and the second one for "video
     * muted". This is to be included in client's offer.
     * @param reInvite <tt>true</tt> if the offer will be a 're-invite' one or
     * <tt>false</tt> otherwise.
     */
    public ChannelAllocator(JitsiMeetConference    meetConference,
                            ColibriConference      colibriConference,
                            Participant            newParticipant,
                            boolean[]              startMuted,
                            boolean                reInvite)
    {
        this.meetConference = meetConference;
        this.colibriConference = colibriConference;
        this.newParticipant = newParticipant;
        this.startMuted = startMuted;
        this.reInvite = reInvite;
    }

    private OperationSetJitsiMeetTools getMeetTools()
    {
        return meetConference.getXmppProvider().getOperationSet(
                OperationSetJitsiMeetTools.class);
    }

    /**
     * Entry point for <tt>ChannelAllocator</tt> task.
     */
    @Override
    public void run()
    {
        try
        {
            discoverFeaturesAndInvite();
        }
        catch (Exception e)
        {
            logger.error("Exception on participant invite", e);
        }
    }

    /**
     * Method does feature discovery and channel allocation for participant.
     */
    private void discoverFeaturesAndInvite()
    {
        String address = newParticipant.getMucJid();

        // Feature discovery
        List<String> features = DiscoveryUtil.discoverParticipantFeatures(
                    meetConference.getXmppProvider(), address);

        newParticipant.setSupportedFeatures(features);

        logger.info(
            address + " has bundle ? " + newParticipant.hasBundleSupport());

        List<ContentPacketExtension> offer;
        try
        {
            offer = createOffer();
            if (offer == null)
            {
                logger.info("Channel allocation cancelled for " + address);
                return;
            }
        }
        catch (OperationFailedException e)
        {
            //FIXME: retry ? sometimes it's just timeout
            logger.error("Failed to allocate channels for " + address, e);

            // Notify users about bridge is down event
            ChatRoom chatRoom = meetConference.getChatRoom();
            if (BRIDGE_FAILURE_ERR_CODE == e.getErrorCode() && chatRoom != null)
            {
                OperationSetJitsiMeetTools meetTools = getMeetTools();
                if (meetTools != null)
                {
                    meetTools.sendPresenceExtension(
                            chatRoom, new BridgeIsDownPacketExt());
                }
            }
            // Cancel - no channels allocated
            return;
        }
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
        else
        {
            OperationSetJingle jingle = meetConference.getJingle();
            boolean ack;
            if (!reInvite)
            {
                ack = jingle.initiateSession(
                        newParticipant.hasBundleSupport(),
                        address,
                        offer,
                        meetConference,
                        startMuted);
            }
            else
            {
                ack = jingle.replaceTransport(
                        newParticipant.hasBundleSupport(),
                        newParticipant.getJingleSession(),
                        offer,
                        startMuted);
            }
            if (!ack)
            {
                // Failed to invite
                logger.info(
                        "Expiring " + address + " channels - no RESULT for "
                        + (reInvite ? "transport-replace" : "session-invite"));
                expireChannels = true;
            }
        }

        if (expireChannels)
        {
            meetConference.expireParticipantChannels(
                    colibriConference, newParticipant);
        }
        else if (reInvite)
        {
            // Update channels info
            // FIXME we should include this stuff in the offer
            colibriConference.updateChannelsInfo(
                    newParticipant.getColibriChannelsInfo(),
                    newParticipant.getRtpDescriptionMap(),
                    newParticipant.getSSRCsCopy(),
                    newParticipant.getSSRCGroupsCopy(),
                    null, null);
        }
    }

    /**
     * Creates Jingle offer for given {@link Participant}.
     *
     * @throws OperationFailedException if we fail to allocate channels
     * or something goes wrong.
     */
    private List<ContentPacketExtension> createOffer()
        throws OperationFailedException
    {
        List<ContentPacketExtension> contents = new ArrayList<>();

        JitsiMeetConfig config = meetConference.getConfig();

        boolean disableIce = !newParticipant.hasIceSupport();
        boolean useDtls = newParticipant.hasDtlsSupport();
        boolean useRtx
            = config.isRtxEnabled() && newParticipant.hasRtxSupport();

        if (newParticipant.hasAudioSupport())
        {
            contents.add(
                    JingleOfferFactory.createAudioContent(
                            disableIce, useDtls, config.stereoEnabled()));
        }

        if (newParticipant.hasVideoSupport())
        {
            contents.add(
                    JingleOfferFactory.createVideoContent(
                            disableIce, useDtls, useRtx,
                            config.getMinBitrate(),
                            config.getStartBitrate()));
        }

        // Is SCTP enabled ?
        boolean openSctp = config.openSctp() == null || config.openSctp();
        if (openSctp && newParticipant.hasSctpSupport())
        {
            contents.add(
                    JingleOfferFactory.createDataContent(disableIce, useDtls));
        }

        ColibriConferenceIQ peerChannels = allocateChannels(contents);

        if (peerChannels == null)
            return null;

        newParticipant.setColibriChannelsInfo(peerChannels);

        craftOffer(contents, peerChannels);

        return contents;
    }

    /**
     * Allocates Colibri channels for given {@link Participant} by trying all
     * available bridges returned by {@link BridgeSelector}.
     *
     * @return {@link ColibriConferenceIQ} that describes channels allocated for
     * given <tt>peer</tt>. <tt>null</tt> is returned if conference is disposed
     * before we manage to allocate the channels.
     *
     * @throws OperationFailedException if we have failed to allocate channels
     * using existing bridge and we can not switch to another bridge.
     */
    private ColibriConferenceIQ allocateChannels(
            List<ContentPacketExtension> contents)
        throws OperationFailedException
    {
        if (colibriConference.isDisposed())
        {
            // Nope - the conference has been disposed, before the thread got
            // the chance to do anything
            return null;
        }

        JitsiMeetConfig config = meetConference.getConfig();

        BridgeSelector bridgeSelector
            = meetConference.getServices().getBridgeSelector();

        // Set initial bridge if we haven't used any yet
        synchronized (colibriConference)
        {
            if (StringUtils.isNullOrEmpty(
                        colibriConference.getJitsiVideobridge()))
            {
                String bridge;

                // Check for enforced bridge

                String enforcedVideoBridge = config.getEnforcedVideobridge();
                if (!StringUtils.isNullOrEmpty(enforcedVideoBridge)
                    && bridgeSelector.isJvbOnTheList(enforcedVideoBridge))
                {
                    bridge = config.getEnforcedVideobridge();
                    logger.info(
                            "Will force bridge: " + bridge
                                    + " on: " + meetConference.getRoomName());
                }
                else
                {
                    bridge = bridgeSelector.selectVideobridge();
                }

                if (StringUtils.isNullOrEmpty(bridge))
                {
                    throw new OperationFailedException(
                        "Failed to allocate channels - no bridge configured",
                        BRIDGE_FAILURE_ERR_CODE);
                }

                colibriConference.setJitsiVideobridge(bridge);
            }
        }

        String jvb = null;

        // We keep trying until the conference is disposed
        // This can happen either when this JitsiMeetConference is being
        // disposed or if the bridge already set on ColibriConference by other
        // allocator thread dies before this thread get the chance to allocate
        // anything, then it will cancel and channels for this Participant will
        // be allocated from 'restartConference'
        while (!colibriConference.isDisposed())
        {
            try
            {
                synchronized (colibriConference)
                {
                    jvb = colibriConference.getJitsiVideobridge();
                }

                logger.info(
                        "Using " + jvb + " to allocate channels for: "
                                 + newParticipant.getMucJid());

                ColibriConferenceIQ peerChannels
                    = colibriConference.createColibriChannels(
                            newParticipant.hasBundleSupport(),
                            newParticipant.getEndpointId(),
                            true /* initiator */, contents);

                // null means cancelled, because colibriConference has been
                // disposed by another thread
                if (peerChannels == null)
                    return null;

                bridgeSelector.updateBridgeOperationalStatus(jvb, true);

                if (colibriConference.hasJustAllocated())
                {
                    meetConference.onColibriConferenceAllocated(
                            colibriConference, jvb);
                }
                return peerChannels;
            }
            catch(OperationFailedException exc)
            {
                logger.error(
                        "Failed to allocate channels using bridge: " + jvb,
                        exc);

                bridgeSelector.updateBridgeOperationalStatus(jvb, false);

                // Check if the conference is in progress
                if (!StringUtils.isNullOrEmpty(
                            colibriConference.getConferenceId()))
                {
                    // Restart the conference
                    meetConference.onBridgeDown(jvb);

                    // This thread will end after returning null here
                    return null;
                }

                // Try next bridge - synchronize on conference instance
                synchronized (colibriConference)
                {
                    if (StringUtils.isNullOrEmpty(
                                config.getEnforcedVideobridge()))
                    {
                        jvb = bridgeSelector.selectVideobridge();
                    }
                    else
                    {
                        // If the "enforced" bridge has failed we do not try
                        // any other bridges, but fail immediately
                        jvb = null;
                    }

                    if (!StringUtils.isNullOrEmpty(jvb))
                    {
                        colibriConference.setJitsiVideobridge(jvb);
                    }
                    else
                    {
                        // No more bridges to try
                        throw new OperationFailedException(
                                "Failed to allocate channels "
                                    + "- all bridges are faulty",
                                BRIDGE_FAILURE_ERR_CODE);
                    }
                }
            }
        }
        // If we reach this point it means that the conference has been disposed
        // before we've managed to allocate anything
        return null;
    }

    /**
     * Fills given list of Jingle contents with transport information for
     * Colibri channels and SSRCs of other conference participants.
     *
     * @param contents the list which contains Jingle content to be included in
     *        the offer.
     * @param peerChannels <tt>ColibriConferenceIQ</tt> which is a Colibri
     *        channels description.
     */
    private void craftOffer(List<ContentPacketExtension>    contents,
                            ColibriConferenceIQ             peerChannels)
    {
        boolean useBundle = newParticipant.hasBundleSupport();

        MediaSSRCMap conferenceSSRCs
            = meetConference.getAllSSRCs(
                    reInvite ? newParticipant : null);

        MediaSSRCGroupMap conferenceSSRCGroups
            = meetConference.getAllSSRCGroups(
                    reInvite ? newParticipant : null);

        for (ContentPacketExtension cpe : contents)
        {
            String contentName = cpe.getName();
            ColibriConferenceIQ.Content colibriContent
                = peerChannels.getContent(contentName);

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
                        = peerChannels.getChannelBundle(
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
                        = peerChannels.getChannelBundle(
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
                        ssrcInfo.setOwner("jvb");
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
                    = conferenceSSRCs.getSSRCsForMedia(contentName);

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
                List<SSRCGroup> sourceGroups
                    = conferenceSSRCGroups.getSSRCGroupsForMedia(contentName);

                for(SSRCGroup ssrcGroup : sourceGroups)
                {
                    rtpDescPe.addChildExtension(ssrcGroup.getPacketExtension());
                }
            }
        }
    }
}
