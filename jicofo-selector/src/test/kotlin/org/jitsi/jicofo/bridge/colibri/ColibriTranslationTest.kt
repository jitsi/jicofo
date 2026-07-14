/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc
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
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.TemplatedUrl
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Connect
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests the colibri2 signaling for live translation in the default per-source mode: each sender's connect is placed on
 * its (single, here) bridge, signaled as create/update/expire deltas, keyed by a per-source connect id.
 */
class ColibriTranslationTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val colibriRequests = mutableListOf<ConferenceModifyIQ>()
    private val colibri2Server = TestColibri2Server()
    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? {
            if (iq is ConferenceModifyIQ) {
                colibriRequests.add(iq)
                return colibri2Server.handleConferenceModifyIq(iq)
            }
            return null
        }
    }

    private val bridge: Bridge = mockk(relaxed = true) {
        every { jid } returns JidCreate.from("jvb@example.com/jvb1")
        every { relayId } returns null
        every { isOperational } returns true
        every { debugState } returns JsonNodeFactory.instance.objectNode()
        every { region } returns "us-east"
    }

    private val bridgeSelector: BridgeSelector = mockk {
        every { selectBridge(any(), any(), any()) } returns bridge
    }

    private fun createSessionManager() = ColibriV2SessionManager(
        xmppConnection.xmppConnection,
        bridgeSelector,
        "test-conference",
        "test-meeting-id",
        false,
        null,
        createLogger()
    )

    private fun allocateParticipant(manager: ColibriV2SessionManager, id: String) = manager.allocate(
        ParticipantAllocationParameters(
            id = id,
            statsId = null,
            region = null,
            sources = EndpointSourceSet.EMPTY,
            useSsrcRewriting = false,
            useRtpMidDemux = false,
            forceMuteAudio = false,
            forceMuteVideo = false,
            useSctp = false,
            visitor = false,
            supportsPrivateAddresses = false,
            diarize = false,
            medias = emptySet()
        )
    )

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    init {
        val translatorUrl = TemplatedUrl(
            "wss://{{REGION}}.translate.example.com/t",
            requiredKeys = setOf("REGION")
        )
        val expectedUrl = "wss://us-east.translate.example.com/t"

        fun lastConnect() = colibriRequests.lastOrNull { it.connects != null }?.connects?.getConnects()?.firstOrNull()

        context("Enabling per-source translation for a sender") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            colibriRequests.clear()
            manager.setTranslator(
                translatorUrl,
                listOf(TranslationRequest("p1", "p1-a0", listOf("p1-a0.en", "p1-a0.es")))
            )
            val connect = lastConnect()

            should("send a per-source translator connect with create") {
                connect shouldNotBe null
                connect!!.id shouldBe "translator-p1-a0-0"
                connect.type shouldBe Connect.Types.TRANSLATOR
                connect.create shouldBe true
                connect.expire shouldBe false
            }
            should("export the sender's source and request its synthetic outputs") {
                connect!!.getExports() shouldBe listOf("p1-a0")
                connect.getRequests() shouldBe listOf("p1-a0.en", "p1-a0.es")
            }
            should("resolve the url for the bridge region") {
                connect!!.url.toString() shouldBe expectedUrl
            }
        }

        context("Updating the requested languages") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTranslator(translatorUrl, listOf(TranslationRequest("p1", "p1-a0", listOf("p1-a0.en"))))
            colibriRequests.clear()
            manager.setTranslator(
                translatorUrl,
                listOf(TranslationRequest("p1", "p1-a0", listOf("p1-a0.en", "p1-a0.es")))
            )
            val connect = lastConnect()

            should("re-signal the same connect as an update (not create)") {
                connect shouldNotBe null
                connect!!.id shouldBe "translator-p1-a0-0"
                connect.create shouldBe false
                connect.expire shouldBe false
                connect.getRequests() shouldBe listOf("p1-a0.en", "p1-a0.es")
            }
        }

        context("Disabling translation") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTranslator(translatorUrl, listOf(TranslationRequest("p1", "p1-a0", listOf("p1-a0.en"))))
            colibriRequests.clear()
            manager.setTranslator(null, emptyList())
            val connect = lastConnect()

            should("expire the connect") {
                connect shouldNotBe null
                connect!!.id shouldBe "translator-p1-a0-0"
                connect.expire shouldBe true
            }
        }

        context("With per-room translator headers") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            colibriRequests.clear()
            manager.setTranslator(
                translatorUrl,
                listOf(TranslationRequest("p1", "p1-a0", listOf("p1-a0.en"))),
                customHeaders = mapOf("Authorization" to "Bearer per-room", "X-Custom" to "v1")
            )
            val connect = lastConnect()

            should("put the per-room headers on the connect") {
                connect shouldNotBe null
                connect!!.getHttpHeaders().associate { it.name to it.value } shouldBe mapOf(
                    "Authorization" to "Bearer per-room",
                    "X-Custom" to "v1"
                )
            }
        }

        context("Without per-room translator headers") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            colibriRequests.clear()
            // No customHeaders: fall back to the (empty, in this test) static config headers.
            manager.setTranslator(translatorUrl, listOf(TranslationRequest("p1", "p1-a0", listOf("p1-a0.en"))))
            val connect = lastConnect()

            should("fall back to the config headers (none configured here)") {
                connect shouldNotBe null
                connect!!.getHttpHeaders().associate { it.name to it.value } shouldBe emptyMap()
            }
        }
    }
}
