/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
package org.jitsi.jicofo

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.jicofo.xmpp.ConferenceRoomFilter
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.impl.JidCreate

class ConferenceIqHandlerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val conferenceMucJid = JidCreate.domainBareFrom("conference.example.com")
    private val breweryJid = JidCreate.entityBareFrom("jibribrewery@conference.example.com")

    private val focusManager: FocusManager = mockk {
        every { getConference(any()) } returns null
        every { conferenceRequest(any(), any()) } returns mockk<JitsiMeetConference> {
            every { isStarted() } returns true
            every { chatRoom } returns null
        }
    }

    private val xmppConnection: AbstractXMPPConnection = mockk(relaxed = true)
    private val xmppProvider: XmppProvider = mockk(relaxed = true) {
        every { xmppConnection } returns this@ConferenceIqHandlerTest.xmppConnection
    }

    /** Whether the discovery of XMPP components is still pending. */
    private var discoveryPending = false

    private fun createHandler(roomFilterEnabled: Boolean = true) = ConferenceIqHandler(
        xmppProvider = xmppProvider,
        focusManager = focusManager,
        focusAuthJid = "",
        authAuthority = null,
        jigasiEnabled = false,
        visitorsManager = mockk(relaxed = true),
        roomFilter = ConferenceRoomFilter(
            conferenceMucJid,
            reservedRooms = setOf(breweryJid),
            discoveryPending = { discoveryPending },
            enabled = roomFilterEnabled,
            additionalDomains = emptyList()
        )
    )

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    private fun conferenceIq(room: String) = ConferenceIq().apply {
        from = JidCreate.from("user@example.com/resource")
        this.room = JidCreate.entityBareFrom(room)
        to = JidCreate.from("focus.example.com")
        type = IQ.Type.set
    }

    private fun IQ.shouldBeError(condition: StanzaError.Condition) {
        type shouldBe IQ.Type.error
        error.condition shouldBe condition
    }

    init {
        context("Handling a ConferenceIQ") {
            val handler = createHandler()

            should("accept a room in the conference MUC domain") {
                handler.handleConferenceIq(conferenceIq("testRoom@conference.example.com"))
                    .shouldBeInstanceOf<ConferenceIq>()
                verify(exactly = 1) { focusManager.conferenceRequest(any(), any()) }
            }
            should("accept a room in a tenant sub-domain") {
                handler.handleConferenceIq(conferenceIq("testRoom@conference.tenant.example.com"))
                    .shouldBeInstanceOf<ConferenceIq>()
                verify(exactly = 1) { focusManager.conferenceRequest(any(), any()) }
            }
            should("reject a room in another domain") {
                handler.handleConferenceIq(conferenceIq("testRoom@internal.auth.example.com"))
                    .shouldBeError(StanzaError.Condition.bad_request)
                verify(exactly = 0) { focusManager.conferenceRequest(any(), any()) }
            }
            should("reject a brewery room") {
                handler.handleConferenceIq(conferenceIq(breweryJid.toString()))
                    .shouldBeError(StanzaError.Condition.bad_request)
                verify(exactly = 0) { focusManager.conferenceRequest(any(), any()) }
            }
            should("accept a breakout room once the breakout rooms component is discovered") {
                handler.handleConferenceIq(conferenceIq("testRoom@breakout.example.com"))
                    .shouldBeError(StanzaError.Condition.bad_request)

                handler.componentsChanged(setOf(XmppProvider.Component("breakout_rooms", "breakout.example.com")))

                handler.handleConferenceIq(conferenceIq("testRoom@breakout.example.com"))
                    .shouldBeInstanceOf<ConferenceIq>()
                verify(exactly = 1) { focusManager.conferenceRequest(any(), any()) }
            }
            should("accept a breakout room while the discovery of components is pending") {
                discoveryPending = true
                handler.handleConferenceIq(conferenceIq("testRoom@breakout.example.com"))
                    .shouldBeInstanceOf<ConferenceIq>()
            }
        }
        context("When handling the request throws") {
            every { focusManager.getConference(any()) } throws RuntimeException("test")
            val iq = conferenceIq("testRoom@conference.example.com")
            createHandler().handleIQRequest(iq)

            should("still send an error response") {
                val sent = slot<Stanza>()
                verify(exactly = 1) { xmppConnection.sendStanza(capture(sent)) }
                val response = sent.captured.shouldBeInstanceOf<IQ>()
                response.shouldBeError(StanzaError.Condition.internal_server_error)
                response.to shouldBe iq.from
            }
        }
        context("With the room domain filter disabled") {
            val handler = createHandler(roomFilterEnabled = false)

            should("accept a room in any domain") {
                handler.handleConferenceIq(conferenceIq("testRoom@internal.auth.example.com"))
                    .shouldBeInstanceOf<ConferenceIq>()
            }
            should("still reject a brewery room") {
                handler.handleConferenceIq(conferenceIq(breweryJid.toString()))
                    .shouldBeError(StanzaError.Condition.bad_request)
                verify(exactly = 0) { focusManager.conferenceRequest(any(), any()) }
            }
        }
        context("When the room is in use") {
            every { focusManager.conferenceRequest(any(), any()) } throws XmppProvider.RoomExistsException("in use")
            createHandler().handleConferenceIq(conferenceIq("testRoom@conference.example.com"))
                .shouldBeError(StanzaError.Condition.conflict)
        }
        context("When starting the conference fails") {
            every { focusManager.conferenceRequest(any(), any()) } throws RuntimeException("test")
            createHandler().handleConferenceIq(conferenceIq("testRoom@conference.example.com"))
                .shouldBeError(StanzaError.Condition.internal_server_error)
        }
    }
}
