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

import org.jitsi.protocol.xmpp.colibri.exception.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * A task which allocates Colibri channels and (optionally) initiates a
 * Jingle session with a participant.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public abstract class AbstractChannelAllocator implements Runnable
{
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
        = Logger.getLogger(AbstractChannelAllocator.class);

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
    protected final JitsiMeetConferenceImpl meetConference;

    /**
     * The {@link JitsiMeetConferenceImpl.BridgeSession} on which
     * to allocate channels for the participant.
     */
    protected final JitsiMeetConferenceImpl.BridgeSession bridgeSession;

    /**
     * The participant that is to be invited by this instance to the conference.
     */
    protected final AbstractParticipant participant;

    /**
     * A flag which indicates whether channel allocation is canceled. Raising
     * this makes the allocation thread discontinue the allocation process and
     * return.
     */
    protected volatile boolean canceled = false;

    /**
     * First argument stands for "start audio muted" and the second one for
     * "start video muted". The information is included as a custom extension in
     * 'session-initiate' sent to the user.
     */
    protected final boolean[] startMuted;

    /**
     * Indicates whether or not this task will be doing a "re-invite". It
     * means that we're going to replace a previous conference which has failed.
     * Channels are allocated on new JVB and peer is re-invited with
     * 'transport-replace' Jingle action as opposed to 'session-initiate' in
     * regular invite.
     */
    protected final boolean reInvite;

    /**
     * The colibri channels that this allocator has allocated. They'll be
     * cleaned up if the allocator is canceled or failed at any point.
     */
    private ColibriConferenceIQ colibriChannels;

    /**
     * Initializes a new {@link AbstractChannelAllocator} instance which is to
     * invite a specific {@link Participant} into a specific
     * {@link JitsiMeetConferenceImpl} (using a specific jitsi-videobridge
     * instance specified by a
     * {@link org.jitsi.jicofo.JitsiMeetConferenceImpl.BridgeSession}).
     *
     * @param meetConference the {@link JitsiMeetConferenceImpl} into which to
     * invite {@code participant}.
     * @param bridgeSession the
     * {@link org.jitsi.jicofo.JitsiMeetConferenceImpl.BridgeSession} which
     * identifies the jitsi-videobridge instance on which to allocate channels.
     * @param participant the participant to be invited.
     * @param startMuted an array which must have the size of 2 where the first
     * value stands for "start audio muted" and the second one for "video
     * muted". This is to be included in client's offer.
     * @param reInvite whether to send an initial offer (session-initiate) or
     * a an updated offer (transport-replace).
     */
    protected AbstractChannelAllocator(
            JitsiMeetConferenceImpl meetConference,
            JitsiMeetConferenceImpl.BridgeSession bridgeSession,
            AbstractParticipant participant,
            boolean[] startMuted,
            boolean reInvite)
    {
        this.meetConference = meetConference;
        this.bridgeSession
            = Objects.requireNonNull(bridgeSession, "bridgeSession");
        this.participant = Objects.requireNonNull(participant, "participant");
        this.startMuted = startMuted;
        this.reInvite = reInvite;
        this.logger = Logger.getLogger(classLogger, meetConference.getLogger());
    }

    /**
     * Entry point for the {@link AbstractChannelAllocator} task.
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
        throws OperationFailedException
    {
        List<ContentPacketExtension> offer;

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

        colibriChannels = allocateChannels(offer);
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
        if (offer == null || canceled)
        {
            return;
        }

        try
        {
            invite(offer);
        }
        catch (OperationFailedException e)
        {
            logger.error("Failed to invite participant: ", e);
        }
    }

    /**
     * Sends a Jingle message to the {@link Participant} associated with this
     * {@link AbstractChannelAllocator}, if there is one, in order to invite
     * (or re-invite) him to the conference.
     */
    protected void invite(List<ContentPacketExtension> offer)
        throws OperationFailedException
    {
    }

    /**
     * Creates a Jingle offer for the {@link Participant} of this
     * {@link AbstractChannelAllocator}.
     */
    protected abstract List<ContentPacketExtension> createOffer() throws UnsupportedFeatureConfigurationException;

    /**
     * Allocates Colibri channels for this {@link AbstractChannelAllocator}'s
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

            ColibriConferenceIQ colibriChannels
                = doAllocateChannels(contents);

            // null means canceled, because colibriConference has been
            // disposed by another thread
            if (colibriChannels == null)
            {
                cancel();
                return null;
            }

            bridgeSession.bridge.setIsOperational(true);

            if (bridgeSession.colibriConference.hasJustAllocated())
            {
                meetConference.onColibriConferenceAllocated(
                    bridgeSession.colibriConference, jvb);
            }
            return colibriChannels;
        }
        catch (ConferenceNotFoundException e)
        {
            // The conference on the bridge has likely expired. We want to
            // re-invite the conference participants, though the bridge is not
            // faulty.
            restartConference = true;
            faulty = false;
            logger.error(
                jvb + " - conference ID not found (expired?):" + e.getMessage());
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

    protected abstract ColibriConferenceIQ doAllocateChannels(
        List<ContentPacketExtension> offer)
        throws ColibriException;

    /**
     * Updates a Jingle offer (represented by a list of
     * {@link ContentPacketExtension}) with the transport and SSRC information
     * contained in {@code colibriChannels}.
     *
     * @param offer the list which contains Jingle content to be included in
     * the offer.
     * @param colibriChannels the {@link ColibriConferenceIQ} which represents
     * the channels allocated on a jitsi-videobridge instance for the participant
     * for which the Jingle offer is being prepared.
     */
    protected List<ContentPacketExtension> updateOffer(
            List<ContentPacketExtension> offer,
            ColibriConferenceIQ colibriChannels)
    {
        return offer;
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
     * instance of this {@link AbstractChannelAllocator}.
     */
    public JitsiMeetConferenceImpl.BridgeSession getBridgeSession()
    {
        return bridgeSession;
    }

    /**
     * @return the {@link Participant} of this {@link AbstractChannelAllocator}.
     */
    public AbstractParticipant getParticipant()
    {
        return participant;
    }

    /**
     * @return the "startMuted" array of this {@link AbstractChannelAllocator}.
     */
    public boolean[] getStartMuted()
    {
        return startMuted;
    }

    /**
     * @return the {@code reInvite} flag of this {@link AbstractChannelAllocator}.
     */
    public boolean isReInvite()
    {
        return reInvite;
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
}
