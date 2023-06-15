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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.conference.inPlaceExecutor
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.util.ListConferenceStore
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.jicofo.xmpp.IqRequest
import org.jitsi.jicofo.xmpp.JigasiIqHandler
import org.jitsi.xmpp.extensions.rayo.DialIq
import org.jivesoftware.smack.packet.EmptyResultIQ
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaError.Condition
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class JigasiIqHandlerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance

    private val jicofo = JidCreate.from("jicofo@example.com")
    private val conferenceJid = JidCreate.entityBareFrom("conference@muc.example.com")
    private val participant = JidCreate.from("$conferenceJid/endpoint_id")
    private val jigasi1 = JidCreate.from("jigasi1@example.com")
    private val jigasi2 = JidCreate.from("jigasi2@example.com")
    private val jigasiXmppConnection = JigasiXmppConnection()
    private val jigasiDetector: JigasiDetector = mockk {
        every { xmppConnection } returns jigasiXmppConnection.xmppConnection
    }

    private val conferenceStore = ListConferenceStore()
    private val jigasiIqHandler = JigasiIqHandler(setOf(), conferenceStore, jigasiDetector)

    private val dialResponses = mutableListOf<Stanza>()
    private val dialRequest = IqRequest(
        DialIq().apply {
            from = participant
            to = jicofo
            type = IQ.Type.set
        },
        mockk {
            every { sendStanza(capture(dialResponses)) } returns Unit
        }
    )

    override suspend fun beforeSpec(spec: Spec) = super.beforeSpec(spec).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterSpec(spec: Spec) = super.afterSpec(spec).also {
        TaskPools.resetIoPool()
    }

    init {
        context("When the conference doesn't exist") {
            jigasiIqHandler.handleRequest(dialRequest).let {
                it.shouldBeInstanceOf<RejectedWithError>()
                it.response.shouldBeError(Condition.item_not_found)
            }
        }
        context("When the conference exists") {
            val conference: JitsiMeetConference = mockk(relaxed = true) {
                every { roomName } returns conferenceJid
            }
            conferenceStore.add(conference)

            context("And rejects the request") {
                every { conference.acceptJigasiRequest(any()) } returns false

                jigasiIqHandler.handleRequest(dialRequest).let {
                    it.shouldBeInstanceOf<RejectedWithError>()
                    it.response.shouldBeError(Condition.forbidden)
                }
            }
            context("And accepts the request") {
                every { conference.acceptJigasiRequest(any()) } returns true

                context("And there are no Jigasis available") {
                    every { jigasiDetector.selectSipJigasi(any(), any()) } returns null

                    jigasiIqHandler.handleRequest(dialRequest).shouldBeInstanceOf<AcceptedWithNoResponse>()
                    dialResponses.last().let {
                        it.shouldBeInstanceOf<IQ>()
                        it.shouldBeError(Condition.service_unavailable)
                    }
                }
                context("And the only jigasi instance fails") {
                    jigasiXmppConnection.responses[jigasi1] = JigasiXmppConnection.Response.Failure
                    every { jigasiDetector.selectSipJigasi(any(), any()) } answers { jigasi1 } andThen null

                    jigasiIqHandler.handleRequest(dialRequest).shouldBeInstanceOf<AcceptedWithNoResponse>()
                    dialResponses.last().let {
                        it.shouldBeInstanceOf<IQ>()
                        it.shouldBeError()
                    }
                }
                context("And the only jigasi instance times out") {
                    jigasiXmppConnection.responses[jigasi1] = JigasiXmppConnection.Response.Timeout
                    every { jigasiDetector.selectSipJigasi(any(), any()) } answers { jigasi1 } andThen null

                    jigasiIqHandler.handleRequest(dialRequest).shouldBeInstanceOf<AcceptedWithNoResponse>()
                    dialResponses.last().let {
                        it.shouldBeInstanceOf<IQ>()
                        it.shouldBeError()
                    }
                }
                context("And the only jigasi instance succeeds") {
                    jigasiXmppConnection.responses[jigasi1] = JigasiXmppConnection.Response.Success
                    every { jigasiDetector.selectSipJigasi(any(), any()) } returns jigasi1

                    jigasiIqHandler.handleRequest(dialRequest).shouldBeInstanceOf<AcceptedWithNoResponse>()
                    dialResponses.last().let {
                        it.shouldBeInstanceOf<IQ>()
                        it.shouldBeSuccessful()
                    }
                }
                context("The first jigasi instance fails, but the second succeeds") {
                    jigasiXmppConnection.responses[jigasi1] = JigasiXmppConnection.Response.Failure
                    jigasiXmppConnection.responses[jigasi2] = JigasiXmppConnection.Response.Success
                    every { jigasiDetector.selectSipJigasi(any(), any()) } answers {
                        jigasi1
                    } andThenAnswer {
                        jigasi2
                    }

                    jigasiIqHandler.handleRequest(dialRequest).shouldBeInstanceOf<AcceptedWithNoResponse>()
                    dialResponses.last().let {
                        it.shouldBeInstanceOf<IQ>()
                        it.shouldBeSuccessful()
                    }
                }
            }
        }
    }
}

private fun IQ?.shouldBeError(condition: Condition? = null) {
    this.shouldBeInstanceOf<ErrorIQ>()
    if (condition != null) {
        error.condition shouldBe condition
    }
}

private fun IQ?.shouldBeSuccessful() {
    this.shouldBeInstanceOf<EmptyResultIQ>()
}

class JigasiXmppConnection : MockXmppConnection() {
    val responses = mutableMapOf<Jid, Response>()
    override fun handleIq(iq: IQ): IQ? = when (iq) {
        is DialIq -> responses.computeIfAbsent(iq.to) { Response.Success }.let {
            when (it) {
                Response.Success -> IQ.createResultIQ(iq)
                Response.Failure -> IQ.createErrorResponse(iq, Condition.internal_server_error)
                Response.Timeout -> null
            }
        }
        else -> {
            println("Not handling ${iq.toXML()}")
            null
        }
    }

    enum class Response { Success, Failure, Timeout }
}
