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
import org.jitsi.jicofo.conference.colibri.BadColibriRequestException
import org.jitsi.jicofo.conference.colibri.BridgeFailedException
import org.jitsi.jicofo.conference.colibri.BridgeInGracefulShutdownException
import org.jitsi.jicofo.conference.colibri.BridgeSelectionFailedException
import org.jitsi.jicofo.conference.colibri.ColibriAllocation
import org.jitsi.jicofo.conference.colibri.ColibriAllocationFailedException
import org.jitsi.jicofo.conference.colibri.ColibriConferenceDisposedException
import org.jitsi.jicofo.conference.colibri.ColibriConferenceExpiredException
import org.jitsi.jicofo.conference.colibri.ColibriParsingException
import org.jitsi.jicofo.conference.colibri.ColibriSessionManager
import org.jitsi.jicofo.conference.colibri.ColibriTimeoutException
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError.Condition.bad_request
import org.jivesoftware.smack.packet.StanzaError.Condition.conflict
import org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found
import org.jivesoftware.smack.packet.StanzaError.Condition.service_unavailable
import org.json.simple.JSONArray
import java.util.UUID

/**
 * Implements [ColibriSessionManager] using colibri2.
 */
class ColibriV2SessionManager(
    internal val xmppConnection: AbstractXMPPConnection,
    private val bridgeSelector: BridgeSelector,
    private val conference: JitsiMeetConferenceImpl,
    parentLogger: Logger
) : ColibriSessionManager {
    private val logger = createChildLogger(parentLogger)

    private val eventEmitter = SyncEventEmitter<ColibriSessionManager.Listener>()
    override fun addListener(listener: ColibriSessionManager.Listener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ColibriSessionManager.Listener) = eventEmitter.removeHandler(listener)

    /**
     * The colibri2 sessions that are currently active, mapped by the [Bridge] that they use.
     */
    private val sessions = mutableMapOf<Bridge, Colibri2Session>()

    /**
     * The set of participants that have associated colibri2 endpoints allocated, mapped by their ID. A participant is
     * represented by a [ParticipantInfo] instance. Needs to be kept in sync with [participantsBySession].
     */
    private val participants = mutableMapOf<String, ParticipantInfo>()

    /**
     * Maintains the same set as [participants], but organized by their session. Needs to be kept in sync with
     * [participants] (see [add], [remove], [clear]).
     */
    private val participantsBySession = mutableMapOf<Colibri2Session, MutableList<ParticipantInfo>>()

    /**
     * Protects access to [sessions], [participants] and [participantsBySession].
     *
     * Note that we currently fire some events via [eventEmitter] while holding this lock.
     */
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
    internal val callstatsEnabled = conference.config.callStatsEnabled
    internal val rtcStatsEnabled = conference.config.rtcStatsEnabled

    /**
     * Expire everything.
     */
    override fun expire() = synchronized(syncRoot) {
        logger.info("Expiring.")
        sessions.values.forEach { session ->
            logger.debug { "Expiring $session" }
            session.expire(getSessionParticipants(session))
            session.expireAllRelays()
        }
        sessions.clear()
        eventEmitter.fireEvent { bridgeCountChanged(0) }
        clear()
    }

    override fun removeParticipants(participants: Collection<Participant>) = synchronized(syncRoot) {
        participants.forEach { it.setInviteRunnable(null) }
        logger.debug { "Asked to remove participants: ${participants.map { it.endpointId}}" }

        val participantInfos = participants.mapNotNull { this.participants[it.endpointId] }
        logger.info("Removing participants: ${participantInfos.map { it.id }}")
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
            logger.debug { "Removing participants from session $session: ${sessionParticipantsToRemove.map { it.id }}" }
            session.expire(sessionParticipantsToRemove)
            sessionParticipantsToRemove.forEach { remove(it) }

            val removeSession = getSessionParticipants(session).isEmpty()

            if (removeSession) {
                logger.info("Removing session with no remaining participants: $session")
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

    override fun mute(participantIds: Set<String>, doMute: Boolean, mediaType: MediaType): Boolean {

        synchronized(syncRoot) {
            val participantsToMuteBySession = mutableMapOf<Colibri2Session, MutableSet<ParticipantInfo>>()

            participantIds.forEach {
                val participantInfo = participants[it]
                if (participantInfo == null) {
                    logger.error("No ParticipantInfo for $it, can not force mute.")
                    return@forEach
                }

                if (mediaType == MediaType.AUDIO && participantInfo.audioMuted == doMute ||
                    mediaType == MediaType.VIDEO && participantInfo.videoMuted == doMute
                ) {
                    // No change required.
                    return@forEach
                }

                // We set the updated state before we even send the request, i.e. we assume the request was successful.
                // If an error occurs we should stop the whole colibri session with that bridge (TODO).
                if (mediaType == MediaType.AUDIO) {
                    participantInfo.audioMuted = doMute
                }
                if (mediaType == MediaType.VIDEO) {
                    participantInfo.videoMuted = doMute
                }

                participantsToMuteBySession.computeIfAbsent(participantInfo.session) {
                    mutableSetOf()
                }.add(participantInfo)
            }

            participantsToMuteBySession.forEach { (session, participantsToMute) ->
                session.updateForceMute(participantsToMute)
            }
        }
        return true
    }

    override val bridgeCount: Int
        get() = synchronized(syncRoot) { sessions.size }
    override val bridgeRegions: Set<String>
        get() = synchronized(syncRoot) { sessions.keys.map { it.region }.toSet() }

    /**
     * Get the [Colibri2Session] for a specific [Bridge]. If one doesn't exist, create it. Returns the session and
     * a boolean indicating whether the session was just created (true) or existed (false).
     */
    private fun getOrCreateSession(bridge: Bridge): Pair<Colibri2Session, Boolean> = synchronized(syncRoot) {
        var session = sessions[bridge]
        if (session != null) {
            return Pair(session, false)
        }

        session = Colibri2Session(this, bridge, logger)
        sessions[bridge] = session
        return Pair(session, true)
    }

    /** Get the bridge-to-participant-count needed for bridge selection. */
    private fun getBridges(): Map<Bridge, Int> = synchronized(syncRoot) {
        return participantsBySession.entries
            .filter { it.key.bridge.isOperational }
            .associate { Pair(it.key.bridge, it.value.size) }
    }

    @Throws(ColibriAllocationFailedException::class)
    override fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        forceMuteAudio: Boolean,
        forceMuteVideo: Boolean
    ): ColibriAllocation {
        logger.info("Allocating for ${participant.endpointId}")
        val stanzaCollector: StanzaCollector
        val session: Colibri2Session
        val created: Boolean
        val participantInfo: ParticipantInfo
        val useSctp = contents.any { it.name == "data" }
        synchronized(syncRoot) {
            if (participants.containsKey(participant.endpointId)) {
                throw IllegalStateException("participant already exists")
            }

            val version = conference.bridgeVersion
            if (version != null) {
                logger.info("Selecting bridge. Conference is pinned to version \"$version\"")
            }

            val visitor = (participant.chatMember.role == MemberRole.VISITOR)

            // The requests for each session need to be sent in order, but we don't want to hold the lock while
            // waiting for a response. I am not sure if processing responses is guaranteed to be in the order in which
            // the requests were sent.
            val bridge = bridgeSelector.selectBridge(getBridges(), participant.chatMember.region, version)
                ?: throw BridgeSelectionFailedException()
            getOrCreateSession(bridge).let {
                session = it.first
                created = it.second
            }
            logger.info("Selected ${bridge.jid.resourceOrNull}, session exists: ${!created}")
            participantInfo = ParticipantInfo(
                participant.endpointId,
                participant.statId,
                audioMuted = forceMuteAudio,
                videoMuted = forceMuteVideo,
                session = session,
                visitor = (participant.chatMember.role == MemberRole.VISITOR),
                supportsSourceNames = participant.hasSourceNameSupport()
            )
            stanzaCollector = session.sendAllocationRequest(participantInfo, contents, useSctp)
            add(participantInfo)
            if (created) {
                sessions.values.filter { it != session }.forEach {
                    logger.debug { "Creating relays between $session and $it." }
                    it.createRelay(session.bridge.relayId, getSessionParticipants(session), initiator = true)
                    session.createRelay(it.bridge.relayId, getSessionParticipants(it), initiator = false)
                }
            } else {
                sessions.values.filter { it != session }.forEach {
                    logger.debug { "Adding a relayed endpoint to $it for ${participantInfo.id}." }
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
            logger.trace { "Received response: ${response?.toXML()}" }
        } finally {
            stanzaCollector.cancel()
        }

        synchronized(syncRoot) {
            try {
                return handleResponse(response, session, created, participantInfo, useSctp)
            } catch (e: Exception) {
                // TODO: the conference does not know that participants were removed and need to be re-invited (they
                //  will eventually time-out ICE and trigger a restart). This is equivalent to colibri v1 and it's
                //  hard to avoid right now.
                logger.error("Failed to allocate a colibri2 endpoint for ${participantInfo.id}", e)
                removeParticipantInfosBySession(mapOf(session to getSessionParticipants(session)))
                remove(participantInfo)
                throw e
            }
        }
    }

    private fun handleResponse(
        response: IQ?,
        session: Colibri2Session,
        created: Boolean,
        participantInfo: ParticipantInfo,
        useSctp: Boolean
    ): ColibriAllocation {

        // The game we're playing here is throwing the appropriate exception type and setting or not setting the
        // bridge as non-operational to make [ParticipantInviteRunnable] do the right thing:
        // * Do nothing (if this is due to an internal error we don't want to retry indefinitely)
        // * Re-invite the participants on this bridge
        // * Re-invite the participants on this bridge to a different bridge
        //
        // Once colibri v1 is removed, it will be easier to organize this better.
        if (!sessions.containsKey(session.bridge)) {
            logger.warn("The session was removed, ignoring allocation response.")
            throw ColibriConferenceDisposedException()
        }

        if (response == null) {
            session.bridge.setIsOperational(false)
            throw ColibriTimeoutException(session.bridge)
        } else if (response is ErrorIQ) {
            // The reason in a colibri2 error extension, if one is present. If a reason is present we know the response
            // comes from a jitsi-videobridge instance. Otherwise, it might come from another component (e.g. the
            // XMPP server or MUC component).
            val reason = response.error?.getExtension<Colibri2Error>(
                Colibri2Error.ELEMENT,
                Colibri2Error.NAMESPACE
            )?.reason
            logger.info("Received error response: ${response.toXML()}")
            when (response.error?.condition) {
                bad_request -> {
                    // Most probably we sent a bad request.
                    // If we flag the bridge as non-operational we may disrupt other conferences.
                    // If we trigger a re-invite we may cause the same error repeating.
                    throw BadColibriRequestException(response.error?.toXML()?.toString() ?: "null")
                }
                item_not_found -> {
                    if (reason == Colibri2Error.Reason.CONFERENCE_NOT_FOUND) {
                        // The conference on the bridge has expired. The state between jicofo and the bridge is out of
                        // sync.
                        throw ColibriConferenceExpiredException(session.bridge, true)
                    } else {
                        // This is an item_not_found NOT coming from the bridge. Most likely coming from the MUC
                        // because the occupant left.
                        throw BridgeFailedException(session.bridge, true)
                    }
                }
                conflict -> {
                    if (reason == null) {
                        // An error NOT coming from the bridge.
                        throw BridgeFailedException(session.bridge, true)
                    } else {
                        // An error coming from the bridge. The state between jicofo and the bridge must be out of sync.
                        // It's not clear how to handle this. Ideally we should expire the conference and retry, but
                        // we can't expire a conference without listing its individual endpoints and we think there
                        // were none.
                        // We don't bring the whole bridge down.
                        throw BridgeFailedException(session.bridge, false)
                    }
                }
                service_unavailable -> {
                    // This only happens if the bridge is in graceful-shutdown and we request a new conference.
                    throw BridgeInGracefulShutdownException()
                }
                else -> {
                    session.bridge.setIsOperational(false)
                    throw BridgeFailedException(session.bridge, true)
                }
            }
        }

        if (response !is ConferenceModifiedIQ) {
            session.bridge.setIsOperational(false)
            throw BadColibriRequestException("Response of wrong type: ${response::class.java.name}")
        }

        if (created) {
            session.feedbackSources = response.parseSources()
        }

        val transport = response.parseTransport(participantInfo.id)
            ?: throw ColibriParsingException("failed to parse transport")
        val sctpPort = transport.sctp?.port
        if (useSctp && sctpPort == null) {
            logger.error("Requested SCTP, but the response had no SCTP.")
            throw BridgeFailedException(session.bridge, false)
        }

        return ColibriAllocation(
            session.feedbackSources,
            transport.iceUdpTransport ?: throw ColibriParsingException("failed to parse transport"),
            session.bridge.region,
            session.id,
            sctpPort
        )
    }

    override fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension?,
        sources: ConferenceSourceMap?,
        suppressLocalBridgeUpdate: Boolean
    ) = synchronized(syncRoot) {
        logger.info("Updating $participant with transport=$transport, sources=$sources")

        val participantInfo = participants[participant.endpointId]
            ?: run {
                // This can happen after a colibri session is removed due to a failure to allocate, since we never
                // notify the JitsiMeetConferenceImpl object of the failure.
                logger.error("No ParticipantInfo for ${participant.endpointId}")
                return
            }
        if (!suppressLocalBridgeUpdate) {
            participantInfo.session.updateParticipant(participantInfo, transport, sources)
        }
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
        logger.info("Removing bridges: ${bridges.map { it.jid.resourceOrNull }}")
        val sessionsToRemove = sessions.values.filter { bridges.contains(it.bridge) }
        logger.info("Removing sessions: $sessionsToRemove")
        val participantsToRemove = sessionsToRemove.flatMap { getSessionParticipants(it) }.map { it.id }

        removeParticipantInfosBySession(sessionsToRemove.associateWith { getSessionParticipants(it) })
        eventEmitter.fireEvent { failedBridgesRemoved(sessionsToRemove.size) }

        logger.info("Removed participants: $participantsToRemove")
        participantsToRemove
    }

    override val debugState
        get() = OrderedJsonObject().apply {
            synchronized(syncRoot) {
                val participantsJson = OrderedJsonObject()
                participants.values.forEach { participantsJson[it.id] = it.toJson() }
                put("participants", participantsJson)

                val sessionsJson = OrderedJsonObject()
                sessions.values.forEach {
                    sessionsJson[it.bridge.jid.resourceOrNull.toString()] = it.toJson().also { sessionJson ->
                        sessionJson["participants"] = JSONArray().apply {
                            getSessionParticipants(it).forEach { participant -> add(participant.id) }
                        }
                    }
                }
                put("sessions", sessionsJson)
            }
        }

    /**
     * Sets the transport information for a relay. Since relays connect two different [Colibri2Session]s which don't
     * know about each other, their communications goes through [setRelayTransport]
     */
    internal fun setRelayTransport(
        /** The session which received transport information for a relay. */
        session: Colibri2Session,
        /** The transport information for [session]'s bridge, which needs to be passed to the other session. */
        transport: IceUdpTransportPacketExtension,
        /** The ID of the relay, which can be used to find the other session. */
        relayId: String
    ) {
        logger.info("Received transport from $session for relay $relayId")
        logger.debug { "Received transport from $session for relay $relayId: ${transport.toXML()}" }
        synchronized(syncRoot) {
            // It's possible a new session was started for the same bridge.
            if (!sessions.containsKey(session.bridge) || sessions[session.bridge] != session) {
                logger.info("Received a response for a session that is no longer active. Ignoring.")
                return
            }
            sessions.values.find { it.bridge.relayId == relayId }?.setRelayTransport(transport, session.bridge.relayId)
                ?: { logger.warn("Response for a relay that is no longer active. Ignoring.") }
        }
    }

    private fun clear() {
        participants.clear()
        participantsBySession.clear()
    }

    private fun getSessionParticipants(session: Colibri2Session): List<ParticipantInfo> =
        participantsBySession[session]?.toList() ?: emptyList()

    private fun remove(participantInfo: ParticipantInfo) {
        participants.remove(participantInfo.id)
        participantsBySession[participantInfo.session]?.remove(participantInfo)
    }

    private fun add(participantInfo: ParticipantInfo) {
        participants[participantInfo.id] = participantInfo
        participantsBySession.computeIfAbsent(participantInfo.session) { mutableListOf() }.add(participantInfo)
    }
}
