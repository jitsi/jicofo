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
package org.jitsi.jicofo.conference.agent

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.colibri.AgentConnectRequest
import org.jitsi.jicofo.bridge.colibri.ColibriSessionManager
import org.jitsi.jicofo.bridge.colibri.ParticipantAllocationParameters
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.xmpp.RoomMetadata
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl

class ConferenceAgentManagerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    private val conferenceSources = ValidatingConferenceSourceMap(20, 20)
    private val colibriSessionManager = mockk<ColibriSessionManager>(relaxed = true)
    private val manager = ConferenceAgentManager(conferenceSources, LoggerImpl("test"))
    private val agent = RoomMetadata.Metadata.Agent(urlParams = mapOf("session" to "s1"))

    private val urlConfig = "jicofo.agent.url-template=\"wss://agents.example.com/{{MEETING_ID}}\""

    init {
        context("With no agent URL configured") {
            manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
            should("not touch colibri") {
                verify { colibriSessionManager wasNot Called }
            }
        }

        context("Adding an agent") {
            withNewConfig(urlConfig) {
                manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")

                should("allocate a synthetic endpoint with a synthetic audio source") {
                    val params = slot<ParticipantAllocationParameters>()
                    verify { colibriSessionManager.allocate(capture(params)) }
                    params.captured.id shouldBe "agent1"
                    params.captured.synthetic shouldBe true
                    params.captured.useSctp shouldBe false
                    val source = params.captured.sources.sources.single()
                    source.name shouldBe "agent1-a0"
                    source.synthetic shouldBe true
                    source.mediaType shouldBe MediaType.AUDIO
                    verify { colibriSessionManager.updateParticipant("agent1", any(), any(), any(), any()) }
                }

                should("set the agent connect once the endpoint is allocated") {
                    val connects = mutableListOf<List<AgentConnectRequest>>()
                    verify { colibriSessionManager.setAgents(capture(connects)) }
                    val last = connects.last()
                    last.size shouldBe 1
                    last.first().endpointId shouldBe "agent1"
                    last.first().syntheticSourceName shouldBe "agent1-a0"
                    last.first().urlParams shouldBe mapOf("session" to "s1")
                }
            }
        }

        context("Removing an agent") {
            withNewConfig(urlConfig) {
                manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                manager.setRequests(emptyMap(), colibriSessionManager, "meeting1")

                should("expire the endpoint and clear the connect") {
                    verify { colibriSessionManager.removeParticipant("agent1") }
                    val connects = mutableListOf<List<AgentConnectRequest>>()
                    verify { colibriSessionManager.setAgents(capture(connects)) }
                    connects.last() shouldBe emptyList()
                }
            }
        }

        context("An agent keeps its SSRC across updates") {
            withNewConfig(urlConfig) {
                manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                val params = slot<ParticipantAllocationParameters>()
                verify { colibriSessionManager.allocate(capture(params)) }
                val firstSsrc = params.captured.sources.sources.single().ssrc

                // A second update adding another agent must not re-allocate or re-mint for agent1.
                manager.setRequests(mapOf("agent1" to agent, "agent2" to agent), colibriSessionManager, "meeting1")

                should("only allocate the new agent") {
                    val allParams = mutableListOf<ParticipantAllocationParameters>()
                    verify { colibriSessionManager.allocate(capture(allParams)) }
                    allParams.map { it.id } shouldBe listOf("agent1", "agent2")
                    allParams.first { it.id == "agent1" }.sources.sources.single().ssrc shouldBe firstSsrc
                }
            }
        }
    }
}
