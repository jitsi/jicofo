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
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests the colibri2 signaling for voice-agent connects: placed on the session hosting the agent's synthetic endpoint,
 * exporting only the consented sources, and re-signaled as an update when the exports change.
 */
class ColibriAgentConnectTest : ShouldSpec() {
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

    /** The mock bridge requires a transport, so the agent endpoint is allocated as a regular one here. */
    private fun allocate(manager: ColibriV2SessionManager, id: String) = manager.allocate(
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
        val agentUrl = TemplatedUrl("wss://{{REGION}}.agents.example.com/m1", requiredKeys = setOf("REGION"))
        fun request(exports: List<String>) = AgentConnectRequest("agent1", "agent1-a0", exports, agentUrl)
        fun lastConnect() = colibriRequests.lastOrNull { it.connects != null }?.connects?.getConnects()?.firstOrNull()

        context("Setting an agent connect") {
            val manager = createSessionManager()
            allocate(manager, "p1")
            allocate(manager, "agent1")
            colibriRequests.clear()
            manager.setAgents(listOf(request(listOf("p1-a0"))))
            val connect = lastConnect()

            should("create it, exporting only the consented sources") {
                connect shouldNotBe null
                connect!!.id shouldBe "agent-agent1"
                connect.create shouldBe true
                connect.expire shouldBe false
                connect.getExports() shouldBe listOf("p1-a0")
                connect.getRequests() shouldBe listOf("agent1-a0")
                connect.url.toString() shouldBe "wss://us-east.agents.example.com/m1"
            }
        }

        context("Changing the exports") {
            val manager = createSessionManager()
            allocate(manager, "p1")
            allocate(manager, "agent1")
            manager.setAgents(listOf(request(listOf("p1-a0"))))
            colibriRequests.clear()
            manager.setAgents(listOf(request(listOf("p1-a0", "p2-a0"))))
            val connect = lastConnect()

            should("re-signal the connect as an update (not create)") {
                connect shouldNotBe null
                connect!!.id shouldBe "agent-agent1"
                connect.create shouldBe false
                connect.expire shouldBe false
                connect.getExports().toSet() shouldBe setOf("p1-a0", "p2-a0")
            }
        }

        context("Re-applying the same exports in a different order") {
            val manager = createSessionManager()
            allocate(manager, "agent1")
            manager.setAgents(listOf(request(listOf("p1-a0", "p2-a0"))))
            colibriRequests.clear()
            manager.setAgents(listOf(request(listOf("p2-a0", "p1-a0"))))

            should("not signal anything") {
                colibriRequests.none { it.connects != null } shouldBe true
            }
        }
    }
}
