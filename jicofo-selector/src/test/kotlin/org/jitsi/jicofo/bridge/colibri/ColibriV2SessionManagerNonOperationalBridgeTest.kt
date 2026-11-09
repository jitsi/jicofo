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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.setStats
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.PendingExecutor
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.createError
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.time.FakeClock
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StanzaError.Condition.conflict
import org.jivesoftware.smack.packet.StanzaError.Condition.internal_server_error
import org.jivesoftware.smack.packet.StanzaError.Condition.service_unavailable
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

    /** Bridges which respond to colibri2 requests with an error, and the error they respond with. */
    private val failingBridges = mutableMapOf<Jid, BridgeError>()

    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? {
            if (iq is ConferenceModifyIQ) {
                colibriRequests.computeIfAbsent(iq.to) { mutableListOf() }.add(iq)
                if (iq.to in unresponsiveBridges) {
                    return null
                }
                failingBridges[iq.to]?.let { error ->
                    return createError(iq, error.condition, extension = error.reason?.let { Colibri2Error(it) })
                }
                return colibriServers.computeIfAbsent(iq.to) { TestColibri2Server() }.handleConferenceModifyIq(iq)
            }
            return null
        }
    }

    private val failedSessions = mutableListOf<Bridge>()

    /** The participants reported for re-invite by [ColibriSessionManager.Listener.bridgeRemoved]. */
    private val reinvitedParticipants = mutableListOf<String>()

    /** The [ColibriSessionManager.Listener.bridgeSelectionFailed] and [bridgeSelectionSucceeded] events, in order. */
    private val selectionEvents = mutableListOf<Boolean>()

    private val listener = object : ColibriSessionManager.Listener {
        override fun bridgeCountChanged(bridgeCount: Int) {}
        override fun bridgeRemoved(bridge: Bridge, participantIds: List<String>) {
            failedSessions.add(bridge)
            reinvitedParticipants.addAll(participantIds)
        }
        override fun endpointRemoved(endpointId: String) {}
        override fun bridgeSelectionFailed() {
            selectionEvents.add(false)
        }
        override fun bridgeSelectionSucceeded() {
            selectionEvents.add(true)
        }
    }

    private val ioExecutor = PendingExecutor()
    private val clock = FakeClock()

    private fun drain() = ioExecutor.runAll()

    private fun jid(name: String) = JidCreate.from("jvbbrewery@example.com/$name")

    /** Create a [ColibriV2SessionManager] which uses [bridgeSelector]. */
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

    private fun ColibriV2SessionManager.allocate(id: String, region: String? = null, visitor: Boolean = false) =
        allocate(
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
                visitor = visitor,
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
        // A bridge reports a relay ID when relays are enabled on the bridge, independently of the Octo setting in
        // jicofo. Without relay IDs all sessions map to the same (null) key, so test both.
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
                    context("And it still responds, with a visitor joining") {
                        sessionManager.allocate("v1", visitor = true)
                        should("allocate the visitor on the conference's bridge") {
                            sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2", "v1")
                            requestsTo(jvb2).shouldBeEmpty()
                            failedSessions.shouldBeEmpty()
                        }
                    }
                    context("And it does not respond") {
                        unresponsiveBridges.add(jvb1.jid)
                        val e = shouldThrow<ColibriAllocationFailedException> { sessionManager.allocate("p3") }
                        // The call threw, so the helper did not drain. Run the queued bridgeRemoved event.
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
                    context("And it responds with an error") {
                        failingBridges[jvb1.jid] = INTERNAL_SERVER_ERROR
                        val e = runCatching { sessionManager.allocate("p3") }.exceptionOrNull()
                        // The call threw, so the helper did not drain. Run the queued bridgeRemoved event.
                        drain()
                        should("remove the session and report all its participants, including the new one") {
                            e.shouldBeInstanceOf<ColibriAllocationFailedException>().removeBridge shouldBe true
                            failedSessions shouldBe listOf(jvb1)
                            reinvitedParticipants.toSet() shouldBe setOf("p1", "p2", "p3")
                            sessionManager.getBridges().keys.shouldBeEmpty()
                        }
                    }
                    context("And no other bridge is operational") {
                        // The selector returns no bridge. Before, this failed the selection.
                        jvb2.isOperational = false
                        val e = runCatching { sessionManager.allocate("p3") }.exceptionOrNull()
                        drain()
                        should("allocate on the conference's bridge") {
                            e shouldBe null
                            sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2", "p3")
                            requestsTo(jvb2).shouldBeEmpty()
                            failedSessions.shouldBeEmpty()
                        }
                    }
                }
            }
        }

        // The selector returns no bridge, because the only bridge is not operational (e.g. its health check timed
        // out). Use the conference's bridge. If it fails, fail only the new participant: there is no other bridge to
        // move the conference to, and the existing participants may still have working media.
        listOf(true, false).forEach { octoEnabled ->
            listOf(Outcome.RESPONDS, Outcome.TIMEOUT, Outcome.ERROR).forEach { outcome ->
                context(
                    "Octo ${if (octoEnabled) "enabled" else "disabled"}, the only bridge not operational and $outcome"
                ) {
                    withNewConfig("jicofo.octo.enabled=$octoEnabled") {
                        val bridgeSelector = BridgeSelector(clock)
                        val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                            setStats(stress = 0.1, relayId = "jvb1")
                        }
                        val sessionManager = createSessionManager(bridgeSelector)
                        sessionManager.allocate("p1")
                        sessionManager.allocate("p2")
                        sessionManager.getBridges().keys shouldBe setOf(jvb1)

                        jvb1.isOperational = false
                        when (outcome) {
                            Outcome.TIMEOUT -> unresponsiveBridges.add(jvb1.jid)
                            Outcome.ERROR -> failingBridges[jvb1.jid] = INTERNAL_SERVER_ERROR
                            Outcome.RESPONDS -> {}
                        }
                        selectionEvents.clear()
                        val e = runCatching { sessionManager.allocate("p3") }.exceptionOrNull()
                        // The call may have thrown, so the helper did not drain. Run any queued events.
                        drain()

                        if (outcome == Outcome.RESPONDS) {
                            should("allocate on the conference's bridge") {
                                e shouldBe null
                                sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2", "p3")
                                failedSessions.shouldBeEmpty()
                                selectionEvents shouldBe listOf(true)
                            }
                        } else {
                            should("fail only the new participant") {
                                e.shouldBeInstanceOf<ColibriAllocationFailedException>().removeBridge shouldBe false
                                sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2")
                                sessionManager.bridgeCount shouldBe 1
                            }
                            should("not remove the session or re-invite the existing participants") {
                                failedSessions.shouldBeEmpty()
                                reinvitedParticipants.shouldBeEmpty()
                            }
                            should("not report the bridge selection as failed") {
                                // The "bridge not available" presence reaches all occupants, and clients leave the
                                // conference when they receive it.
                                selectionEvents shouldBe listOf(true)
                            }
                            context("And the bridge responds again") {
                                unresponsiveBridges.clear()
                                failingBridges.clear()
                                sessionManager.allocate("p4")
                                should("allocate the next participant on the conference's bridge") {
                                    sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2", "p4")
                                    failedSessions.shouldBeEmpty()
                                    selectionEvents.last() shouldBe true
                                }
                            }
                        }
                    }
                }
            }
        }

        // Each error response which removes the session on the normal path fails only the new participant in the
        // single-bridge fallback.
        BRIDGE_ERRORS.forEach { error ->
            listOf(true, false).forEach { fallback ->
                context(
                    "The only bridge ${if (fallback) "not " else ""}operational and responding with $error"
                ) {
                    val bridgeSelector = BridgeSelector(clock)
                    val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                        setStats(stress = 0.1, relayId = "jvb1")
                    }
                    val sessionManager = createSessionManager(bridgeSelector)
                    sessionManager.allocate("p1")
                    sessionManager.allocate("p2")
                    sessionManager.getBridges().keys shouldBe setOf(jvb1)

                    if (fallback) jvb1.isOperational = false
                    failingBridges[jvb1.jid] = error
                    val e = runCatching { sessionManager.allocate("p3") }.exceptionOrNull()
                    // The call threw, so the helper did not drain. Run any queued events.
                    drain()

                    if (fallback) {
                        should("fail only the new participant") {
                            e.shouldBeInstanceOf<ColibriAllocationFailedException>().removeBridge shouldBe false
                            sessionManager.getParticipants(jvb1) shouldBe listOf("p1", "p2")
                            sessionManager.bridgeCount shouldBe 1
                        }
                        should("not remove the session or re-invite the existing participants") {
                            failedSessions.shouldBeEmpty()
                            reinvitedParticipants.shouldBeEmpty()
                        }
                        should("not report the bridge selection as failed") {
                            selectionEvents.contains(false) shouldBe false
                        }
                    } else {
                        should("remove the session and report all its participants, including the new one") {
                            e.shouldBeInstanceOf<ColibriAllocationFailedException>().removeBridge shouldBe true
                            failedSessions shouldBe listOf(jvb1)
                            reinvitedParticipants.toSet() shouldBe setOf("p1", "p2", "p3")
                            sessionManager.bridgeCount shouldBe 0
                        }
                    }
                    if (error.reason == Colibri2Error.Reason.GRACEFUL_SHUTDOWN) {
                        should("mark the bridge as in graceful shutdown") {
                            jvb1.isInGracefulShutdown shouldBe true
                        }
                    }
                }
            }
        }

        // The selector returns no bridge, and there is more than one session: there is no single bridge to fall
        // back to, so keep the old behavior (fail the selection).
        context("Octo enabled, two sessions and no operational bridge") {
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
                val sessionManager = createSessionManager(bridgeSelector)
                sessionManager.allocate("p1", region = "r1")
                sessionManager.allocate("p2", region = "r2")
                sessionManager.getBridges().keys shouldBe setOf(jvb1, jvb2)

                jvb1.isOperational = false
                jvb2.isOperational = false
                val requestsBefore = requestsTo(jvb1).size + requestsTo(jvb2).size
                val e = runCatching { sessionManager.allocate("p3", region = "r1") }.exceptionOrNull()
                drain()

                should("fail bridge selection, and not use one of the existing bridges") {
                    e.shouldBeInstanceOf<BridgeSelectionFailedException>()
                    requestsTo(jvb1).size + requestsTo(jvb2).size shouldBe requestsBefore
                    failedSessions.shouldBeEmpty()
                    sessionManager.getParticipants(jvb1) shouldBe listOf("p1")
                    sessionManager.getParticipants(jvb2) shouldBe listOf("p2")
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
                            e.shouldBeInstanceOf<ColibriAllocationFailedException>().removeBridge shouldBe true
                            failedSessions shouldBe listOf(jvb1)
                            reinvitedParticipants.toSet() shouldBe setOf("p1", "p2")
                            sessionManager.getBridges().keys.shouldBeEmpty()
                        }
                    }
                }
            }
        }

        // The conference's bridge is operational: keep the old behavior (fail the selection).
        context("Octo enabled, an operational bridge and a new bridge with no relay ID") {
            withNewConfig(
                """
                jicofo.octo.enabled=true
                jicofo.bridge.selection-strategy=RegionBasedBridgeSelectionStrategy
                """.trimIndent()
            ) {
                // [BridgeSelector] reads its selection strategy from the config when it is created, so create it inside
                // [withNewConfig].
                val bridgeSelector = BridgeSelector(clock)
                val jvb1 = bridgeSelector.addJvbAddress(jid("jvb1")).apply {
                    setStats(stress = 0.1, region = "r1", relayId = "jvb1")
                }
                val jvb2 = bridgeSelector.addJvbAddress(jid("jvb2")).apply {
                    setStats(stress = 0.1, region = "r2", relayId = null)
                }
                val sessionManager = createSessionManager(bridgeSelector)
                sessionManager.allocate("p1", region = "r1")
                sessionManager.getBridges().keys shouldBe setOf(jvb1)

                // The selector returns jvb2 for the participant's region, but jvb2 does not support Octo.
                val e = runCatching { sessionManager.allocate("p2", region = "r2") }.exceptionOrNull()
                drain()

                should("fail bridge selection, and not use the conference's bridge") {
                    e.shouldBeInstanceOf<BridgeSelectionFailedException>()
                    requestsTo(jvb2).shouldBeEmpty()
                    failedSessions.shouldBeEmpty()
                    sessionManager.getParticipants(jvb1) shouldBe listOf("p1")
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
                // [BridgeSelector] reads its selection strategy from the config when it is created, so create it inside
                // [withNewConfig].
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
                    e.shouldBeInstanceOf<BridgeSelectionFailedException>()
                    requestsTo(jvb3).shouldBeEmpty()
                    failedSessions.shouldBeEmpty()
                    sessionManager.getParticipants(jvb1) shouldBe listOf("p1")
                    sessionManager.getParticipants(jvb2) shouldBe listOf("p2")
                }
            }
        }
    }

    private enum class Outcome(private val description: String) {
        RESPONDS("responding"),
        TIMEOUT("not responding"),
        ERROR("responding with an error");

        override fun toString() = description
    }

    /** An error response from a bridge: a stanza error [condition], and an optional colibri2 [reason]. */
    private data class BridgeError(
        val condition: StanzaError.Condition,
        val reason: Colibri2Error.Reason?,
        private val description: String
    ) {
        override fun toString() = description
    }

    companion object {
        private val INTERNAL_SERVER_ERROR = BridgeError(internal_server_error, null, "internal-server-error")

        /** The errors which make the session manager remove the session, unless it falls back to a single bridge. */
        private val BRIDGE_ERRORS = listOf(
            BridgeError(conflict, null, "conflict, with no colibri2 reason"),
            BridgeError(conflict, Colibri2Error.Reason.UNKNOWN_ENDPOINT, "conflict, with a colibri2 reason"),
            BridgeError(service_unavailable, Colibri2Error.Reason.GRACEFUL_SHUTDOWN, "graceful shutdown"),
            BridgeError(service_unavailable, null, "service-unavailable"),
            INTERNAL_SERVER_ERROR
        )
    }
}
