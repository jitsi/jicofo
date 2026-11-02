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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.setStats
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.PendingExecutor
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.time.FakeClock
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests [ColibriV2SessionManager] with a real [BridgeSelector] when the bridge of a conference is not operational
 * (e.g. its health check timed out) and the conference can not use a second bridge.
 */
class ColibriV2SessionManagerNonOperationalBridgeTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    /** Requests jicofo sent, per bridge JID. */
    private val colibriRequests = mutableMapOf<Jid, MutableList<ConferenceModifyIQ>>()

    /** One colibri2 server per bridge JID, IQs are routed by their `to` address. */
    private val colibriServers = mutableMapOf<Jid, TestColibri2Server>()

    /** Bridges which do not respond to colibri2 requests, i.e. the requests time out. */
    private val unresponsiveBridges = mutableSetOf<Jid>()

    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? {
            if (iq is ConferenceModifyIQ) {
                colibriRequests.computeIfAbsent(iq.to) { mutableListOf() }.add(iq)
                if (iq.to in unresponsiveBridges) {
                    return null
                }
                return colibriServers.computeIfAbsent(iq.to) { TestColibri2Server() }.handleConferenceModifyIq(iq)
            }
            return null
        }
    }

    private val failedSessions = mutableListOf<Bridge>()

    /** The participants reported for re-invite by [ColibriSessionManager.Listener.bridgeRemoved]. */
    private val reinvitedParticipants = mutableListOf<String>()

    private val listener = object : ColibriSessionManager.Listener {
        override fun bridgeCountChanged(bridgeCount: Int) {}
        override fun bridgeRemoved(bridge: Bridge, participantIds: List<String>) {
            failedSessions.add(bridge)
            reinvitedParticipants.addAll(participantIds)
        }
        override fun endpointRemoved(endpointId: String) {}
    }

    private val ioExecutor = PendingExecutor()
    private val clock = FakeClock()

    private fun drain() = ioExecutor.runAll()

    private fun jid(name: String) = JidCreate.from("jvbbrewery@example.com/$name")

    /**
     * Create a [BridgeSelector] and a [ColibriV2SessionManager]. The selector reads its strategy from the config, so
     * this must run inside the [withNewConfig] block of the context.
     */
    private fun createSessionManager(bridgeSelector: BridgeSelector) = ColibriV2SessionManager(
        xmppConnection.xmppConnection,
        bridgeSelector,
        "test-conference",
        "test-meeting-id",
        false,
        null,
        createLogger(),
        clock
    ).apply { addListener(listener) }

    private fun ColibriV2SessionManager.allocate(id: String, region: String? = null) = allocate(
        ParticipantAllocationParameters(
            id = id,
            statsId = null,
            region = region,
            sources = EndpointSourceSet.EMPTY,
            useSsrcRewriting = false,
            useRtpMidDemux = false,
            forceMuteAudio = false,
            forceMuteVideo = false,
            useSctp = false,
            visitor = false,
            supportsPrivateAddresses = false,
            medias = emptySet(),
            diarize = false
        )
    ).also { drain() }

    private fun requestsTo(bridge: Bridge) = colibriRequests[bridge.jid] ?: emptyList()

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = ioExecutor.executor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    init {
        // Bridges commonly have relay IDs even when Octo is disabled. Without relay IDs all sessions map to the same
        // (null) key, so test both.
        listOf(true, false).forEach { withRelayIds ->
            context("Octo disabled, bridges ${if (withRelayIds) "with" else "without"} relay IDs") {
                val bridgeSelector = BridgeSelector(clock)
                // The lower stress makes jvb1 the initial selection.
                val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                    setStats(stress = 0.1, relayId = if (withRelayIds) "jvb1" else null)
                }
                val jvb2 = bridgeSelector.addJvbAddress(jid("jvb2")).apply {
                    setStats(stress = 0.5, relayId = if (withRelayIds) "jvb2" else null)
                }
                val sessionManager = createSessionManager(bridgeSelector)

                sessionManager.allocate("p1")
                sessionManager.allocate("p2")
                sessionManager.getBridges().keys shouldBe setOf(jvb1)

                context("When the bridge is operational") {
                    sessionManager.allocate("p3")
                    should("allocate on the conference's bridge") {
                        sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2", "p3")
                        requestsTo(jvb2).shouldBeEmpty()
                        failedSessions.shouldBeEmpty()
                    }
                }
                context("When the bridge is not operational") {
                    // E.g. its health check timed out.
                    jvb1.isOperational = false

                    context("And it still responds") {
                        sessionManager.allocate("p3")
                        should("allocate on the conference's bridge") {
                            sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2", "p3")
                        }
                        should("not move the conference") {
                            requestsTo(jvb2).shouldBeEmpty()
                            failedSessions.shouldBeEmpty()
                            reinvitedParticipants.shouldBeEmpty()
                        }
                    }
                    context("And it does not respond") {
                        unresponsiveBridges.add(jvb1.jid)
                        val e = shouldThrow<ColibriAllocationFailedException> { sessionManager.allocate("p3") }
                        // [allocate] threw before it ran the async tasks.
                        drain()
                        should("remove the session and report all its participants, including the new one") {
                            e.removeBridge shouldBe true
                            failedSessions shouldBe listOf(jvb1)
                            reinvitedParticipants.toSet() shouldBe setOf("p1", "p2", "p3")
                            sessionManager.getBridges().keys.shouldBeEmpty()
                        }
                        context("And re-inviting the participants") {
                            listOf("p1", "p2", "p3").forEach { sessionManager.allocate(it) }
                            should("move them to another bridge") {
                                sessionManager.getBridges().keys shouldBe setOf(jvb2)
                                sessionManager.getParticipants(jvb2) shouldBe listOf("p1", "p2", "p3")
                            }
                        }
                    }
                    context("And no other bridge is operational") {
                        jvb2.isOperational = false
                        val e = runCatching { sessionManager.allocate("p3") }.exceptionOrNull()
                        drain()
                        should("fail bridge selection") {
                            (e is BridgeSelectionFailedException) shouldBe true
                            failedSessions.shouldBeEmpty()
                            requestsTo(jvb2).shouldBeEmpty()
                        }
                    }
                }
            }
        }

        // With Octo enabled and both bridges supporting it, the new participant goes to another bridge, and the
        // relay to the non-operational bridge fails the session if that bridge does not respond.
        listOf(true, false).forEach { responds ->
            context(
                "Octo enabled, the conference's bridge not operational and ${if (responds) "" else "not "}responding"
            ) {
                withNewConfig("jicofo.octo.enabled=true") {
                    val bridgeSelector = BridgeSelector(clock)
                    val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                        setStats(stress = 0.1, relayId = "jvb1")
                    }
                    val jvb2 = bridgeSelector.addJvbAddress(jid("jvb2")).apply {
                        setStats(stress = 0.5, relayId = "jvb2")
                    }
                    val sessionManager = createSessionManager(bridgeSelector)
                    sessionManager.allocate("p1")
                    sessionManager.allocate("p2")
                    sessionManager.getBridges().keys shouldBe setOf(jvb1)

                    jvb1.isOperational = false
                    if (!responds) unresponsiveBridges.add(jvb1.jid)
                    sessionManager.allocate("p3")

                    should("allocate the new participant on another bridge") {
                        sessionManager.getParticipants(jvb2) shouldBe listOf("p3")
                    }
                    should("create a relay to the new bridge on the conference's bridge") {
                        requestsTo(jvb1).flatMap { it.relays }.filter { it.create }.map { it.id } shouldBe
                            listOf("jvb2")
                    }
                    if (responds) {
                        should("keep the existing participants on the conference's bridge") {
                            sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2")
                            failedSessions.shouldBeEmpty()
                        }
                    } else {
                        should("fail the session on the unresponsive bridge when the relay request times out") {
                            failedSessions shouldBe listOf(jvb1)
                            reinvitedParticipants shouldBe listOf("p1", "p2")
                            sessionManager.getBridges().keys shouldBe setOf(jvb2)
                        }
                    }
                }
            }
        }

        // With Octo enabled, but a bridge without a relay ID, the conference can not use a second bridge.
        listOf(true, false).forEach { responds ->
            context(
                "Octo enabled, the conference's bridge has no relay ID, not operational and " +
                    "${if (responds) "" else "not "}responding"
            ) {
                withNewConfig("jicofo.octo.enabled=true") {
                    val bridgeSelector = BridgeSelector(clock)
                    val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                        setStats(stress = 0.1, relayId = null)
                    }
                    val jvb2 = bridgeSelector.addJvbAddress(jid("jvb2")).apply {
                        setStats(stress = 0.5, relayId = "jvb2")
                    }
                    val sessionManager = createSessionManager(bridgeSelector)
                    sessionManager.allocate("p1")
                    sessionManager.getBridges().keys shouldBe setOf(jvb1)

                    jvb1.isOperational = false
                    if (!responds) unresponsiveBridges.add(jvb1.jid)
                    val e = runCatching { sessionManager.allocate("p2") }.exceptionOrNull()
                    drain()

                    if (responds) {
                        should("allocate on the conference's bridge") {
                            e shouldBe null
                            sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2")
                            requestsTo(jvb2).shouldBeEmpty()
                            failedSessions.shouldBeEmpty()
                        }
                    } else {
                        should("remove the session and report all its participants") {
                            (e as ColibriAllocationFailedException).removeBridge shouldBe true
                            failedSessions shouldBe listOf(jvb1)
                            reinvitedParticipants.toSet() shouldBe setOf("p1", "p2")
                            sessionManager.getBridges().keys.shouldBeEmpty()
                        }
                    }
                }
            }
        }

        // More than one session: keep the old behavior (fail the selection).
        context("Octo enabled, two non-operational bridges and a new bridge with no relay ID") {
            withNewConfig(
                """
                jicofo.octo.enabled=true
                jicofo.bridge.selection-strategy=RegionBasedBridgeSelectionStrategy
                """.trimIndent()
            ) {
                val bridgeSelector = BridgeSelector(clock)
                val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                    setStats(stress = 0.1, region = "r1", relayId = "jvb1")
                }
                val jvb2 = bridgeSelector.addJvbAddress(jid("jvb2")).apply {
                    setStats(stress = 0.1, region = "r2", relayId = "jvb2")
                }
                val jvb3 = bridgeSelector.addJvbAddress(jid("jvb3")).apply {
                    setStats(stress = 0.1, region = "r3", relayId = null)
                }
                val sessionManager = createSessionManager(bridgeSelector)
                sessionManager.allocate("p1", region = "r1")
                sessionManager.allocate("p2", region = "r2")
                sessionManager.getBridges().keys shouldBe setOf(jvb1, jvb2)

                jvb1.isOperational = false
                jvb2.isOperational = false
                val e = runCatching { sessionManager.allocate("p3", region = "r3") }.exceptionOrNull()
                drain()

                should("fail bridge selection, and not use one of the existing bridges") {
                    (e is BridgeSelectionFailedException) shouldBe true
                    requestsTo(jvb3).shouldBeEmpty()
                    failedSessions.shouldBeEmpty()
                    sessionManager.getParticipants(jvb1) shouldBe listOf("p1")
                    sessionManager.getParticipants(jvb2) shouldBe listOf("p2")
                }
            }
        }
    }
}
