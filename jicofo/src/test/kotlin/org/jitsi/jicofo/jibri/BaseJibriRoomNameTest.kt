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
package org.jitsi.jicofo.jibri

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.xmpp.IqRequest
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate

class BaseJibriRoomNameTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val sentStanzas = mutableListOf<Stanza>()
    val connection: AbstractXMPPConnection = mockk(relaxed = true) {
        every { sendStanza(any()) } answers {
            sentStanzas.add(firstArg())
            Unit
        }
    }

    var startRequestsHandled = 0
    fun createJibri(roomName: EntityBareJid): BaseJibri {
        val conference: JitsiMeetConferenceImpl = mockk(relaxed = true) {
            every { getRoomName() } returns roomName
            every { getRoleForMucJid(any()) } returns MemberRole.MODERATOR
            every { getChatRoom() } returns mockk(relaxed = true) {
                every { queueXmppTask(any()) } answers { firstArg<() -> Unit>().invoke() }
            }
        }
        return object : BaseJibri(conference, LoggerImpl("test"), mockk(relaxed = true)) {
            override fun getJibriSessionForMeetIq(iq: JibriIq): JibriSession? = null
            override val jibriSessions: List<JibriSession> = emptyList()
            override fun acceptType(packet: JibriIq) = true
            override fun handleStartRequest(iq: JibriIq): IQ {
                startRequestsHandled++
                return JibriIq.createResult(iq, "session-$startRequestsHandled")
            }
            override fun onSessionStateChanged(
                jibriSession: JibriSession,
                newStatus: JibriIq.Status,
                failureReason: JibriIq.FailureReason?
            ) = Unit
        }
    }

    fun sendRequest(room: String, action: JibriIq.Action) {
        val roomName = JidCreate.entityBareFrom(room)
        val iq = JibriIq().apply {
            type = IQ.Type.set
            this.action = action
            recordingMode = JibriIq.RecordingMode.STREAM
            streamId = "abcd-1234-efgh-5678"
            from = JidCreate.from("$room/member")
            to = JidCreate.from("focus@example.com")
        }
        createJibri(roomName).handleJibriRequest(IqRequest(iq, connection))
    }

    fun sendStartRequest(room: String) = sendRequest(room, JibriIq.Action.START)

    fun lastErrorCondition(): StanzaError.Condition? = sentStanzas.lastOrNull()?.error?.condition

    context("A start request") {
        should("be handled for a room in the form jitsi-meet uses") {
            sendStartRequest("my%20room@conference.example.com")
            startRequestsHandled shouldBe 1
            lastErrorCondition() shouldBe null
        }
        should("be rejected for a room in a different form") {
            sendStartRequest("lockedvictim?x@conference.example.com")
            startRequestsHandled shouldBe 0
            lastErrorCondition() shouldBe StanzaError.Condition.bad_request
        }
        should("be rejected for a tenant in a different form") {
            sendStartRequest("room@conference.tenant?x.example.com")
            startRequestsHandled shouldBe 0
            lastErrorCondition() shouldBe StanzaError.Condition.bad_request
        }
    }
    context("A stop request") {
        should("not be rejected for a room in a different form") {
            sendRequest("lockedvictim?x@conference.example.com", JibriIq.Action.STOP)
            lastErrorCondition() shouldBe StanzaError.Condition.item_not_found
        }
    }
})
