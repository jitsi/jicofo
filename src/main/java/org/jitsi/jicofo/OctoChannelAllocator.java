/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-2018 Atlassian Pty Ltd
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

import org.jitsi.jicofo.codec.*;
import org.jitsi.protocol.xmpp.colibri.exception.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.utils.logging.*;

import java.util.*;

/**
 * Allocates colibri channels for an {@link OctoParticipant}. The "offer" that
 * we create is specific to Octo, and there is no Jingle session like in a
 * regular {@link Participant}. The session is considered established once
 * the colibri channels have been allocated.
 *
 * @author Boris Grozev
 */
public class OctoChannelAllocator extends AbstractChannelAllocator
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(OctoChannelAllocator.class);

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

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
            JitsiMeetConferenceImpl.BridgeSession bridgeSession,
            OctoParticipant participant)
    {
        super(conference, bridgeSession, participant, null, false);
        this.participant = participant;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<ContentPacketExtension> createOffer()
    {
        JitsiMeetConfig config = meetConference.getConfig();

        boolean useIce = false;
        boolean useDtls = false;
        boolean useRtx = false;

        JingleOfferFactory jingleOfferFactory
            = FocusBundleActivator.getJingleOfferFactory();

        List<ContentPacketExtension> contents = new ArrayList<>();
        contents.add(
            jingleOfferFactory.createAudioContent(useIce, useDtls, config));

        contents.add(
            jingleOfferFactory.createVideoContent(
                    useIce, useDtls, useRtx, config));

        return contents;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ColibriConferenceIQ doAllocateChannels(
        List<ContentPacketExtension> offer)
        throws ColibriException
    {
        // This is a blocking call.
        ColibriConferenceIQ result =
            bridgeSession.colibriConference.createColibriChannels(
                null /* endpoint */,
                null /* statsId */,
                false/* initiator */,
                offer,
                participant.getSourcesCopy().toMap(),
                participant.getSourceGroupsCopy().toMap(),
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
            if (channel == null
                || !(channel instanceof ColibriConferenceIQ.OctoChannel))
            {
                logger.error(
                    "Expected to find an OctoChannel in the response, found"
                        + channel + " instead.");
            }
            else
            {
                List<String> responseRelays
                    = ((ColibriConferenceIQ.OctoChannel) channel).getRelays();
                if (!new HashSet<>(responseRelays)
                        .equals(new HashSet<>(participant.getRelays())))
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
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy(),
                    null,
                    null,
                    participant.getRelays());
            }

            participant.setSessionEstablished(true);
        }

        return result;
    }
}
