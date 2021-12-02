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

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.*;
import org.jxmpp.jid.parts.*;

import java.util.*;

/**
 * Represents a colibri session to a specific {@link Bridge} instance in a specific conference.
 */
class BridgeSession
{
    private final static Random RANDOM = new Random();
    private final Logger logger;

    /**
     * The {@link Bridge}.
     */
    final Bridge bridge;

    /**
     * The bridge session's id.
     * <p>
     * At the time of this writing it's used to distinguish between current
     * and outdated ICE failed notifications coming from the client.
     * <p>
     * It can often happen that during a bridge failure multiple clients
     * will send ICE failed messages because all of them will have
     * connectivity broken. Jicofo will mark the bridge as unhealthy when
     * processing the first notification and any following ones should be
     * discarded.
     */
    public final String id = Integer.toHexString(RANDOM.nextInt(0x1_000000));

    /**
     * The list of participants in the conference which use this
     * {@link BridgeSession}.
     */
    @NotNull final List<Participant> participants = new LinkedList<>();

    /**
     * The {@link ColibriConference} instance used to communicate with
     * the jitsi-videobridge represented by this {@link BridgeSession}.
     */
    public final ColibriConference colibriConference;

    /**
     * The single {@link OctoParticipant} for this bridge session, if any.
     */
    private OctoParticipant octoParticipant;

    /**
     * Indicates if the bridge used in this conference is faulty. We use
     * this flag to skip channel expiration step when the conference is being
     * disposed of.
     */
    public boolean hasFailed = false;

    @NotNull private final ColibriSessionManager colibriSessionManager;

    @NotNull private final ColibriRequestCallback colibriRequestCallback;

    /**
     * Initializes a new {@link BridgeSession} instance.
     *
     * @param bridge the {@link Bridge} which the new
     *               {@link BridgeSession} instance is to represent.
     */
    BridgeSession(
            @NotNull JitsiMeetConferenceImpl jitsiMeetConference,
            @NotNull ColibriSessionManager colibriSessionManager,
            @NotNull ColibriRequestCallback colibriRequestCallback,
            @NonNull AbstractXMPPConnection xmppConnection,
            @NotNull Bridge bridge,
            long gid,
            @NotNull Logger parentLogger)
    {
        this.colibriSessionManager = colibriSessionManager;
        this.colibriRequestCallback = colibriRequestCallback;
        this.bridge = bridge;
        this.colibriConference = new ColibriConferenceImpl(xmppConnection);
        colibriConference.setName(jitsiMeetConference.getRoomName());
        colibriConference.setGID(Long.toHexString(gid));
        colibriConference.setRtcStatsEnabled(jitsiMeetConference.getConfig().getRtcStatsEnabled());
        colibriConference.setCallStatsEnabled(jitsiMeetConference.getConfig().getCallStatsEnabled());
        ChatRoom chatRoom = jitsiMeetConference.getChatRoom();
        if (chatRoom != null)
        {
            String meetingId = chatRoom.getMeetingId();
            if (meetingId != null)
            {
                colibriConference.setMeetingId(meetingId);
            }
        }
        colibriConference.setJitsiVideobridge(bridge.getJid());

        logger = parentLogger.createChildLogger(BridgeSession.class.getName());
        logger.addContext("bs_id", id);
    }

    void addParticipant(Participant participant)
    {
        participants.add(participant);
        bridge.endpointAdded();
    }

    /**
     * Disposes of this {@link BridgeSession}, attempting to expire the
     * COLIBRI conference.
     */
    void dispose()
    {
        // We will not expire channels if the bridge is faulty or when our connection is down.
        if (!hasFailed)
        {
            colibriConference.expireConference();
        }
        else
        {
            // TODO: make sure this doesn't block waiting for a response
            colibriConference.dispose();
        }

        // TODO: should we terminate (or clear) #participants?
    }

    /**
     * Expires the COLIBRI channels for all participants.
     *
     * @return the list of participants which were removed from
     * {@link #participants} as a result of this call (does not include
     * the Octo participant).
     */
    List<Participant> terminateAll()
    {
        List<Participant> terminatedParticipants = new LinkedList<>();
        // sync on what?
        for (Participant participant : new LinkedList<>(participants))
        {
            ParticipantInfo participantInfo = colibriSessionManager.getParticipantInfo(participant);
            if (participantInfo != null && participantInfo.getHasColibriSession())
            {
                terminatedParticipants.add(participant);
                participant.setChannelAllocator(null);
                participant.clearTransportInfo();
                participant.setColibriChannelsInfo(null);
                participantInfo.setHasColibriSession(false);
            }
        }

        if (octoParticipant != null)
        {
            terminateOctoParticipant();
        }

        return terminatedParticipants;
    }

    /**
     * Expires the COLIBRI channels allocated for a specific {@link Participant} and removes the participant from
     * {@link #participants}.
     *
     * @param participant the {@link Participant} for which to expire the COLIBRI channels.
     * @return {@code true} if the participant was a member of {@link #participants} and was removed as a result of
     * this call, and {@code false} otherwise.
     */
    public boolean terminate(Participant participant)
    {
        boolean removed = participants.remove(participant);

        ColibriConferenceIQ channelsInfo
                = participant != null
                ? participant.getColibriChannelsInfo() : null;

        if (channelsInfo != null && !hasFailed)
        {
            logger.info("Expiring channels for: " + participant + " on: " + bridge);
            colibriConference.expireChannels(channelsInfo);
        }

        return removed;
    }

    private void terminateOctoParticipant()
    {
        OctoParticipant participant = octoParticipant;
        if (participant != null)
        {
            participant.setChannelAllocator(null);
        }
        octoParticipant = null;
    }

    /**
     * Sends a COLIBRI message which updates the channels for a particular
     * {@link Participant} in this {@link BridgeSession}, setting the
     * participant's RTP description, sources, transport information, etc.
     */
    void updateColibriChannels(Participant participant)
    {
        ParticipantInfo participantInfo = colibriSessionManager.getParticipantInfo(participant);
        colibriConference.updateChannelsInfo(
                participant.getColibriChannelsInfo(),
                participantInfo == null ? null : participantInfo.getRtpDescriptionMap(),
                participant.getSources(),
                participant.getBundleTransport(),
                participant.getEndpointId(),
                null);
    }

    /**
     * Sends a COLIBRI message which updates the channels for the Octo
     * participant in this {@link BridgeSession}.
     */
    private void updateColibriOctoChannels(OctoParticipant octoParticipant)
    {
        if (octoParticipant != null)
        {
            colibriConference.updateChannelsInfo(
                    octoParticipant.getColibriChannelsInfo(),
                    null,
                    octoParticipant.getSources(),
                    null,
                    null,
                    octoParticipant.getRelays());
        }
    }

    /**
     * Returns the Octo participant for this {@link BridgeSession}. If
     * a participant doesn't exist yet, it is created.
     *
     * @return the {@link OctoParticipant} for this {@link BridgeSession}.
     */
    private OctoParticipant getOrCreateOctoParticipant()
    {
        if (octoParticipant != null)
        {
            return octoParticipant;
        }

        List<String> remoteRelays = colibriSessionManager.getAllRelays(bridge.getRelayId());
        return getOrCreateOctoParticipant(new LinkedList<>(remoteRelays));
    }

    /**
     * Returns the Octo participant for this {@link BridgeSession}. If
     * a participant doesn't exist yet, it is created and initialized
     * with {@code relays} as the list of remote Octo relays.
     *
     * @return the {@link OctoParticipant} for this {@link BridgeSession}.
     */
    private OctoParticipant getOrCreateOctoParticipant(List<String> relays)
    {
        if (octoParticipant == null)
        {
            octoParticipant = createOctoParticipant(relays);
        }
        return octoParticipant;
    }

    /**
     * Adds sources and source groups to this {@link BridgeSession}'s Octo
     * participant. If the Octo participant's session is already
     * established, then the sources are added and a colibri message is
     * sent to the bridge. Otherwise, they are scheduled to be added once
     * the session is established.
     *
     * @param sources the sources to add.
     */
    void addSourcesToOcto(ConferenceSourceMap sources)
    {
        if (!OctoConfig.config.getEnabled())
        {
            return;
        }

        OctoParticipant octoParticipant = getOrCreateOctoParticipant();

        synchronized (octoParticipant)
        {
            if (octoParticipant.isSessionEstablished())
            {
                octoParticipant.addSources(sources);
                updateColibriOctoChannels(octoParticipant);
            }
            else
            {
                // The allocator will take care of updating these when the
                // session is established.
                octoParticipant.queueRemoteSourcesToAdd(sources);
            }
        }
    }

    /**
     * Removes sources and source groups
     */
    void removeSourcesFromOcto(ConferenceSourceMap sourcesToRemove)
    {
        OctoParticipant octoParticipant = this.octoParticipant;
        if (octoParticipant != null)
        {
            synchronized (octoParticipant)
            {
                if (octoParticipant.isSessionEstablished())
                {
                    octoParticipant.removeSources(sourcesToRemove);

                    updateColibriOctoChannels(octoParticipant);
                }
                else
                {
                    octoParticipant.queueRemoteSourcesToRemove(sourcesToRemove);
                }
            }
        }
    }

    /**
     * Sets the list of Octo relays for this {@link BridgeSession}.
     *
     * @param allRelays all relays in the conference (including the relay
     *                  of the bridge of this {@link BridgeSession}).
     */
    void setRelays(List<String> allRelays)
    {
        List<String> remoteRelays = new LinkedList<>(allRelays);
        remoteRelays.remove(bridge.getRelayId());

        logger.info("Updating Octo relays for " + bridge);

        OctoParticipant octoParticipant = getOrCreateOctoParticipant(remoteRelays);
        octoParticipant.setRelays(remoteRelays);
        if (octoParticipant.isSessionEstablished())
        {
            updateColibriOctoChannels(octoParticipant);
        }
    }

    /**
     * Creates an {@link OctoParticipant} for this {@link BridgeSession}
     * and starts an {@link OctoChannelAllocator} to allocate channels for
     * it.
     *
     * @param relays the list of Octo relay ids to set to the newly
     *               allocated channels.
     * @return the instance which was created.
     */
    private OctoParticipant createOctoParticipant(List<String> relays)
    {
        logger.info("Creating an Octo participant for " + bridge);

        OctoParticipant octoParticipant = new OctoParticipant(relays, logger, bridge.getJid());

        ConferenceSourceMap remoteSources = colibriSessionManager.getSources(participants);

        octoParticipant.addSources(remoteSources);

        OctoChannelAllocator channelAllocator
                = new OctoChannelAllocator(colibriRequestCallback, this, octoParticipant, logger);
        octoParticipant.setChannelAllocator(channelAllocator);

        TaskPools.getIoPool().execute(channelAllocator);

        return octoParticipant;
    }

    @Override
    public String toString()
    {
        return String.format(
                "BridgeSession[id=%s, bridge=%s]@%d",
                id,
                bridge,
                hashCode());
    }
}
