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

import org.jitsi.jicofo.JicofoServices
import org.jitsi.jicofo.bridge.Bridge
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
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid

class ColibriV2SessionManager(
    private val jicofoServices: JicofoServices,
    private val conference: JitsiMeetConferenceImpl,
    parentLogger: Logger
) : ColibriSessionManager {
    private val logger = createChildLogger(parentLogger)

    private val eventEmitter = SyncEventEmitter<ColibriSessionManager.Listener>()
    override fun addListener(listener: ColibriSessionManager.Listener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ColibriSessionManager.Listener) = eventEmitter.removeHandler(listener)

    private val sessions: MutableMap<Bridge, Colibri2Session> = mutableMapOf()
    private val syncRoot = Any()

    override fun expire() {
        // TODO("Not yet implemented")
    }

    override fun removeParticipant(participant: Participant) {
        // TODO("Not yet implemented")
    }

    override fun removeParticipants(participants: Collection<Participant>) = synchronized(syncRoot) {
        participants.forEach { removeParticipant(it) }
    }

    override fun addSources(participant: Participant, sources: ConferenceSourceMap) =
        updateParticipant(participant, sources = participant.sources)

    override fun removeSources(participant: Participant, sources: ConferenceSourceMap) =
        updateParticipant(participant, sources = participant.sources)

    override fun mute(participant: Participant, doMute: Boolean, mediaType: MediaType): Boolean {
        TODO("Not yet implemented")
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

        session = Colibri2Session(
            jicofoServices.xmppServices.serviceConnection.xmppConnection,
            conference.roomName.toString(),
            // TODO: generate a meeting ID if missing.
            conference.chatRoom.meetingId,
            bridge,
            logger)
        sessions[bridge] = session
        return Pair(session, true)
    }

    private fun getBridges(): Map<Bridge, Int> = synchronized(syncRoot) {
        // TODO count participants
        sessions.values.associate { Pair(it.bridge, 1) }
    }

    @Throws(ColibriAllocationFailedException::class)
    override fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        reInvite: Boolean
    ): ColibriAllocation {
        val stanzaCollector: StanzaCollector
        val session: Colibri2Session
        synchronized(syncRoot) {
            // The requests for each session need to be sent in order, but we don't want to hold the lock while
            // waiting for a response. I am not sure if processing responses is guaranteed to be in the order in which
            // the requests were sent.
            val bridge = jicofoServices.bridgeSelector.selectBridge(getBridges(), participant.chatMember.region)
                ?: throw BridgeSelectionFailedException()
            val (s, created) = getOrCreateSession(bridge)
            logger.warn("Selected ${bridge.jid.resourceOrNull} for $participant, session exists: ${!created}")
            session = s
            stanzaCollector = session.sendAllocationRequest(participant, contents, created)
        }

        val response: IQ?
        try {
            response = stanzaCollector.nextResult()
        }
        finally {
            stanzaCollector.cancel()
        }
        return session.processAllocationResponse(response, participant)
    }

    override fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension?,
        sources: ConferenceSourceMap?,
        rtpDescriptions: Map<String, RtpDescriptionPacketExtension>?
    ) {
        logger.warn(
            "Updating $participant with transport=$transport, sources=$sources, rtpDescriptions=$rtpDescriptions"
        )

        val session = getSession(participant) ?: throw IllegalStateException("No session for participant")
        session.updateParticipant(participant, transport, sources, rtpDescriptions)
    }

    override fun getBridgeSessionId(participant: Participant): String? = null
        //TODO("Not yet implemented")

    override fun removeBridges(bridges: Set<Jid>): List<Participant> {
        TODO("Not yet implemented")
    }

    // TODO: implement
    private fun getSession(participant: Participant): Colibri2Session? = synchronized(syncRoot) {
        sessions.values.firstOrNull()
    }
}
