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

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.utils.logging2.*;
import org.jxmpp.jid.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Allocates colibri channels for an {@link OctoParticipant}. The "offer" that
 * we create is specific to Octo, and there is no Jingle session like in a
 * regular {@link Participant}. The session is considered established once
 * the colibri channels have been allocated.
 *
 * @author Boris Grozev
 */
public class OctoChannelAllocator implements Runnable
{
    /**
     * The logger for this instance.
     */
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
     * The colibri channels that this allocator has allocated. They'll be
     * cleaned up if the allocator is canceled or failed at any point.
     */
    private ColibriConferenceIQ colibriChannels;

    /**
     * The {@link OctoParticipant} for which colibri channels will be allocated.
     */
    private final OctoParticipant participant;

    /**
     * Initializes a new {@link OctoChannelAllocator} instance which is meant to
     * invite a specific {@link Participant} into a specific
     * {@link JitsiMeetConferenceImpl}.
     *
     */
    public OctoChannelAllocator(
            JitsiMeetConferenceImpl conference,
            BridgeSession bridgeSession,
            OctoParticipant participant,
            Logger parentLogger)
    {
        this.meetConference = conference;
        this.bridgeSession = bridgeSession;
        this.participant = participant;
        logger = parentLogger.createChildLogger(OctoChannelAllocator.class.getName());
        logger.addContext("bridge", bridgeSession.bridge.getJid().toString());
    }

    /**
     * Entry point for the {@link OctoChannelAllocator} task.
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
        Offer offer = new Offer(
                new ConferenceSourceMap(),
                JingleOfferFactory.INSTANCE.createOffer(OfferOptionsKt.getOctoOptions()));
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
    }

    /**
     * Allocates Colibri channels for this {@link OctoChannelAllocator}'s
     * {@link OctoParticipant} on {@link #bridgeSession}.
     *
     * @return a {@link ColibriConferenceIQ} which describes the allocated
     * channels, or {@code null}.
     */
    private ColibriConferenceIQ allocateChannels(List<ContentPacketExtension> contents)
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
            logger.info("Allocating octo channels on " + jvb);

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

    /**
     * @return the {@link OctoParticipant} of this {@link OctoChannelAllocator}.
     */
    public OctoParticipant getParticipant()
    {
        return participant;
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
    private ColibriConferenceIQ doAllocateChannels(List<ContentPacketExtension> offer)
        throws ColibriException
    {
        // This is a blocking call.
        ColibriConferenceIQ result =
            bridgeSession.colibriConference.createColibriChannels(
                null /* endpoint */,
                null /* statsId */,
                false/* initiator */,
                offer,
                participant.getSources(),
                participant.getRelays());

        // The colibri channels have now been allocated and we know their IDs.
        // Now we check for any scheduled updates to the sources and source
        // groups, as well as the relays.
        synchronized (participant)
        {
            participant.setColibriChannelsInfo(result);

            // Check if the sources of the participant need an update.
            boolean update = false;

            if (participant.updateSources())
            {
                update = true;
                logger.info("Will update the sources of the Octo participant " + this);
            }

            // Check if the relays need an update. We always use the same set
            // of relays for the audio and video channels, so just check video.
            ColibriConferenceIQ.Channel channel
                = result.getContent("video").getChannel(0);
            if (!(channel instanceof ColibriConferenceIQ.OctoChannel))
            {
                logger.error("Expected to find an OctoChannel in the response, found" + channel + " instead.");
            }
            else
            {
                List<String> responseRelays = ((ColibriConferenceIQ.OctoChannel) channel).getRelays();
                if (!new HashSet<>(responseRelays).equals(new HashSet<>(participant.getRelays())))
                {
                    update = true;

                    logger.info(
                            "Relays need updating. Response: " + responseRelays
                                + ", participant:" + participant.getRelays());
                }
            }

            if (update)
            {
                bridgeSession.colibriConference.updateChannelsInfo(
                    participant.getColibriChannelsInfo(),
                    null,
                    participant.getSources(),
                    null,
                    null,
                    participant.getRelays());
            }

            participant.setSessionEstablished(true);
        }

        return result;
    }
}
