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
import org.jitsi.utils.time.FakeClock
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration

/**
 * Tests the per-conference rate limit on jibri start requests. The defaults in reference.conf are 5 requests per
 * 5 minutes, with at least 10 seconds between them.
 */
class BaseJibriRateLimitTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val roomName = JidCreate.entityBareFrom("room@conference.example.com")
    val memberJid = JidCreate.from("room@conference.example.com/member")
    val clock = FakeClock()

    val sentStanzas = mutableListOf<Stanza>()
    val connection: AbstractXMPPConnection = mockk(relaxed = true) {
        every { sendStanza(any()) } answers {
            sentStanzas.add(firstArg())
            Unit
        }
    }

    val conference: JitsiMeetConferenceImpl = mockk(relaxed = true) {
        every { getRoomName() } returns roomName
        every { getRoleForMucJid(any()) } returns MemberRole.MODERATOR
        // Run queued tasks in place so the test does not have to wait for the room's queue.
        every { getChatRoom() } returns mockk(relaxed = true) {
            every { queueXmppTask(any()) } answers { firstArg<() -> Unit>().invoke() }
        }
    }

    var startRequestsHandled = 0
    val jibri = object : BaseJibri(conference, LoggerImpl("test"), mockk(relaxed = true), clock) {
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

    fun sendStartRequest() {
        val iq = JibriIq().apply {
            type = IQ.Type.set
            action = JibriIq.Action.START
            recordingMode = JibriIq.RecordingMode.STREAM
            streamId = "abcd-1234-efgh-5678"
            from = memberJid
            to = JidCreate.from("focus@example.com")
        }
        jibri.handleJibriRequest(IqRequest(iq, connection))
    }

    /** The name of the first child element of `<error>` in the serialized [stanza], or null if there is none. */
    fun firstErrorChildName(stanza: Stanza): String? {
        val parser = PacketParserUtils.getParserFor(stanza.toXML().toString())
        var inError = false
        var event = parser.next()
        while (event != XmlPullParser.Event.END_DOCUMENT) {
            if (event == XmlPullParser.Event.START_ELEMENT) {
                if (inError) {
                    return parser.name
                }
                inError = parser.name == "error"
            }
            event = parser.next()
        }
        return null
    }

    /**
     * Prosody's mod_filter_iq_jibri rejects requests that come too quickly with the same error, and the client
     * shows "too quickly" for it. A resource-constraint error would show "all recorders are busy" instead.
     *
     * The client reads only the name of the first child element of `<error>` (lib-jitsi-meet
     * JibriSession._setErrorFromIq), so check the serialized XML too, not only the parsed condition.
     */
    fun shouldBeRateLimitedError(stanza: Stanza?) {
        stanza?.error?.condition shouldBe StanzaError.Condition.policy_violation
        stanza?.error?.type shouldBe StanzaError.Type.WAIT
        stanza?.let { firstErrorChildName(it) } shouldBe "policy-violation"
    }

    context("A conference which repeats start requests") {
        should("stop being served once it exceeds its budget") {
            // The first request is always accepted, then one per min-interval up to max-requests.
            repeat(10) {
                sendStartRequest()
                clock.elapse(Duration.ofSeconds(15))
            }

            startRequestsHandled shouldBe 5
            shouldBeRateLimitedError(sentStanzas.lastOrNull())
        }

        should("be served again after the interval passes") {
            repeat(10) {
                sendStartRequest()
                clock.elapse(Duration.ofSeconds(15))
            }
            startRequestsHandled shouldBe 5

            clock.elapse(Duration.ofMinutes(5))
            sendStartRequest()
            startRequestsHandled shouldBe 6
        }
    }

    context("Requests which come faster than the minimum interval") {
        should("be rejected") {
            sendStartRequest()
            startRequestsHandled shouldBe 1

            clock.elapse(Duration.ofSeconds(1))
            sendStartRequest()
            startRequestsHandled shouldBe 1
            shouldBeRateLimitedError(sentStanzas.lastOrNull())
        }
    }
})
