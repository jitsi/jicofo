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
 * Tests the colibri2 signaling of the text-translation target languages: they ride on the transcriber connect's
 * `requests` list as bare language codes, so changing them updates the existing connect rather than reconnecting it.
 */
class ColibriTextTranslationTest : ShouldSpec() {
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
        val transcriberUrl = TemplatedUrl(
            "wss://{{REGION}}.transcribe.example.com/transcribe",
            requiredKeys = setOf("REGION")
        )

        fun lastTranscriberConnect(): Connect? = colibriRequests
            .lastOrNull { iq -> iq.connects?.getConnects()?.any { it.type == Connect.Types.TRANSCRIBER } == true }
            ?.connects?.getConnects()?.first { it.type == Connect.Types.TRANSCRIBER }

        context("Requesting languages after the transcriber is connected") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTranscriberUrl(transcriberUrl)
            colibriRequests.clear()
            manager.setTextTranslationLanguages(setOf("fr", "de"))
            val connect = lastTranscriberConnect()

            should("update the existing transcriber connect rather than create a new one") {
                connect shouldNotBe null
                connect!!.id shouldBe "transcriber"
                connect.create shouldBe false
                connect.expire shouldBe false
            }
            should("carry the languages as the connect requests") {
                connect!!.getRequests() shouldBe listOf("de", "fr")
            }
            should("not change the url, so the bridge keeps the same websocket") {
                connect!!.url.toString() shouldBe "wss://us-east.transcribe.example.com/transcribe"
            }
        }

        context("Requesting languages before the transcriber is connected") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTextTranslationLanguages(setOf("fr"))
            colibriRequests.clear()
            manager.setTranscriberUrl(transcriberUrl)

            should("apply them to the transcriber connect when it is created") {
                val connect = lastTranscriberConnect()
                connect shouldNotBe null
                connect!!.create shouldBe true
                connect.getRequests() shouldBe listOf("fr")
            }
        }

        context("Changing the requested languages") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTranscriberUrl(transcriberUrl)
            manager.setTextTranslationLanguages(setOf("fr"))
            colibriRequests.clear()
            manager.setTextTranslationLanguages(setOf("fr", "es"))

            should("re-signal the connect with the new set") {
                lastTranscriberConnect()!!.getRequests() shouldBe listOf("es", "fr")
            }
        }

        context("Requesting the same languages again") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTranscriberUrl(transcriberUrl)
            manager.setTextTranslationLanguages(setOf("fr", "de"))
            colibriRequests.clear()

            should("not signal anything when the set is unchanged") {
                manager.setTextTranslationLanguages(setOf("fr", "de"))
                lastTranscriberConnect() shouldBe null
            }
            should("not signal anything when only the order differs") {
                manager.setTextTranslationLanguages(linkedSetOf("de", "fr"))
                lastTranscriberConnect() shouldBe null
            }
        }

        context("Giving up the last language") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            manager.setTranscriberUrl(transcriberUrl)
            manager.setTextTranslationLanguages(setOf("fr"))
            colibriRequests.clear()
            manager.setTextTranslationLanguages(emptySet())

            should("signal an empty requests list, which stops translation") {
                val connect = lastTranscriberConnect()
                connect shouldNotBe null
                connect!!.expire shouldBe false
                connect.getRequests() shouldBe emptyList()
            }
        }

        context("Requesting languages with no transcriber at all") {
            val manager = createSessionManager()
            allocateParticipant(manager, "p1")
            colibriRequests.clear()
            manager.setTextTranslationLanguages(setOf("fr"))

            should("not signal a transcriber connect") {
                lastTranscriberConnect() shouldBe null
            }
        }
    }
}
