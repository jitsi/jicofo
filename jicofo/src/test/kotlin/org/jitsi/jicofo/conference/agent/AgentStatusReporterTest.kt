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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.utils.logging2.LoggerImpl
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AgentStatusReporterTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    /** Runs scheduled retries inline so the tests are deterministic. */
    private val inlineScheduler = object : ScheduledThreadPoolExecutor(1) {
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            command.run()
            return super.schedule({}, 0, unit)
        }
    }

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inlineScheduler
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
        inlineScheduler.shutdownNow()
    }

    private data class Sent(val url: String, val token: String?, val body: String)

    private val sent = mutableListOf<Sent>()

    /** Builds a reporter whose sender replays [codes] in order; a negative code throws instead. */
    private fun reporter(vararg codes: Int): AgentStatusReporter {
        val responses = ArrayDeque(codes.toList())
        return AgentStatusReporter(
            "room@muc.example.com",
            LoggerImpl("test"),
            send = { url, token, body ->
                sent.add(Sent(url, token, body))
                responses.removeFirst().also { if (it < 0) throw RuntimeException("connection refused") }
            },
            retryDelay = { Duration.ZERO }
        )
    }

    private val statusConfig = """
        jicofo.agent.status.url = "http://prosody:5280/voice-agent/status"
        jicofo.agent.status.token = "jicofo-secret"
        jicofo.agent.status.retries = 1
    """.trimIndent()

    init {
        context("With no status URL configured") {
            reporter(200).report("agent1", AgentStatusReporter.ACTIVE)
            should("send nothing") {
                sent.size shouldBe 0
            }
        }

        context("Reporting a transition") {
            withNewConfig(statusConfig) {
                reporter(200).report("agent1", AgentStatusReporter.FAILED, "no bridge")
                should("POST the contract body to the configured URL with the bearer token") {
                    sent.size shouldBe 1
                    sent.single().url shouldBe "http://prosody:5280/voice-agent/status"
                    sent.single().token shouldBe "jicofo-secret"
                    val body = jacksonObjectMapper().readTree(sent.single().body)
                    body["conference"].asText() shouldBe "room@muc.example.com"
                    body["agentId"].asText() shouldBe "agent1"
                    body["state"].asText() shouldBe "failed"
                    body["reason"].asText() shouldBe "no bridge"
                }
            }
        }

        context("Reporting without a reason") {
            withNewConfig(statusConfig) {
                reporter(200).report("agent1", AgentStatusReporter.ACTIVE)
                should("omit the reason field") {
                    jacksonObjectMapper().readTree(sent.single().body).has("reason") shouldBe false
                }
            }
        }

        context("When the agent no longer exists (404)") {
            withNewConfig(statusConfig) {
                reporter(404).report("agent1", AgentStatusReporter.ACTIVE)
                should("not retry") {
                    sent.size shouldBe 1
                }
            }
        }

        context("When delivery fails") {
            withNewConfig(statusConfig) {
                context("and then succeeds") {
                    reporter(500, 200).report("agent1", AgentStatusReporter.ACTIVE)
                    should("retry once") {
                        sent.size shouldBe 2
                    }
                }

                context("with an exception") {
                    reporter(-1, 200).report("agent1", AgentStatusReporter.ACTIVE)
                    should("retry once") {
                        sent.size shouldBe 2
                    }
                }

                context("beyond the retry budget") {
                    reporter(500, 500, 500).report("agent1", AgentStatusReporter.ACTIVE)
                    should("give up after the configured retries") {
                        sent.size shouldBe 2
                    }
                }
            }
        }
    }
}
