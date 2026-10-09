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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.ConferenceBridgeProperties
import org.jitsi.jicofo.bridge.colibri.AgentConnectRequest
import org.jitsi.jicofo.bridge.colibri.BridgeSelectionFailedException
import org.jitsi.jicofo.bridge.colibri.ColibriAllocationFailedException
import org.jitsi.jicofo.bridge.colibri.ColibriSessionManager
import org.jitsi.jicofo.bridge.colibri.ParticipantAllocationParameters
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
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
    private val colibriSessionManager = mockk<ColibriSessionManager>(relaxed = true).also {
        every { it.getBridges() } returns emptyMap()
        // Allocated endpoints stay on their session unless a test expires them.
        every { it.getBridgeSessionId(any()) } returns Pair(mockk<Bridge>(), "session1")
    }
    private val statusReporter = mockk<AgentStatusReporter>(relaxed = true)

    /** Endpoint id -> consented agent ids, as the conference would derive it from presence. */
    private var consent: Map<String, Set<String>> = emptyMap()
    private val manager = ConferenceAgentManager(
        conferenceSources,
        "room@muc.example.com",
        LoggerImpl("test"),
        consentingMembers = { consent },
        statusReporter = statusReporter
    )
    private val agent = RoomMetadata.Metadata.Agent()
    private val failedAgent = RoomMetadata.Metadata.Agent(state = "failed")
    private val endedAgent = RoomMetadata.Metadata.Agent(state = "ended")

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
                    last.first().urlParams shouldBe mapOf("conference" to "room@muc.example.com", "agentId" to "agent1")
                }
            }
        }

        context("Placement") {
            withNewConfig(urlConfig) {
                fun bridgeIn(r: String?) = mockk<Bridge> { every { region } returns r }
                every { colibriSessionManager.getBridges() } returns mapOf(
                    bridgeIn("eu-west") to ConferenceBridgeProperties(1),
                    bridgeIn("us-east") to ConferenceBridgeProperties(3),
                    bridgeIn("ap-south") to ConferenceBridgeProperties(5, visitor = true)
                )
                manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")

                should("allocate in the region of the bridge hosting most of the humans, ignoring visitor bridges") {
                    val params = slot<ParticipantAllocationParameters>()
                    verify { colibriSessionManager.allocate(capture(params)) }
                    params.captured.region shouldBe "us-east"
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

        context("Agent exports") {
            withNewConfig(urlConfig) {
                /** The exports of each agent in the last connect update, keyed by agent id. */
                fun lastExports(): Map<String, List<String>> {
                    val connects = mutableListOf<List<AgentConnectRequest>>()
                    verify { colibriSessionManager.setAgents(capture(connects)) }
                    return connects.last().associate { it.endpointId to it.exports }
                }

                context("when nobody consented") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    should("be empty") {
                        lastExports() shouldBe mapOf("agent1" to emptyList())
                    }
                }

                context("when a member with a known audio source consented") {
                    conferenceSources.tryToAdd("p1", EndpointSourceSet(Source(1234, MediaType.AUDIO, name = "p1-a0")))
                    consent = mapOf("p1" to setOf("agent1"))
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    should("export that source") {
                        lastExports() shouldBe mapOf("agent1" to listOf("p1-a0"))
                    }
                }

                context("when a consenting member has no known audio source yet") {
                    consent = mapOf("p2" to setOf("agent1"))
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    should("fall back to the conventional first audio source name") {
                        lastExports() shouldBe mapOf("agent1" to listOf("p2-a0"))
                    }
                }

                context("when consent changes") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    lastExports() shouldBe mapOf("agent1" to emptyList())

                    consent = mapOf("p1" to setOf("agent1"))
                    manager.reapply(colibriSessionManager, "meeting1")
                    should("add the exports on re-apply") {
                        lastExports() shouldBe mapOf("agent1" to listOf("p1-a0"))
                    }

                    consent = emptyMap()
                    manager.reapply(colibriSessionManager, "meeting1")
                    should("drop the exports once consent is withdrawn") {
                        lastExports() shouldBe mapOf("agent1" to emptyList())
                    }
                }

                context("when a member consented to another agent") {
                    consent = mapOf("p1" to setOf("agent2"))
                    manager.setRequests(mapOf("agent1" to agent, "agent2" to agent), colibriSessionManager, "meeting1")
                    should("only export to that agent") {
                        lastExports() shouldBe mapOf("agent1" to emptyList(), "agent2" to listOf("p1-a0"))
                    }
                }
            }
        }

        context("When the bridge loses the agent's endpoint") {
            withNewConfig(urlConfig) {
                context("after it was allocated") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    manager.endpointsRemoved(listOf("agent1", "p1"), colibriSessionManager, "meeting1")

                    should("re-allocate it with the same SSRC and report connecting again") {
                        val allParams = mutableListOf<ParticipantAllocationParameters>()
                        verify(exactly = 2) { colibriSessionManager.allocate(capture(allParams)) }
                        allParams.map { it.id } shouldBe listOf("agent1", "agent1")
                        allParams[0].sources.sources.single().ssrc shouldBe allParams[1].sources.sources.single().ssrc
                        verify(exactly = 2) { statusReporter.report("agent1", AgentStatusReporter.CONNECTING) }
                    }
                    should("only know its own endpoints") {
                        manager.manages("agent1") shouldBe true
                        manager.manages("p1") shouldBe false
                    }
                }

                context("before anything was requested") {
                    manager.endpointsRemoved(listOf("agent1"), colibriSessionManager, "meeting1")
                    should("not touch colibri") {
                        verify { colibriSessionManager wasNot Called }
                    }
                }

                context("after its allocation failed") {
                    every { colibriSessionManager.allocate(any()) } throws BridgeSelectionFailedException()
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    manager.endpointsRemoved(listOf("agent1"), colibriSessionManager, "meeting1")
                    should("stay failed") {
                        verify(exactly = 1) { colibriSessionManager.allocate(any()) }
                    }
                }
            }
        }

        context("When every bridge session expired") {
            withNewConfig(urlConfig) {
                manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                // The last human left: colibri expired the sessions, and the agent's endpoint with them.
                every { colibriSessionManager.getBridgeSessionId("agent1") } returns Pair(null, null)
                manager.reapply(colibriSessionManager, "meeting1")

                should("re-allocate the agent with the same SSRC on the next apply") {
                    val allParams = mutableListOf<ParticipantAllocationParameters>()
                    verify(exactly = 2) { colibriSessionManager.allocate(capture(allParams)) }
                    allParams.map { it.id } shouldBe listOf("agent1", "agent1")
                    allParams[0].sources.sources.single().ssrc shouldBe allParams[1].sources.sources.single().ssrc
                }
            }
        }

        context("An agent the provisioning API marked as over") {
            withNewConfig(urlConfig) {
                context("when it is failed from the start") {
                    manager.setRequests(mapOf("agent1" to failedAgent), colibriSessionManager, "meeting1")
                    should("not be allocated") {
                        verify(exactly = 0) { colibriSessionManager.allocate(any()) }
                        verify(exactly = 0) { statusReporter.report("agent1", any(), any()) }
                    }
                }

                context("when it fails after being allocated") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    manager.setRequests(mapOf("agent1" to failedAgent), colibriSessionManager, "meeting1")
                    should("expire its endpoint and drop its connect, so the bridge stops redialing") {
                        verify { colibriSessionManager.removeParticipant("agent1") }
                        val connects = mutableListOf<List<AgentConnectRequest>>()
                        verify { colibriSessionManager.setAgents(capture(connects)) }
                        connects.last() shouldBe emptyList()
                    }
                    should("not be re-allocated while it stays failed") {
                        manager.reapply(colibriSessionManager, "meeting1")
                        verify(exactly = 1) { colibriSessionManager.allocate(any()) }
                    }
                }

                context("when it ended") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    manager.setRequests(mapOf("agent1" to endedAgent), colibriSessionManager, "meeting1")
                    should("expire its endpoint") {
                        verify { colibriSessionManager.removeParticipant("agent1") }
                    }
                }
            }
        }

        context("Lifecycle reporting") {
            withNewConfig(urlConfig) {
                context("when an agent is allocated") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    should("report connecting, and leave active to the media relay") {
                        verify { statusReporter.report("agent1", AgentStatusReporter.CONNECTING) }
                        verify(exactly = 0) { statusReporter.report("agent1", AgentStatusReporter.ACTIVE, any()) }
                    }
                }

                context("when allocation fails") {
                    every { colibriSessionManager.allocate(any()) } throws BridgeSelectionFailedException()
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    should("report failed with the reason") {
                        verify { statusReporter.report("agent1", AgentStatusReporter.FAILED, "no bridge available") }
                    }
                    should("not retry on re-apply, since the status rebroadcast would otherwise loop") {
                        manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                        manager.reapply(colibriSessionManager, "meeting1")
                        verify(exactly = 1) { colibriSessionManager.allocate(any()) }
                        verify(exactly = 1) { statusReporter.report("agent1", AgentStatusReporter.FAILED, any()) }
                    }
                    should("retry once the provisioning API removes and re-adds the agent") {
                        every { colibriSessionManager.allocate(any()) } returns mockk(relaxed = true)
                        manager.setRequests(emptyMap(), colibriSessionManager, "meeting1")
                        manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                        verify(exactly = 2) { colibriSessionManager.allocate(any()) }
                        verify(exactly = 2) { statusReporter.report("agent1", AgentStatusReporter.CONNECTING) }
                    }
                }

                context("when the bridge rejects the allocation") {
                    every { colibriSessionManager.allocate(any()) } throws ColibriAllocationFailedException(
                        "Bad request: <error xmlns='jabber:client' type='modify'><bad-request/></error>",
                        false
                    )
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    should("report a short reason without the error stanza") {
                        val reason = slot<String>()
                        verify { statusReporter.report("agent1", AgentStatusReporter.FAILED, capture(reason)) }
                        reason.captured shouldBe "bridge allocation failed: Bad request"
                    }
                }

                context("when the provisioning API removes an agent") {
                    manager.setRequests(mapOf("agent1" to agent), colibriSessionManager, "meeting1")
                    manager.setRequests(emptyMap(), colibriSessionManager, "meeting1")
                    should("not report anything further") {
                        verify(exactly = 0) { statusReporter.report("agent1", "ended", any()) }
                    }
                }
            }
        }
    }
}
