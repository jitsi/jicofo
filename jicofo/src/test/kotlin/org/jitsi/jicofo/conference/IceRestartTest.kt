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
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.mock.ConferenceHarness
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jitsimeet.BridgeSessionPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

/**
 * Tests the in-place ICE restart flow end to end within jicofo: a client `session-info` with
 * `<bridge-session ice-restart="true"/>` results in a colibri2 request to the bridge, and the bridge's rotated
 * transport is relayed back to the client in a Jingle `transport-info`.
 */
class IceRestartTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val harness = ConferenceHarness()
    private val xmppConnection = harness.xmppConnection

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    /** The colibri2 requests jicofo sent which ask for an ICE restart. */
    private fun iceRestartRequests() = xmppConnection.requests.filterIsInstance<ConferenceModifyIQ>()
        .flatMap { it.endpoints }.filter { it.transport?.iceRestart == true }

    /** The error condition of the response jicofo sent to a request, or null if it was accepted. */
    private fun errorConditionOf(request: IQ) = xmppConnection.requests
        .find { it.stanzaId == request.stanzaId && it.type == IQ.Type.error }?.error?.condition

    init {
        context("A conference with two participants") {
            // min-participants is 2, so nothing is invited before the second one joins.
            val member = harness.addParticipants(2).first()
            val participant = harness.getParticipant(member).shouldNotBeNull()
            val remoteParticipant = harness.getRemoteParticipant(member).shouldNotBeNull()
            val sessionInitiate = remoteParticipant.sessionInitiate
            val jingleSession = harness.jingleSessions.find { it.sid == sessionInitiate.sid }.shouldNotBeNull()
            val bridgeSessionId = sessionInitiate.getExtension(BridgeSessionPacketExtension::class.java)
                .shouldNotBeNull().id.shouldNotBeNull()

            /** The `transport-info`s jicofo sent to the client (the session-initiate does not use one). */
            fun transportInfosToClient() =
                remoteParticipant.requests.filter { it.action == JingleAction.TRANSPORT_INFO }

            fun createIceRestartRequest(bsId: String? = bridgeSessionId) =
                JingleIQ(JingleAction.SESSION_INFO, sessionInitiate.sid).apply {
                    from = sessionInitiate.to
                    to = sessionInitiate.from
                    type = IQ.Type.set
                    stanzaId = "ice-restart-request-$bsId"
                    addExtension(
                        BridgeSessionPacketExtension().apply {
                            id = bsId
                            setIceRestart(true)
                        }
                    )
                }

            context("A valid request") {
                val request = createIceRestartRequest()
                jingleSession.processIq(request)

                should("be accepted") {
                    errorConditionOf(request) shouldBe null
                }
                should("result in a colibri2 ICE restart request for the endpoint") {
                    iceRestartRequests().map { it.id } shouldBe listOf(participant.endpointId)
                }
                should("relay the bridge's rotated transport to the client in a transport-info") {
                    val transportInfos = transportInfosToClient()
                    transportInfos.size shouldBe 1
                    val transport = transportInfos.first().contentList
                        .firstNotNullOf { it.getFirstChildOfType(IceUdpTransportPacketExtension::class.java) }
                    // The mock bridge rotates its credentials and tags them with the generation.
                    transport.iceGeneration shouldBe 1
                    transport.ufrag shouldBe "ufrag-${harness.conference.meetingId}-${participant.endpointId}-1"
                }
                should("not terminate the Jingle session") {
                    participant.jingleSession shouldBe jingleSession
                    remoteParticipant.requests.none { it.action == JingleAction.SESSION_TERMINATE } shouldBe true
                }
            }

            context("A request with a stale bridge-session ID") {
                val request = createIceRestartRequest("not-the-current-bridge-session")
                jingleSession.processIq(request)

                should("be rejected with item-not-found") {
                    errorConditionOf(request) shouldBe StanzaError.Condition.item_not_found
                }
                should("not ask the bridge for anything") {
                    iceRestartRequests().size shouldBe 0
                }
                should("not signal anything to the client") {
                    transportInfosToClient().size shouldBe 0
                }
            }

            context("A request when the feature is disabled") {
                withNewConfig("jicofo.conference.enable-ice-restart=false") {
                    val request = createIceRestartRequest()
                    jingleSession.processIq(request)

                    should("be rejected with feature-not-implemented") {
                        errorConditionOf(request) shouldBe StanzaError.Condition.feature_not_implemented
                    }
                    should("not ask the bridge for anything") {
                        iceRestartRequests().size shouldBe 0
                    }
                }
            }

            context("Repeated requests") {
                // The configured limits allow 5 requests per minute, but never two within 5 seconds of each other.
                val first = createIceRestartRequest()
                jingleSession.processIq(first)
                val second = JingleIQ(JingleAction.SESSION_INFO, sessionInitiate.sid).apply {
                    from = sessionInitiate.to
                    to = sessionInitiate.from
                    type = IQ.Type.set
                    stanzaId = "second-ice-restart-request"
                    addExtension(
                        BridgeSessionPacketExtension().apply {
                            id = bridgeSessionId
                            setIceRestart(true)
                        }
                    )
                }
                jingleSession.processIq(second)

                should("rate-limit the second one") {
                    errorConditionOf(first) shouldBe null
                    errorConditionOf(second) shouldBe StanzaError.Condition.resource_constraint
                }
                should("only ask the bridge once") {
                    iceRestartRequests().size shouldBe 1
                }
            }

            context("A session-info without the ice-restart flag") {
                // The existing ice-state=failed path must be unaffected.
                val request = JingleIQ(JingleAction.SESSION_INFO, sessionInitiate.sid).apply {
                    from = sessionInitiate.to
                    to = sessionInitiate.from
                    type = IQ.Type.set
                    stanzaId = "plain-session-info"
                    addExtension(BridgeSessionPacketExtension().apply { id = bridgeSessionId })
                }
                jingleSession.processIq(request)

                should("not trigger an ICE restart") {
                    iceRestartRequests().size shouldBe 0
                    transportInfosToClient().size shouldBe 0
                }
            }
        }
    }
}
