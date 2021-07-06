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
package org.jitsi.jicofo.xmpp.jigasi

import MockJigasi
import MockXmppEndpoint
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.JitsiMeetConference
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.util.ListConferenceStore
import org.jitsi.jicofo.xmpp.JigasiIqHandler
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.xmpp.extensions.rayo.RayoIqProvider.DialIq
import org.jivesoftware.smack.packet.EmptyResultIQ
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError.Condition
import org.jxmpp.jid.impl.JidCreate

class JigasiIqHandlerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance

    val jicofo = MockXmppEndpoint(JidCreate.from("jicofo@example.com")).apply {
        // This prevents the test from blocking for the default 15 seconds, and makes sure that jicofo detects a
        // jigasi timeout before the participant detects a timeout from jicofo.
        xmppConnection.replyTimeout = 1000
    }
    private val conferenceJid = JidCreate.entityBareFrom("conference@muc.example.com")
    private val participant = MockXmppEndpoint(JidCreate.from("$conferenceJid/endpoint_id"))
    private val jigasi1 = MockJigasi(JidCreate.from("jigasi1@example.com"))
    private val jigasi2 = MockJigasi(JidCreate.from("jigasi2@example.com"))
    private val jigasiDetector: JigasiDetector = mockk()

    private val conferenceStore = ListConferenceStore()
    private val jigasiIqHandler = JigasiIqHandler(setOf(jicofo.xmppConnection), conferenceStore, jigasiDetector)

    private val dialRequest = DialIq().apply {
        from = participant.jid
        to = jicofo.jid
        type = IQ.Type.set
    }

    override fun afterSpec(spec: Spec) = super.afterSpec(spec).also {
        jicofo.shutdown()
        participant.shutdown()
        jigasi1.shutdown()
        jigasi2.shutdown()
        jigasiIqHandler.shutdown()
    }

    init {
        // The XMPPConnection that jicofo uses to send requests to jigasi
        every { jigasiDetector.xmppConnection } returns jicofo.xmppConnection

        context("When the conference doesn't exist") {
            val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
            response.shouldBeError(Condition.item_not_found)
        }
        context("When the conference exists") {
            val conference: JitsiMeetConference = mockk()
            every { conference.roomName } returns conferenceJid
            every { conference.bridges } returns emptyMap()
            conferenceStore.apply { add(conference) }

            context("And rejects the request") {
                every { conference.acceptJigasiRequest(any()) } returns false

                val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
                response.shouldBeError(Condition.forbidden)
            }
            context("And accepts the request") {
                every { conference.acceptJigasiRequest(any()) } returns true

                context("And there are no Jigasis available") {
                    every { jigasiDetector.selectSipJigasi(any(), any()) } returns null

                    val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
                    response.shouldBeError(Condition.service_unavailable)
                }
                context("And the only jigasi instance fails") {
                    jigasi1.response = MockJigasi.Response.Failure
                    every { jigasiDetector.selectSipJigasi(any(), any()) } answers { jigasi1.jid } andThen null

                    val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
                    response.shouldBeError()
                }
                context("And the only jigasi instance times out") {
                    jigasi1.response = MockJigasi.Response.Timeout
                    every { jigasiDetector.selectSipJigasi(any(), any()) } answers { jigasi1.jid } andThen null

                    val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
                    response.shouldBeError()
                }
                context("And the only jigasi instance succeeds") {
                    jigasi1.response = MockJigasi.Response.Success
                    every { jigasiDetector.selectSipJigasi(any(), any()) } returns jigasi1.jid

                    val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
                    response.shouldBeSuccessful()
                }
                context("The first jigasi instance fails, but the second succeeds") {
                    jigasi1.response = MockJigasi.Response.Failure
                    jigasi2.response = MockJigasi.Response.Success
                    every { jigasiDetector.selectSipJigasi(any(), any()) } answers {
                        jigasi1.jid
                    } andThen {
                        jigasi2.jid
                    }

                    val response = participant.xmppConnection.sendIqAndGetResponse(dialRequest)
                    response.shouldBeSuccessful()
                }
            }
        }
    }
}

private fun IQ?.shouldBeError(condition: Condition? = null) {
    this.shouldBeInstanceOf<ErrorIQ>()
    this as ErrorIQ
    if (condition != null)
        error.condition shouldBe condition
}

private fun IQ?.shouldBeSuccessful() {
    this.shouldBeInstanceOf<EmptyResultIQ>()
}
