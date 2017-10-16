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
 * The class is a thread that does the job of allocating Colibri channels on
 * the bridge and invites participant with Jingle 'session-initiate'.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class ChannelAllocator implements Runnable
{
    /**
     * Error code used in {@link OperationFailedException} when there are no
     * working videobridge bridges.
     * FIXME: consider moving to OperationFailedException ?
     */
    final static int NO_BRIDGE_AVAILABLE_ERR_CODE = 20;

    /**
     * Error code used in {@link OperationFailedException} when Colibri channel
     * allocation fails.
     * FIXME: consider moving to OperationFailedException ?
     */
    final static int CHANNEL_ALLOCATION_FAILED_ERR_CODE = 21;

    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(ChannelAllocator.class);

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    /**
     * The {@link JitsiMeetConferenceImpl} into which a participant will be
     * invited.
     */
    private final JitsiMeetConferenceImpl meetConference;

    /**
     * The {@link JitsiMeetConferenceImpl.BridgeSession} on which
     * to allocate channels for the participant.
     */
    private final JitsiMeetConferenceImpl.BridgeSession bridgeSession;

    /**
     * The participant that is to be invited by this instance to the conference.
     */
    private final Participant participant;

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
     * A flag which indicates whether channel allocation is canceled. Raising
     * this makes the allocation thread discontinue the allocation process and
     * return.
     */
    private volatile boolean canceled = false;

    /**
     * Initializes a new {@link ChannelAllocator} instance which is meant to
     * invite a specific {@link Participant} into a specific
     * {@link JitsiMeetConferenceImpl}.
     *
     * @param meetConference the {@link JitsiMeetConferenceImpl} into which to
     * invite {@code participant}.
     * @param participant the participant to be invited.
     * @param startMuted an array which must have the size of 2 where the first
     * value stands for "start audio muted" and the second one for "video
     * muted". This is to be included in client's offer.
     * @param reInvite whether to send an initial offer (session-initiate) or
     * a an updated offer (transport-replace).
     */
    public ChannelAllocator(
            JitsiMeetConferenceImpl meetConference,
            JitsiMeetConferenceImpl.BridgeSession bridgeSession,
            Participant participant,
            boolean[] startMuted,
            boolean reInvite)
    {
        this.meetConference = meetConference;
        this.bridgeSession = bridgeSession;
        this.participant = participant;
        this.startMuted = startMuted;
        this.reInvite = reInvite;
        this.logger = Logger.getLogger(classLogger, meetConference.getLogger());
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
        catch (Throwable e)
        {
            logger.error("Exception on participant invite", e);
        }

        participant.channelAllocatorCompleted(this);
    }

    /**
     * Method does feature discovery and channel allocation for participant.
     *
     * @throws OperationFailedException if the XMPP connection is broken.
     */
    private void discoverFeaturesAndInvite()
        throws OperationFailedException
    {
        EntityFullJid address = participant.getMucJid();

        // Feature discovery
        List<String> features = DiscoveryUtil.discoverParticipantFeatures(
                    meetConference.getXmppProvider(), address);

        if (canceled)
        {
            // Another thread intentionally called cancel() and it is its
            // responsibility to retry if necessary.
            return;
        }

        participant.setSupportedFeatures(features);

        logger.info(
            address + " has bundle ? " + participant.hasBundleSupport());

        List<ContentPacketExtension> offer;

        try
        {
            offer = createOffer();
            if (offer == null)
            {
                logger.info("Channel allocation canceled for " + address);
                return;
            }
        }
        catch (OperationFailedException e)
        {
            logger.error("Failed to allocate channels for " + address, e);

            // Notify conference about failure
            meetConference.onChannelAllocationFailed(this, e);

            // Cancel this thread - nothing to be done after failure
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
                        + (reInvite ? "transport-replace" : "session-invite"));
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
                    participant.getSourceGroupsCopy(),
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

        boolean disableIce = !participant.hasIceSupport();
        boolean useDtls = participant.hasDtlsSupport();
        boolean useRtx
            = config.isRtxEnabled() && participant.hasRtxSupport();

        JingleOfferFactory jingleOfferFactory
            = FocusBundleActivator.getJingleOfferFactory();

        if (participant.hasAudioSupport())
        {
            contents.add(
                    jingleOfferFactory.createAudioContent(
                            disableIce, useDtls, config.stereoEnabled()));
        }

        if (participant.hasVideoSupport())
        {
            contents.add(
                jingleOfferFactory.createVideoContent(
                            disableIce, useDtls, useRtx,
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

        ColibriConferenceIQ colibriChannels = allocateChannels(contents);

        if (colibriChannels == null)
        {
            if (canceled)
            {
                // Another thread called cancel() intentionally and it is its
                // responsibility to retry the invitation if necessary.
                return null;
            }
            throw new OperationFailedException(
                "Colibri channel allocation failed",
                CHANNEL_ALLOCATION_FAILED_ERR_CODE);
        }

        if (!canceled)
        {
            participant.setColibriChannelsInfo(colibriChannels);

            craftOffer(contents, colibriChannels);

            return contents;
        }
        else
        {
            return null;
        }
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
        // TODO: synchronization?
        if (bridgeSession.colibriConference.isDisposed())
        {
            // Nope - the conference has been disposed, before the thread got
            // the chance to do anything
            return null;
        }

        Jid jvb = bridgeSession.colibriConference.getJitsiVideobridge();
        if (jvb == null)
        {
            logger.error("No bridge jid");
            return null;
        }

        // We keep trying until the conference is disposed
        // This can happen either when this JitsiMeetConference is being
        // disposed or if the bridge already set on ColibriConference by other
        // allocator thread dies before this thread get the chance to allocate
        // anything, then it will cancel and channels for this Participant will
        // be allocated from 'restartConference'
        while (!bridgeSession.colibriConference.isDisposed()
                && !bridgeSession.hasFailed)
        {
            try
            {
                logger.info(
                        "Using " + jvb + " to allocate channels for: "
                                 + participant.getMucJid());

                ColibriConferenceIQ peerChannels
                    = bridgeSession.colibriConference.createColibriChannels(
                            participant.hasBundleSupport(),
                            participant.getEndpointId(),
                            participant.getStatId(),
                            true /* initiator */, contents);

                // null means canceled, because colibriConference has been
                // disposed by another thread
                if (peerChannels == null)
                {
                    return null;
                }

                bridgeSession.bridgeState.setIsOperational(true);

                if (bridgeSession.colibriConference.hasJustAllocated())
                {
                    meetConference.onColibriConferenceAllocated(
                        bridgeSession.colibriConference, jvb);
                }
                return peerChannels;
            }
            catch (OperationFailedException exc)
            {
                logger.error(
                        "Failed to allocate channels using bridge: " + jvb,
                        exc);

                // ILLEGAL_ARGUMENT == BAD_REQUEST(XMPP)
                // It usually means that Jicofo's conference state got out of
                // sync with the one of the bridge. The easiest thing to do here
                // is to restart the conference. It does not mean that
                // the bridge is faulty though.
                if (OperationFailedException.ILLEGAL_ARGUMENT
                        != exc.getErrorCode())
                {
                    bridgeSession.bridgeState.setIsOperational(false);
                    bridgeSession.hasFailed = true;
                }

                // Check if the conference is in progress
                if (!StringUtils.isNullOrEmpty(
                    bridgeSession.colibriConference.getConferenceId()))
                {
                    // Notify the conference that this ColibriConference is now
                    // broken.
                    meetConference.onBridgeDown(jvb);

                    // This thread will end after returning null here
                    return null;
                }
            }
        }
        // If we reach this point it means that the conference has been disposed
        // of before we managed to allocate anything
        return null;
    }

    /**
     * Fills given list of Jingle contents with transport information for
     * Colibri channels and SSRCs of other conference participants.
     *
     * @param contents the list which contains Jingle content to be included in
     *        the offer.
     * @param colibriChannels <tt>ColibriConferenceIQ</tt> which is a Colibri
     *        channels description.
     */
    private void craftOffer(
            List<ContentPacketExtension> contents,
            ColibriConferenceIQ colibriChannels)
    {
        boolean useBundle = participant.hasBundleSupport();

        MediaSourceMap conferenceSSRCs
            = meetConference.getAllSources(
                    reInvite ? participant : null);

        MediaSourceGroupMap conferenceSSRCGroups
            = meetConference.getAllSourceGroups(
                    reInvite ? participant : null);

        for (ContentPacketExtension cpe : contents)
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
    }

    /**
     * Raises the {@code canceled} flag, which causes the thread to not continue
     * with the allocation process.
     */
    public void cancel()
    {
        canceled = true;
    }

    /**
     * @return the {@link JitsiMeetConferenceImpl.BridgeSession}
     * instance of this {@link ChannelAllocator}.
     */
    public JitsiMeetConferenceImpl.BridgeSession getBridgeSession()
    {
        return bridgeSession;
    }

    /**
     * @return the {@link Participant} of this {@link ChannelAllocator}.
     */
    public Participant getParticipant()
    {
        return participant;
    }

    /**
     * @return the "startMuted" array of this {@link ChannelAllocator}.
     */
    public boolean[] getStartMuted()
    {
        return startMuted;
    }

    /**
     * @return the {@code reInvite} flag of this {@link ChannelAllocator}.
     */
    public boolean isReInvite()
    {
        return reInvite;
    }

}
