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
package org.jitsi.jicofo.conference

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.mock.ConferenceHarness
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ

/**
 * Tests the synthetic translation sources of a sender that the conference re-invites, in the per-source mode: the
 * bridge that injects the sources can change, so the new allocation of the sender must carry new SSRCs.
 */
class TranslationReinviteTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val harness = ConferenceHarness()

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    /** The synthetic source of [endpointId] in the conference, or null if it has none. */
    private fun syntheticSource(endpointId: String): Source? =
        harness.conference.sources[endpointId]?.sources?.singleOrNull { it.synthetic }

    /** The SSRCs in each colibri2 request that created the endpoint [endpointId], in the order of the requests. */
    private fun ssrcsOfEndpointCreates(endpointId: String): List<Set<Long>> =
        harness.xmppConnection.requests.filterIsInstance<ConferenceModifyIQ>()
            .flatMap { it.endpoints }
            .filter { it.id == endpointId && it.create }
            .map { endpoint ->
                endpoint.sources?.mediaSources.orEmpty().flatMap { it.sources }.map { it.ssrc }.toSet()
            }

    private fun requestTranslation(endpointId: String) = harness.chatRoom.chatRoomListeners.forEach {
        it.audioTranslationRequestsChanged(mapOf(endpointId to listOf("en")))
    }

    init {
        val translation = "jicofo.translation.url-template=\"wss://{{REGION}}.example.com/{{MEETING_ID}}\""

        context("A translated sender that is re-invited with ReplaceTransport") {
            withNewConfig("$translation\njicofo.conference.reinvite-method=ReplaceTransport") {
                // min-participants is 2, so nothing is invited until the second participant joins.
                val member = harness.addParticipants(2).first()
                val endpointId = harness.getParticipant(member).shouldNotBeNull().endpointId
                requestTranslation(endpointId)
                val first = syntheticSource(endpointId).shouldNotBeNull()

                harness.conference.moveEndpoint(endpointId, null) shouldBe true
                val second = syntheticSource(endpointId).shouldNotBeNull()

                should("keep its synthetic source under a new SSRC") {
                    second.ssrc shouldNotBe first.ssrc
                    second.copy(ssrc = first.ssrc) shouldBe first
                }
                should("be allocated again with the new SSRC, and not with the old SSRC") {
                    val creates = ssrcsOfEndpointCreates(endpointId)
                    creates.size shouldBe 2
                    creates.last().contains(second.ssrc) shouldBe true
                    creates.last().contains(first.ssrc) shouldBe false
                }
            }
        }

        context("A translated sender that is re-invited with RestartJingle") {
            withNewConfig(translation) {
                val member = harness.addParticipants(2).first()
                val endpointId = harness.getParticipant(member).shouldNotBeNull().endpointId
                requestTranslation(endpointId)
                val first = syntheticSource(endpointId).shouldNotBeNull()

                harness.conference.moveEndpoint(endpointId, null) shouldBe true

                should("lose its synthetic source with its other sources") {
                    syntheticSource(endpointId) shouldBe null
                }
                should("get a synthetic source with a new SSRC when it accepts the new session") {
                    val remoteParticipant = harness.getRemoteParticipant(member).shouldNotBeNull()
                    val jingleSession = harness.jingleSessions.find { it.sid == remoteParticipant.sessionInitiate.sid }
                        .shouldNotBeNull()
                    jingleSession.processIq(remoteParticipant.createSessionAccept())

                    val second = syntheticSource(endpointId).shouldNotBeNull()
                    second.ssrc shouldNotBe first.ssrc
                    second.name shouldBe first.name
                }
            }
        }
    }
}
