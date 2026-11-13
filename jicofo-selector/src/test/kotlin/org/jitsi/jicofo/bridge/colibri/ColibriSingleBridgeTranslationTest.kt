/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo.bridge.colibri

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.ParticipantProperties
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.PendingExecutor
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.TemplatedUrl
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests the moves of the translator connect in the single-bridge mode, in a conference with two bridges:
 * [ColibriSessionManager.translatorConnectMoves] and [ColibriSessionManager.Listener.translatorConnectMoved].
 */
class ColibriSingleBridgeTranslationTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    /** Requests jicofo sent, per bridge JID. */
    private val colibriRequests = mutableMapOf<Jid, MutableList<ConferenceModifyIQ>>()

    /** One colibri2 server per bridge JID, IQs are routed by their `to` address. */
    private val colibriServers = mutableMapOf<Jid, TestColibri2Server>()
    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? {
            if (iq is ConferenceModifyIQ) {
                colibriRequests.computeIfAbsent(iq.to) { mutableListOf() }.add(iq)
                return colibriServers.computeIfAbsent(iq.to) { TestColibri2Server() }.handleConferenceModifyIq(iq)
            }
            return null
        }
    }

    private fun createBridge(name: String) = mockk<Bridge>(relaxed = true) {
        every { jid } returns JidCreate.from("jvbbrewery@example.com/$name")
        every { relayId } returns name
        every { isOperational } returns true
        every { debugState } returns JsonNodeFactory.instance.objectNode()
        every { region } returns "region-$name"
    }

    private val bridge1 = createBridge("jvb1")
    private val bridge2 = createBridge("jvb2")

    private val bridgeSelector: BridgeSelector = mockk {
        every { selectBridge(any(), any(), any()) } answers {
            val region = secondArg<ParticipantProperties>().region
            listOf(bridge1, bridge2).find { it.region == region } ?: bridge1
        }
    }

    /** How many times [ColibriSessionManager.Listener.translatorConnectMoved] fired. */
    private var moveEvents = 0

    private val listener = object : ColibriSessionManager.Listener {
        override fun bridgeCountChanged(bridgeCount: Int) {}
        override fun bridgeRemoved(bridge: Bridge, participantIds: List<String>) {}
        override fun endpointRemoved(endpointId: String) {}
        override fun translatorConnectMoved() {
            moveEvents++
        }
    }

    /** Events are queued and run by [drain], as in production they run on another thread. */
    private val ioExecutor = PendingExecutor()

    // Initialized lazily so that construction happens after [beforeAny] has replaced the TaskPools executors.
    private val sessionManager by lazy {
        ColibriV2SessionManager(
            xmppConnection.xmppConnection,
            bridgeSelector,
            "test-conference",
            "test-meeting-id",
            false,
            null,
            createLogger()
        ).apply { addListener(listener) }
    }

    private fun drain() = ioExecutor.runAll()

    private fun allocate(id: String, region: String) = sessionManager.allocate(
        ParticipantAllocationParameters(
            id = id,
            statsId = null,
            region = region,
            sources = EndpointSourceSet.EMPTY,
            useSsrcRewriting = false,
            useRtpMidDemux = false,
            forceMuteAudio = false,
            forceMuteVideo = false,
            useSctp = false,
            visitor = false,
            supportsPrivateAddresses = false,
            medias = emptySet(),
            diarize = false
        )
    ).also { drain() }

    /** Check the count of moves and that an event fired for each move. */
    private fun shouldHaveMoved(times: Int) {
        sessionManager.translatorConnectMoves shouldBe times
        moveEvents shouldBe times
    }

    /** The ids of the connects that jicofo created on [bridge]. */
    private fun connectsCreatedOn(bridge: Bridge) = (colibriRequests[bridge.jid] ?: emptyList())
        .flatMap { it.connects?.getConnects() ?: emptyList() }
        .filter { it.create }
        .map { it.id }

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = ioExecutor.executor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    init {
        val translatorUrl = TemplatedUrl("wss://{{REGION}}.translate.example.com/t", requiredKeys = setOf("REGION"))
        // The sender on jvb2 is translated, so its synthetic source stays when jvb1 goes away.
        val requests = listOf(TranslationRequest("p2", "p2-a0", listOf("p2-a0.en")))
        val singleBridge = "jicofo.octo.enabled=true\njicofo.translation.mode=single-bridge"

        val transcriberUrl = TemplatedUrl("wss://transcribe.example.com/t")

        context("In the single-bridge mode") {
            withNewConfig(singleBridge) {
                context("with translation on after the participants joined") {
                    allocate("p1", "region-jvb1")
                    allocate("p2", "region-jvb2")
                    sessionManager.setTranslator(translatorUrl, requests)
                    drain()

                    should("host the connect on the first session, which is not a move") {
                        connectsCreatedOn(bridge1) shouldBe listOf("translator")
                        connectsCreatedOn(bridge2) shouldBe emptyList()
                        shouldHaveMoved(0)
                    }
                    context("when the bridge that hosts the connect goes away") {
                        sessionManager.removeBridge(bridge1)
                        drain()

                        should("move the connect to the other session and report it") {
                            connectsCreatedOn(bridge2) shouldBe listOf("translator")
                            shouldHaveMoved(1)
                        }
                        should("not report it again when the connects are signaled again") {
                            sessionManager.setTranslator(translatorUrl, requests)
                            drain()
                            shouldHaveMoved(1)
                        }
                    }
                    context("when the last participant leaves the bridge that hosts the connect") {
                        sessionManager.removeParticipant("p1")
                        drain()

                        should("move the connect to the other session and report it") {
                            connectsCreatedOn(bridge2) shouldBe listOf("translator")
                            shouldHaveMoved(1)
                        }
                    }
                    context("when the other bridge goes away") {
                        sessionManager.removeBridge(bridge2)
                        drain()

                        should("keep the connect where it is and report nothing") {
                            connectsCreatedOn(bridge1) shouldBe listOf("translator")
                            shouldHaveMoved(0)
                        }
                    }
                    context("when every bridge goes away and a participant comes back") {
                        sessionManager.removeBridge(bridge1)
                        sessionManager.removeBridge(bridge2)
                        drain()
                        // The connect moved to jvb2 when jvb1 went away.
                        val movesWithNoSession = sessionManager.translatorConnectMoves
                        allocate("p2", "region-jvb2")

                        should("report a move to the new session: receivers can stay through the time with none") {
                            movesWithNoSession shouldBe 1
                            shouldHaveMoved(2)
                        }
                    }
                    context("when translation stops and starts again on another session") {
                        sessionManager.setTranslator(null)
                        sessionManager.removeBridge(bridge1)
                        sessionManager.setTranslator(translatorUrl, requests)
                        drain()

                        should("host the connect on the other session, which is not a move") {
                            connectsCreatedOn(bridge2) shouldBe listOf("translator")
                            shouldHaveMoved(0)
                        }
                    }
                }
                context("with translation on before the first participant joins") {
                    sessionManager.setTranslator(translatorUrl, requests)
                    allocate("p1", "region-jvb1")
                    allocate("p2", "region-jvb2")

                    should("host the connect on the session of the first participant, which is not a move") {
                        connectsCreatedOn(bridge1) shouldBe listOf("translator")
                        shouldHaveMoved(0)
                    }
                    should("report a move when that bridge goes away") {
                        sessionManager.removeBridge(bridge1)
                        drain()
                        connectsCreatedOn(bridge2) shouldBe listOf("translator")
                        shouldHaveMoved(1)
                    }
                }
                context("with a transcriber on the same session") {
                    allocate("p1", "region-jvb1")
                    allocate("p2", "region-jvb2")
                    sessionManager.setTranscriberUrl(transcriberUrl)
                    drain()

                    should("report nothing when the transcriber alone moves") {
                        sessionManager.removeBridge(bridge1)
                        drain()
                        shouldHaveMoved(0)
                    }
                    should("report nothing when translation starts or the transcriber stops") {
                        sessionManager.setTranslator(translatorUrl, requests)
                        sessionManager.setTranscriberUrl(null)
                        drain()
                        shouldHaveMoved(0)
                    }
                    should("report a single move when both connects move") {
                        sessionManager.setTranslator(translatorUrl, requests)
                        sessionManager.removeBridge(bridge1)
                        drain()
                        shouldHaveMoved(1)
                    }
                }
            }
        }

        context("In the per-source mode") {
            withNewConfig("jicofo.octo.enabled=true") {
                allocate("p1", "region-jvb1")
                allocate("p2", "region-jvb2")
                sessionManager.setTranslator(translatorUrl, requests)
                drain()

                should("put the connect on the bridge of the sender") {
                    connectsCreatedOn(bridge2) shouldBe listOf("translator-p2-a0-0")
                    shouldHaveMoved(0)
                }
                should("report nothing when a bridge goes away") {
                    sessionManager.removeBridge(bridge1)
                    drain()
                    shouldHaveMoved(0)
                }
            }
        }
    }
}
