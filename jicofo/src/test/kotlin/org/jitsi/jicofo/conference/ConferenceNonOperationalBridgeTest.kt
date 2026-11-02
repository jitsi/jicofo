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
package org.jitsi.jicofo.conference

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.setStats
import org.jitsi.jicofo.mock.ConferenceHarness
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests a conference (with Octo disabled) whose bridge is not operational, e.g. because its health check timed out,
 * while another bridge is available.
 */
class ConferenceNonOperationalBridgeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val bridgeSelector = BridgeSelector()

    // The lower stress makes jvb1 the initial selection.
    private val jvb1 = bridgeSelector.addJvbAddress(JidCreate.from("jvbbrewery@example.com/jvb1")).apply {
        setStats(stress = 0.1, relayId = "jvb1")
    }
    private val jvb2 = bridgeSelector.addJvbAddress(JidCreate.from("jvbbrewery@example.com/jvb2")).apply {
        setStats(stress = 0.5, relayId = "jvb2")
    }

    private val harness = ConferenceHarness(bridgeSelector = bridgeSelector)
    private val xmppConnection = harness.xmppConnection
    private val conference = harness.conference

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    /** The IDs of the endpoints created on [bridge], in order. */
    private fun endpointsCreatedOn(bridge: Bridge) = xmppConnection.requests.filterIsInstance<ConferenceModifyIQ>()
        .filter { it.to == bridge.jid }.flatMap { it.endpoints }.filter { it.create }.map { it.id }

    private fun ChatRoomMember.endpointId() = harness.getParticipant(this)!!.endpointId

    private fun ChatRoomMember.sessionInitiates() = harness.getRemoteParticipant(this)?.requests
        ?.count { it.action == JingleAction.SESSION_INITIATE } ?: 0

    init {
        context("A conference on a bridge which is not operational") {
            val members = harness.addParticipants(2)
            endpointsCreatedOn(jvb1) shouldBe members.map { it.endpointId() }

            // E.g. its health check timed out.
            jvb1.isOperational = false

            context("When the bridge responds") {
                val member3 = harness.chatRoom.addMember("member3")

                should("invite the new participant on the same bridge") {
                    conference.participantCount shouldBe 3
                    endpointsCreatedOn(jvb1) shouldBe (members + member3).map { it.endpointId() }
                    member3.sessionInitiates() shouldBe 1
                }
                should("not move the conference") {
                    endpointsCreatedOn(jvb2).shouldBeEmpty()
                    members.forEach { it.sessionInitiates() shouldBe 1 }
                }
            }
            context("When the bridge does not respond") {
                xmppConnection.unresponsiveBridges.add(jvb1.jid)
                val member3 = harness.chatRoom.addMember("member3")

                should("move the conference, including the new participant, to another bridge") {
                    conference.participantCount shouldBe 3
                    endpointsCreatedOn(jvb2).toSet() shouldBe (members + member3).map { it.endpointId() }.toSet()
                    conference.bridges.keys shouldBe setOf(jvb2)
                    conference.bridges[jvb2]!!.participantCount shouldBe 3
                }
                should("re-invite the existing participants") {
                    members.forEach { it.sessionInitiates() shouldBe 2 }
                }
                should("invite the new participant once") {
                    member3.sessionInitiates() shouldBe 1
                }
            }
        }
    }
}
