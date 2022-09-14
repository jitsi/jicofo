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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.jicofo.OctoConfig
import org.jitsi.jicofo.TaskPools
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
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.event.AsyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ.GracefulShutdown
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
import java.util.Collections.singletonList
import java.util.UUID

/**
 * Implements [ColibriSessionManager] using colibri2.
 */
@SuppressFBWarnings("BC_IMPOSSIBLE_INSTANCEOF")
class ColibriV2SessionManager(
    internal val xmppConnection: AbstractXMPPConnection,
    private val bridgeSelector: BridgeSelector,
    private val conference: JitsiMeetConferenceImpl,
    parentLogger: Logger
) : ColibriSessionManager {
    private val logger = createChildLogger(parentLogger)

    private val eventEmitter = AsyncEventEmitter<ColibriSessionManager.Listener>(TaskPools.ioPool)
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
            session.expire()
        }
        sessions.clear()
        eventEmitter.fireEvent { bridgeCountChanged(0) }
        clear()
    }

    override fun removeParticipant(participant: Participant) = synchronized(syncRoot) {
        logger.debug { "Asked to remove ${participant.endpointId}" }

        participants[participant.endpointId]?.let {
            logger.info("Removing ${it.id}")
            removeParticipantInfosBySession(mapOf(it.session to singletonList(it)))
        } ?: logger.warn("Can not remove ${participant.endpointId}, no participantInfo")
        Unit
    }

    private fun removeSession(session: Colibri2Session): Set<ParticipantInfo> {
        val participants = getSessionParticipants(session)
        session.expire()
        sessions.remove(session.bridge)
        participantsBySession.remove(session)
        participants.forEach { remove(it) }
        session.relayId?.let { removedRelayId ->
            sessions.values.forEach { otherSession -> otherSession.expireRelay(removedRelayId) }
        }
        return participants.toSet()
    }

    private fun removeParticipantInfosBySession(bySession: Map<Colibri2Session, List<ParticipantInfo>>):
        Set<ParticipantInfo> {
        var sessionRemoved = false
        val participantsRemoved = mutableSetOf<ParticipantInfo>()
        bySession.forEach { (session, sessionParticipantsToRemove) ->
            logger.debug { "Removing participants from session $session: ${sessionParticipantsToRemove.map { it.id }}" }
            val remaining = getSessionParticipants(session) - sessionParticipantsToRemove.toSet()
            val removeSession = remaining.isEmpty()
            if (removeSession) {
                logger.info("Removing session with no remaining participants: $session")
                val sessionParticipantsRemoved = removeSession(session)

                participantsRemoved.addAll(sessionParticipantsRemoved)
                sessionRemoved = true
            } else {
                session.expire(sessionParticipantsToRemove)
                sessionParticipantsToRemove.forEach { remove(it) }
                participantsRemoved.addAll(sessionParticipantsToRemove)

                // If the session was removed the relays themselves are expired, so there's no need to expire individual
                // endpoints within a relay.
                sessions.values.filter { it != session }.forEach { otherSession ->
                    session.relayId?.let {
                        otherSession.expireRemoteParticipants(sessionParticipantsToRemove, it)
                    }
                }
            }
        }

        if (sessionRemoved) {
            eventEmitter.fireEvent { bridgeCountChanged(sessions.size) }
        }

        return participantsRemoved
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
        get() = synchronized(syncRoot) { sessions.keys.map { it.region }.filterNotNull().toSet() }

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

    @Throws(ColibriAllocationFailedException::class, BridgeSelectionFailedException::class)
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

            // The requests for each session need to be sent in order, but we don't want to hold the lock while
            // waiting for a response. I am not sure if processing responses is guaranteed to be in the order in which
            // the requests were sent.
            val bridge = bridgeSelector.selectBridge(getBridges(), participant.chatMember.region, version)
                ?: run {
                    eventEmitter.fireEvent { bridgeSelectionFailed() }
                    throw BridgeSelectionFailedException()
                }
            eventEmitter.fireEvent { bridgeSelectionSucceeded() }
            if (sessions.isNotEmpty() && sessions.none { it.key == bridge }) {
                // There is an existing session, and this is a new bridge.
                if (!OctoConfig.config.enabled) {
                    logger.error("A new bridge was selected, but Octo is disabled")
                    // This is a bridge selection failure, because the selector should not have returned a different
                    // bridge when Octo is not enabled.
                    throw BridgeSelectionFailedException()
                } else if (sessions.any { it.value.relayId == null } || bridge.relayId == null) {
                    logger.error("Can not enable Octo: one of the selected bridges does not support Octo.")
                    // This is a bridge selection failure, because the selector should not have returned a different
                    // bridge when one of the bridges doesn't support Octo (does not have a relay ID).
                    throw BridgeSelectionFailedException()
                }
            }
            getOrCreateSession(bridge).let {
                session = it.first
                created = it.second
            }
            logger.info("Selected ${bridge.jid.resourceOrNull}, session exists: ${!created}")
            participantInfo = ParticipantInfo(
                participant.endpointId,
                participant.statId,
                sources = participant.sources,
                audioMuted = forceMuteAudio,
                videoMuted = forceMuteVideo,
                session = session,
                supportsSourceNames = participant.hasSourceNameSupport()
            )
            stanzaCollector = session.sendAllocationRequest(participantInfo, contents, useSctp)
            add(participantInfo)
            if (created) {
                sessions.values.filter { it != session }.forEach {
                    logger.debug { "Creating relays between $session and $it." }
                    // We already made sure that relayId is not null when there are multiple sessions.
                    it.createRelay(session.relayId!!, getSessionParticipants(session), initiator = true)
                    session.createRelay(it.relayId!!, getSessionParticipants(it), initiator = false)
                }
            } else {
                sessions.values.filter { it != session }.forEach {
                    logger.debug { "Adding a relayed endpoint to $it for ${participantInfo.id}." }
                    // We already made sure that relayId is not null when there are multiple sessions.
                    it.updateRemoteParticipant(participantInfo, session.relayId!!, create = true)
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
                logger.error("Failed to allocate a colibri2 endpoint for ${participantInfo.id}: ${e.message}")
                if (e is ColibriAllocationFailedException && e.removeBridge) {
                    // Add participantInfo just in case it wasn't there already (the set will take care of dups).
                    val removedParticipants = removeSession(session) + participantInfo
                    remove(participantInfo)
                    eventEmitter.fireEvent { bridgeRemoved(session.bridge, removedParticipants.map { it.id }.toList()) }
                } else {
                    remove(participantInfo)
                }
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
        // bridge as non-operational so the caller can do the right thing:
        // * Do nothing (if this is due to an internal error we don't want to retry indefinitely)
        // * Re-invite the participants (possibly on the same bridge) on this bridge
        // * Re-invite the participants on this bridge to a different bridge
        if (!sessions.containsKey(session.bridge)) {
            logger.warn("The session was removed, ignoring allocation response.")
            // This is a response for a session that has been removed. We want to make sure the participant is
            // re-invited, but it has already been re-invited.
            throw ColibriAllocationFailedException("Session already removed", participants[participantInfo.id] == null)
        }

        if (response == null) {
            session.bridge.isOperational = false
            throw ColibriAllocationFailedException("Timeout", true)
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
                    throw ColibriAllocationFailedException("Bad request: ${response.error?.toXML()?.toString()}", false)
                }
                item_not_found -> {
                    if (reason == Colibri2Error.Reason.CONFERENCE_NOT_FOUND) {
                        // The conference on the bridge has expired. The state between jicofo and the bridge is out of
                        // sync.
                        throw ColibriAllocationFailedException("Conference not found", true)
                    } else {
                        // This is an item_not_found NOT coming from the bridge. Most likely coming from the MUC
                        // because the occupant left.
                        // This is probably a wrong selection decision, and if we re-try we run the risk of it
                        // repeating.
                        throw ColibriAllocationFailedException("Item not found, bridge unavailable?", false)
                    }
                }
                conflict -> {
                    if (reason == null) {
                        // An error NOT coming from the bridge.
                        throw ColibriAllocationFailedException(
                            "XMPP error: ${response.error?.toXML()}",
                            true
                        )
                    } else {
                        // An error coming from the bridge. The state between jicofo and the bridge must be out of sync.
                        // It's not clear how to handle this. Ideally we should expire the conference and retry, but
                        // we can't expire a conference without listing its individual endpoints and we think there
                        // were none.
                        // We remove the bridge from the conference (expiring it) and re-invite the participants.
                        throw ColibriAllocationFailedException("Colibri error: ${response.error?.toXML()}", true)
                    }
                }
                service_unavailable -> {
                    val gracefulShutdown = reason == Colibri2Error.Reason.GRACEFUL_SHUTDOWN ||
                        response.error?.getExtension<GracefulShutdown>(
                        GracefulShutdown.ELEMENT,
                        ColibriConferenceIQ.NAMESPACE
                    ) != null

                    if (gracefulShutdown) {
                        // The fact that this bridge was selected means that we haven't received its updated presence yet,
                        // so set the graceful-shutdown flag explicitly to prevent future selection.
                        session.bridge.isInGracefulShutdown = true
                        throw ColibriAllocationFailedException("Bridge in graceful shutdown", true)
                    } else {
                        session.bridge.isOperational = false
                        throw ColibriAllocationFailedException("Bridge failed with service_unavailable.", true)
                    }
                }
                else -> {
                    session.bridge.isOperational = false
                    throw ColibriAllocationFailedException("Error: ${response.error?.toXML()}", true)
                }
            }
        }

        if (response !is ConferenceModifiedIQ) {
            session.bridge.isOperational = false
            throw ColibriAllocationFailedException("Response of wrong type: ${response::class.java.name}", false)
        }

        if (created) {
            session.feedbackSources = response.parseSources()
        }

        val transport = response.parseTransport(participantInfo.id)
            ?: throw ColibriAllocationFailedException("failed to parse transport", false)
        val sctpPort = transport.sctp?.port
        if (useSctp && sctpPort == null) {
            logger.error("Requested SCTP, but the response had no SCTP.")
            throw ColibriAllocationFailedException("Requested SCTP, but the response had no SCTP", false)
        }

        return ColibriAllocation(
            session.feedbackSources,
            transport.iceUdpTransport ?: throw ColibriAllocationFailedException("failed to parse transport", false),
            session.bridge.region,
            session.id,
            sctpPort
        )
    }

    internal fun sessionFailed(session: Colibri2Session) = synchronized(syncRoot) {
        // Make sure the same instance is still in use. Especially with long timeouts (15s) it's possible that it's
        // already been removed
        if (sessions.values.contains(session)) {
            val removedParticipants = removeSession(session)
            eventEmitter.fireEvent { bridgeRemoved(session.bridge, removedParticipants.map { it.id }.toList()) }
        }
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
                // We make sure that relayId is not null when there are multiple sessions.
                it.updateRemoteParticipant(participantInfo, participantInfo.session.relayId!!, false)
            }
        }
    }

    override fun getBridgeSessionId(participant: Participant): String? = synchronized(syncRoot) {
        return participants[participant.endpointId]?.session?.id
    }

    override fun removeBridge(bridge: Bridge): List<String> = synchronized(syncRoot) {
        val sessionToRemove = sessions.values.find { it.bridge.jid == bridge.jid } ?: return emptyList()
        logger.info("Removing bridges: $bridge")
        val participantsToRemove = getSessionParticipants(sessionToRemove)

        removeParticipantInfosBySession(mapOf(sessionToRemove to participantsToRemove))

        logger.info("Removed participants: ${participantsToRemove.map { it.id }}")
        participantsToRemove.map { it.id }
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
            // We make sure relayId is not null when there are multiple sessions.
            sessions.values.find { it.relayId == relayId }?.setRelayTransport(transport, session.relayId!!)
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
