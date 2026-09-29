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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.jitsi.jicofo.AgentConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Reports agent lifecycle transitions to the voice-agent provisioning API (`POST /voice-agent/status`), which
 * drives the agent's client-facing state and the customer's lifecycle webhooks. Disabled unless
 * `jicofo.agent.status.url` is configured.
 *
 * What jicofo can observe: [CONNECTING] once the synthetic endpoint allocation is submitted, [ACTIVE] once the
 * bridge has accepted the endpoint and the `<connect>` is dispatched (the bridge's dial to the agent follows
 * immediately; jicofo has no signal for the media leg itself), and [FAILED] when the allocation throws. Teardown
 * initiated by the provisioning API is not reported back — it already knows.
 *
 * Delivery is best-effort and never on the caller's thread: bounded retries with exponential backoff. A 404 means
 * the API has already removed the agent and is not retried.
 */
class AgentStatusReporter @JvmOverloads constructor(
    private val conferenceJid: String,
    parentLogger: Logger,
    /** Sends one report and returns the HTTP status; throws on I/O failure. Injectable for tests. */
    private val send: (url: String, token: String?, body: String) -> Int =
        { url, token, body -> sendWithSharedClient(url, token, body) },
    /** Delay before retry number [attempt]. Injectable for tests. */
    private val retryDelay: (attempt: Int) -> Duration = { Duration.ofSeconds(1L shl it) }
) {
    private val logger = createChildLogger(parentLogger)

    fun report(agentId: String, state: String, reason: String? = null) {
        val config = AgentConfig.config
        val url = config.statusUrl ?: return
        val body = JsonNodeFactory.instance.objectNode().apply {
            put("conference", conferenceJid)
            put("agentId", agentId)
            put("state", state)
            reason?.let { put("reason", it) }
        }.toString()
        TaskPools.ioPool.submit { attempt(url, config.statusToken, body, agentId, state, 1, config.statusRetries) }
    }

    private fun attempt(
        url: String,
        token: String?,
        body: String,
        agentId: String,
        state: String,
        attempt: Int,
        retries: Int
    ) {
        val code = try {
            send(url, token, body)
        } catch (e: Exception) {
            logger.warn("Status report for agent $agentId ($state) failed: ${e.message}")
            -1
        }
        when {
            code in 200..299 -> return

            // The provisioning API already removed the agent; there is nothing left to report.
            code == 404 -> logger.info("Status report for agent $agentId ($state): agent no longer exists")

            attempt <= retries -> TaskPools.scheduledPool.schedule(
                { TaskPools.ioPool.submit { attempt(url, token, body, agentId, state, attempt + 1, retries) } },
                retryDelay(attempt).toMillis(),
                TimeUnit.MILLISECONDS
            )

            else -> logger.warn(
                "Status report for agent $agentId ($state) gave up after $attempt attempt(s), last status $code"
            )
        }
    }

    companion object {
        const val CONNECTING = "connecting"
        const val ACTIVE = "active"
        const val FAILED = "failed"

        private val sharedClient: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        }

        private fun sendWithSharedClient(url: String, token: String?, body: String): Int {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(AgentConfig.config.statusTimeout)
                .header("Content-Type", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            return sharedClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
    }
}
