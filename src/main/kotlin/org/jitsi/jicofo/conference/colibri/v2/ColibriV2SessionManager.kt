/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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

package org.jitsi.jicofo.conference.colibri.v2

import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.colibri.BridgeSelectionFailedException
import org.jitsi.jicofo.conference.colibri.ColibriAllocation
import org.jitsi.jicofo.conference.colibri.ColibriAllocationFailedException
import org.jitsi.jicofo.conference.colibri.ColibriSessionManager
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.IQ
import java.util.UUID

class ColibriV2SessionManager(
    internal val xmppConnection: AbstractXMPPConnection,
    private val bridgeSelector: BridgeSelector,
    conference: JitsiMeetConferenceImpl,
    parentLogger: Logger
) : ColibriSessionManager {
    private val logger = createChildLogger(parentLogger)

    private val eventEmitter = SyncEventEmitter<ColibriSessionManager.Listener>()
    override fun addListener(listener: ColibriSessionManager.Listener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ColibriSessionManager.Listener) = eventEmitter.removeHandler(listener)

    private val sessions = mutableMapOf<Bridge, Colibri2Session>()
    private val participants = mutableMapOf<String, ParticipantInfo>()
    private val participantsBySession = mutableMapOf<Colibri2Session, MutableList<ParticipantInfo>>()
    private val syncRoot = Any()

    /**
     * We want to delay initialization until the chat room is joined in order to use its meetingId.
     */
    internal val meetingId: String by lazy {
        val chatRoomMeetingId = conference.chatRoom?.meetingId
        if (chatRoomMeetingId == null) {
            logger.warn("No meetingId set for the MUC. Generating one locally.")
            UUID.randomUUID().toString()
        } else chatRoomMeetingId
    }

    internal val conferenceName = conference.roomName.toString()

    override fun expire() = synchronized(syncRoot) {
        sessions.values.forEach { session ->
            session.expire(getSessionParticipants(session))
            session.expireAllRelays()
        }
        sessions.clear()
        clear()
    }

    override fun removeParticipant(participant: Participant) = removeParticipants(listOf(participant))
    override fun removeParticipants(participants: Collection<Participant>) = synchronized(syncRoot) {
        participants.forEach { it.setInviteRunnable(null) }

        val participantInfos = participants.mapNotNull { this.participants[it.endpointId] }
        if (participantInfos.size != participants.size) {
            logger.error(
                "Can not remove every participant requested, ParticipantInfo missing: " +
                    "participants=$participants, participantInfos=$participantInfos"
            )
        }
        removeParticipantInfos(participantInfos)
    }

    private fun removeParticipantInfos(participantsToRemove: Collection<ParticipantInfo>) = synchronized(syncRoot) {
        val bySession = mutableMapOf<Colibri2Session, MutableList<ParticipantInfo>>()
        participantsToRemove.forEach {
            bySession.computeIfAbsent(it.session) { mutableListOf() }.add(it)
        }

        removeParticipantInfosBySession(bySession)
    }

    private fun removeParticipantInfosBySession(bySession: Map<Colibri2Session, List<ParticipantInfo>>) {
        var sessionRemoved = false
        bySession.forEach { (session, sessionParticipantsToRemove) ->
            session.expire(sessionParticipantsToRemove)
            sessionParticipantsToRemove.forEach { remove(it) }

            val removeSession = getSessionParticipants(session).isEmpty()

            if (removeSession) {
                sessions.remove(session.bridge)
                session.expireAllRelays()
                sessions.values.forEach { otherSession ->
                    otherSession.expireRelay(session.bridge.relayId)
                }
                sessionRemoved = true
            } else {
                // If the session was removed the relays themselves are expired, so there's no need to expire individual
                // endpoints within a relay.
                sessions.values.filter { it != session }.forEach { otherSession ->
                    otherSession.expireRemoteParticipants(sessionParticipantsToRemove, session.bridge.relayId)
                }
            }
        }

        if (sessionRemoved) {
            eventEmitter.fireEvent { bridgeCountChanged(sessions.size) }
        }
    }

    /**
     * We don't keep track of source-add/source-removes manually and simply take the updated sources from the
     * participant object.
     */
    override fun addSources(participant: Participant, sources: ConferenceSourceMap) =
        updateParticipant(participant, sources = participant.sources)

    /**
     * We don't keep track of source-add/source-removes manually and simply take the updated sources from the
     * participant object.
     */
    override fun removeSources(participant: Participant, sources: ConferenceSourceMap) =
        updateParticipant(participant, sources = participant.sources)

    override fun mute(participant: Participant, doMute: Boolean, mediaType: MediaType): Boolean {
        val stanzaCollector: StanzaCollector
        val participantInfo: ParticipantInfo
        synchronized(syncRoot) {
            participantInfo = participants[participant.endpointId]
                ?: throw IllegalStateException("No participantInfo for $participant")

            if (mediaType == MediaType.AUDIO && participantInfo.audioMuted == doMute ||
                mediaType == MediaType.VIDEO && participantInfo.videoMuted == doMute
            ) {
                return true
            }

            val audioMute = if (mediaType == MediaType.AUDIO) doMute else participantInfo.audioMuted
            val videoMute = if (mediaType == MediaType.VIDEO) doMute else participantInfo.videoMuted
            val session = participantInfo.session
            stanzaCollector = session.mute(participantInfo, audioMute, videoMute)
        }

        val response: IQ?
        try {
            response = stanzaCollector.nextResult()
        } finally {
            stanzaCollector.cancel()
        }

        return if (response is ConferenceModifiedIQ) {
            // Success, update our local state
            if (mediaType == MediaType.AUDIO)
                participantInfo.audioMuted = doMute
            if (mediaType == MediaType.VIDEO)
                participantInfo.videoMuted = doMute
            true
        } else {
            logger.error("Failed to mute ${participant.endpointId}: ${response?.toXML() ?: "timeout"}")
            false
        }
    }

    override val bridgeCount: Int
        get() = synchronized(syncRoot) { sessions.size }
    override val bridgeRegions: Set<String>
        get() = synchronized(syncRoot) { sessions.keys.map { it.region }.toSet() }

    private fun getOrCreateSession(bridge: Bridge): Pair<Colibri2Session, Boolean> = synchronized(syncRoot) {
        var session = sessions[bridge]
        if (session != null) {
            return Pair(session, false)
        }

        session = Colibri2Session(this, bridge, logger)
        sessions[bridge] = session
        return Pair(session, true)
    }

    private fun getBridges(): Map<Bridge, Int> = synchronized(syncRoot) {
        return participantsBySession.entries.associate { Pair(it.key.bridge, it.value.size) }
    }

    @Throws(ColibriAllocationFailedException::class)
    override fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        reInvite: Boolean
    ): ColibriAllocation {
        val stanzaCollector: StanzaCollector
        val session: Colibri2Session
        val created: Boolean
        val participantInfo: ParticipantInfo
        synchronized(syncRoot) {
            if (participants.containsKey(participant.endpointId)) {
                throw IllegalStateException("participant already exists")
            }

            // The requests for each session need to be sent in order, but we don't want to hold the lock while
            // waiting for a response. I am not sure if processing responses is guaranteed to be in the order in which
            // the requests were sent.
            val bridge = bridgeSelector.selectBridge(getBridges(), participant.chatMember.region)
                ?: throw BridgeSelectionFailedException()
            getOrCreateSession(bridge).let {
                session = it.first
                created = it.second
            }
            logger.warn("Selected ${bridge.jid.resourceOrNull} for $participant, session exists: ${!created}")
            participantInfo = ParticipantInfo(participant.endpointId, participant.statId, session = session)
            stanzaCollector = session.sendAllocationRequest(participantInfo, contents, created)
            add(participantInfo)
            if (created) {
                sessions.values.filter { it != session }.forEach {
                    it.createRelay(session.bridge.relayId, getSessionParticipants(session), initiator = true)
                    session.createRelay(it.bridge.relayId, getSessionParticipants(it), initiator = false)
                }
            } else {
                sessions.values.filter { it != session }.forEach {
                    it.updateRemoteParticipant(participantInfo, session.bridge.relayId, create = true)
                }
            }
        }

        if (created) {
            eventEmitter.fireEvent { bridgeCountChanged(sessions.size) }
        }

        val response: IQ?
        try {
            response = stanzaCollector.nextResult()
        } finally {
            stanzaCollector.cancel()
        }

        return try {
            session.processAllocationResponse(response, participant.endpointId, created)
        } catch (e: ColibriAllocationFailedException) {
            logger.warn("Failed to allocate a colibri endpoint for ${participant.endpointId}", e)
            remove(participantInfo)
            throw e
        }
    }

    override fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension?,
        sources: ConferenceSourceMap?,
        // This param is not used for colibri2
        rtpDescriptions: Map<String, RtpDescriptionPacketExtension>?
    ) = synchronized(syncRoot) {
        logger.info("Updating $participant with transport=$transport, sources=$sources")

        val participantInfo = participants[participant.endpointId]
            ?: throw IllegalStateException("No participantInfo for $participant")
        participantInfo.session.updateParticipant(participantInfo, transport, sources)
        if (sources != null) {
            // We don't need to make a copy, because we're already passed an unmodifiable copy.
            // TODO: refactor to make that clear (explicit use of UnmodifiableConferenceSourceMap).
            participantInfo.sources = sources
            sessions.values.filter { it != participantInfo.session }.forEach {
                it.updateRemoteParticipant(participantInfo, participantInfo.session.bridge.relayId, false)
            }
        }
    }

    override fun getBridgeSessionId(participant: Participant): String? = synchronized(syncRoot) {
        return participants[participant.endpointId]?.session?.id
    }

    override fun removeBridges(bridges: Set<Bridge>): List<String> = synchronized(syncRoot) {
        val sessionsToRemove = sessions.values.filter { bridges.contains(it.bridge) }
        val participantsToRemove = sessionsToRemove.flatMap { getSessionParticipants(it) }.map { it.id }

        removeParticipantInfosBySession(sessionsToRemove.associateWith { getSessionParticipants(it) })

        participantsToRemove
    }

    internal fun setRelayTransport(
        session: Colibri2Session,
        transport: IceUdpTransportPacketExtension,
        relayId: String
    ) {
        synchronized(syncRoot) {
            // It's possible a new session was started for the same bridge.
            if (!sessions.containsKey(session.bridge) || sessions[session.bridge] != session) {
                logger.info("Received a response for a session that is no longer active. Ignoring.")
            }
            sessions.values.find { it.bridge.relayId == relayId }?.setRelayTransport(transport, session.bridge.relayId)
                ?: { logger.error("Response for a relay that is no longer active. Ignoring.") }
        }
    }

    private fun clear() {
        participants.clear()
        participantsBySession.clear()
    }

    private fun getSessionParticipants(session: Colibri2Session): List<ParticipantInfo> =
        participantsBySession[session] ?: emptyList()

    private fun remove(participantInfo: ParticipantInfo) {
        participants.remove(participantInfo.id)
        participantsBySession[participantInfo.session]?.remove(participantInfo)
    }

    private fun add(participantInfo: ParticipantInfo) {
        participants[participantInfo.id] = participantInfo
        participantsBySession.computeIfAbsent(participantInfo.session) { mutableListOf() }.add(participantInfo)
    }
}
