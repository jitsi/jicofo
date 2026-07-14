/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.jicofo.mock

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.xmpp.jingle.JingleSession
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jxmpp.jid.impl.JidCreate
import java.util.logging.Level

/**
 * Wires up a real [JitsiMeetConferenceImpl] against mock XMPP (colibri2 and Jingle responders, a mock chat room) so
 * conference-level behavior can be tested without a real XMPP connection or bridge.
 */
class ConferenceHarness(roomNameString: String = "test@example.com") {
    val roomName = JidCreate.entityBareFrom(roomNameString)
    val xmppConnection = ColibriAndJingleXmppConnection()
    val jingleSessions = mutableListOf<JingleSession>()
    val xmppProvider = MockXmppProvider(xmppConnection.xmppConnection)
    val chatRoom = xmppProvider.getRoom(roomName)

    /** Whether the conference has ended (fired conferenceEnded on its listener). */
    var ended = false
        private set

    private var memberCounter = 1
    private fun nextMemberId() = "member-${memberCounter++}"

    val conference: JitsiMeetConferenceImpl = JitsiMeetConferenceImpl(
        roomName,
        mockk {
            every { conferenceEnded(any()) } answers { ended = true }
            every { meetingIdSet(any(), any()) } returns true
        },
        HashMap(),
        Level.INFO,
        null,
        false,
        mockk(relaxed = true) {
            every { clientConnection } returns xmppProvider.xmppProvider
            every { serviceConnection } returns xmppProvider.xmppProvider
            every { jingleHandler } returns mockk(relaxed = true) {
                every { registerSession(capture(jingleSessions)) } returns Unit
            }
        },
        mockk(relaxed = true) {
            every { selectBridge(any(), any(), any()) } returns mockk(relaxed = true) {
                every { jid } returns JidCreate.from("jvb@example.com/jvb1")
                every { debugState } returns JsonNodeFactory.instance.objectNode()
            }
        },
        null,
        null,
        null,
        mockk(relaxed = true)
    ).apply { start() }

    /**
     * Add [n] members to the chat room and accept the Jingle sessions that jicofo initiates towards them.
     */
    fun addParticipants(n: Int): List<ChatRoomMember> {
        val members = buildList { repeat(n) { add(chatRoom.addMember(nextMemberId())) } }

        members.forEach { member ->
            val remoteParticipant = xmppConnection.remoteParticipants[member.occupantJid]!!
            val jingleSession = jingleSessions.find { it.sid == remoteParticipant.sessionInitiate.sid }!!
            jingleSession.processIq(remoteParticipant.createSessionAccept())
        }
        return members
    }

    fun getParticipant(member: ChatRoomMember): Participant? = conference.getParticipant(member.occupantJid)
    fun getRemoteParticipant(member: ChatRoomMember): ColibriAndJingleXmppConnection.RemoteParticipant? =
        xmppConnection.remoteParticipants[member.occupantJid]
}
