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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeConfig
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.ParticipantProperties
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.PendingExecutor
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.time.FakeClock
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

private fun transportWithGeneration(generation: Int?) = IceUdpTransportPacketExtension().apply {
    ufrag = "ufrag-$generation"
    password = "password-$generation"
    generation?.let { setIceGeneration(it) }
}

/**
 * Tests [ColibriV2SessionManager] against [TestColibri2Server]s, including multi-bridge (Octo) conferences with
 * relays.
 */
class ColibriV2SessionManagerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    /** Requests jicofo sent, per bridge JID. */
    private val colibriRequests = mutableMapOf<Jid, MutableList<ConferenceModifyIQ>>()

    /** One colibri2 server per bridge JID, IQs are routed by their `to` address. */
    private val colibriServers = mutableMapOf<Jid, TestColibri2Server>()
    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? {
            if (iq is ConferenceModifyIQ) {
                colibriRequests.computeIfAbsent(iq.to) { mutableListOf() }.add(iq)
                return colibriServers.computeIfAbsent(iq.to) { TestColibri2Server() }.handleConferenceModifyIq(iq)
            }
            return null
        }
    }

    private fun createBridge(name: String, bridgeRelayId: String? = name, bridgeRegion: String = "region-$name") =
        mockk<Bridge>(relaxed = true) {
            every { jid } returns JidCreate.from("jvbbrewery@example.com/$name")
            every { relayId } returns bridgeRelayId
            every { isOperational } returns true
            every { debugState } returns JsonNodeFactory.instance.objectNode()
            every { region } returns bridgeRegion
        }

    private val bridge1 = createBridge("jvb1")
    private val bridge2 = createBridge("jvb2")

    private val bridgeSelector: BridgeSelector = mockk {
        every { selectBridge(any(), any(), any()) } answers {
            // Select a bridge matching the participant's region, if any.
            val region = secondArg<ParticipantProperties>().region
            listOf(bridge1, bridge2).find { it.region == region } ?: bridge1
        }
    }

    private val failedSessions = mutableListOf<Bridge>()
    private val removedEndpoints = mutableListOf<String>()

    /** The transports relayed to participants after an ICE restart, in the order they were fired. */
    private val iceRestartedTransports = mutableListOf<Pair<String, IceUdpTransportPacketExtension>>()
    private val listener = object : ColibriSessionManager.Listener {
        override fun bridgeCountChanged(bridgeCount: Int) {}
        override fun bridgeRemoved(bridge: Bridge, participantIds: List<String>) {
            failedSessions.add(bridge)
        }
        override fun endpointRemoved(endpointId: String) {
            removedEndpoints.add(endpointId)
        }
        override fun endpointIceRestarted(endpointId: String, transport: IceUdpTransportPacketExtension) {
            iceRestartedTransports.add(endpointId to transport)
        }
    }

    /**
     * "Async" tasks (response handling, event emission) are queued and executed by [drain], modeling the fact that
     * in production they run on a separate thread after the initiating call has completed (and released its locks).
     */
    private val ioExecutor = PendingExecutor()

    private val clock = FakeClock()

    // Initialized lazily so that construction happens after [beforeAny] has replaced the TaskPools executors.
    private val sessionManager by lazy {
        ColibriV2SessionManager(
            xmppConnection.xmppConnection,
            bridgeSelector,
            "test-conference",
            "test-meeting-id",
            false,
            null,
            createLogger(),
            clock
        ).apply { addListener(listener) }
    }

    private fun drain() = ioExecutor.runAll()

    private fun allocate(id: String, region: String? = null) = sessionManager.allocate(
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
        context("Single bridge") {
            val allocation = allocate("p1", region = "region-jvb1")

            should("allocate an endpoint on the bridge") {
                allocation.shouldNotBeNull()
                sessionManager.getBridges().keys shouldBe setOf(bridge1)
                sessionManager.getParticipants(bridge1) shouldBe listOf("p1")
            }
            should("send a conference create request") {
                requestsTo(bridge1).count { it.create } shouldBe 1
            }
            should("not create any relays") {
                requestsTo(bridge1).flatMap { it.relays }.shouldBeEmpty()
            }
            context("And removing the participant") {
                sessionManager.removeParticipant("p1").also { drain() }
                should("expire the conference on the bridge") {
                    // Removing the last participant expires the whole colibri2 conference.
                    requestsTo(bridge1).count { it.expire } shouldBe 1
                }
                should("leave no bridges in the conference") {
                    sessionManager.getBridges().keys.shouldBeEmpty()
                }
            }
        }

        context("Two bridges") {
            withNewConfig("jicofo.octo.enabled=true") {
                allocate("p1", region = "region-jvb1")
                allocate("p2", region = "region-jvb2")

                should("allocate endpoints on both bridges") {
                    sessionManager.getBridges().keys shouldBe setOf(bridge1, bridge2)
                    sessionManager.getParticipants(bridge1) shouldBe listOf("p1")
                    sessionManager.getParticipants(bridge2) shouldBe listOf("p2")
                }
                should("create a relay on each bridge pointing to the other") {
                    val relaysOn1 = requestsTo(bridge1).flatMap { it.relays }.filter { it.create }
                    val relaysOn2 = requestsTo(bridge2).flatMap { it.relays }.filter { it.create }
                    relaysOn1.map { it.id } shouldBe listOf("jvb2")
                    relaysOn2.map { it.id } shouldBe listOf("jvb1")
                }
                should("exchange relay transports (one side active, one side passive)") {
                    val setups = listOf(bridge1, bridge2).map { bridge ->
                        // The transport update for the relay (create=false, with a transport).
                        val transportUpdate = requestsTo(bridge).flatMap { it.relays }
                            .filter { !it.create && it.transport?.iceUdpTransport != null }
                        transportUpdate.size shouldBe 1
                        transportUpdate.first().transport!!.iceUdpTransport!!.getChildExtensionsOfType(
                            DtlsFingerprintPacketExtension::class.java
                        ).first().setup
                    }
                    setups.toSet() shouldBe setOf("active", "passive")
                }
                should("not fail any sessions") {
                    failedSessions.shouldBeEmpty()
                }
                should("signal each participant as a remote endpoint on the other bridge's relay") {
                    val relayEndpointsOn1 = requestsTo(bridge1).flatMap { it.relays }
                        .mapNotNull { it.endpoints }.flatMap { it.endpoints }.map { it.id }
                    val relayEndpointsOn2 = requestsTo(bridge2).flatMap { it.relays }
                        .mapNotNull { it.endpoints }.flatMap { it.endpoints }.map { it.id }
                    relayEndpointsOn1 shouldBe listOf("p2")
                    relayEndpointsOn2 shouldBe listOf("p1")
                }

                context("And removing the participant on the second bridge") {
                    sessionManager.removeParticipant("p2").also { drain() }

                    should("expire the second bridge's session and its relay") {
                        sessionManager.getBridges().keys shouldBe setOf(bridge1)
                        val relayExpires = requestsTo(bridge1).flatMap { it.relays }.filter { it.expire }
                        relayExpires.map { it.id } shouldBe listOf("jvb2")
                    }
                    should("not fail any sessions") {
                        failedSessions.shouldBeEmpty()
                    }
                }
            }
        }

        context("A bridge failing") {
            withNewConfig("jicofo.octo.enabled=true") {
                allocate("p1", region = "region-jvb1")
                allocate("p2", region = "region-jvb2")

                val removed = sessionManager.removeBridge(bridge2).also { drain() }
                should("report the participants on the failed bridge") {
                    removed shouldBe listOf("p2")
                }
                should("expire the relay to the failed bridge") {
                    val relayExpires = requestsTo(bridge1).flatMap { it.relays }.filter { it.expire }
                    relayExpires.map { it.id } shouldBe listOf("jvb2")
                }
                should("keep the remaining bridge in the conference") {
                    sessionManager.getBridges().keys shouldBe setOf(bridge1)
                    sessionManager.getParticipants(bridge1) shouldBe listOf("p1")
                }
            }
        }

        context("Tracking recently added endpoints") {
            withNewConfig("jicofo.octo.enabled=true") {
                allocate("p1", region = "region-jvb1")
                allocate("p2", region = "region-jvb1")
                allocate("p3", region = "region-jvb2")

                should("count the endpoints that this conference added to each bridge") {
                    sessionManager.getBridges()[bridge1]!!.recentlyAddedParticipantCount shouldBe 2
                    sessionManager.getBridges()[bridge2]!!.recentlyAddedParticipantCount shouldBe 1
                }
                should("not decrease the count when a participant is removed") {
                    sessionManager.removeParticipant("p2").also { drain() }
                    with(sessionManager.getBridges()[bridge1]!!) {
                        participantCount shouldBe 1
                        recentlyAddedParticipantCount shouldBe 2
                    }
                }
                should("decay the count after the configured interval") {
                    clock.elapse(BridgeConfig.config.maxBridgeParticipantsInterval + 100.ms)
                    sessionManager.getBridges()[bridge1]!!.recentlyAddedParticipantCount shouldBe 0
                }
                should("reset the count when the session is removed and re-created") {
                    sessionManager.removeParticipant("p1").also { drain() }
                    sessionManager.removeParticipant("p2").also { drain() }
                    sessionManager.getBridges().keys shouldBe setOf(bridge2)

                    allocate("p4", region = "region-jvb1")
                    sessionManager.getBridges()[bridge1]!!.recentlyAddedParticipantCount shouldBe 1
                }
            }
        }

        context("ICE restart") {
            allocate("p1", region = "region-jvb1")

            context("For an existing participant") {
                sessionManager.restartIce("p1").also { drain() }

                should("request an ICE restart for the endpoint from its bridge") {
                    val iceRestarts = requestsTo(bridge1).flatMap { it.endpoints }
                        .filter { it.transport?.iceRestart == true }
                    iceRestarts.map { it.id } shouldBe listOf("p1")
                }
                should("relay the bridge's rotated transport back to the participant") {
                    iceRestartedTransports.size shouldBe 1
                    val (endpointId, transport) = iceRestartedTransports.first()
                    endpointId shouldBe "p1"
                    // The bridge rotated its credentials and tagged them with the first generation.
                    transport.iceGeneration shouldBe 1
                    transport.ufrag shouldBe "ufrag-test-meeting-id-p1-1"
                }
                should("not fail the session") {
                    failedSessions.shouldBeEmpty()
                }

                context("And restarting again") {
                    sessionManager.restartIce("p1").also { drain() }

                    should("relay the next generation") {
                        iceRestartedTransports.map { it.second.iceGeneration } shouldBe listOf(1, 2)
                    }
                }
            }

            context("For an unknown participant") {
                sessionManager.restartIce("nonexistent").also { drain() }

                should("not send anything to the bridge") {
                    requestsTo(bridge1).flatMap { it.endpoints }.none { it.transport?.iceRestart == true } shouldBe true
                }
                should("not relay anything") {
                    iceRestartedTransports.shouldBeEmpty()
                }
            }

            context("With responses arriving out of order") {
                // Colibri2 responses are handled on an IO pool, so two restarts in quick succession can be handled in
                // either order. Only the latest generation may be relayed on to the participant.
                sessionManager.endpointIceRestarted("p1", transportWithGeneration(2)).also { drain() }
                sessionManager.endpointIceRestarted("p1", transportWithGeneration(1)).also { drain() }
                sessionManager.endpointIceRestarted("p1", transportWithGeneration(3)).also { drain() }

                should("drop the stale generation and relay the rest") {
                    iceRestartedTransports.map { it.second.iceGeneration } shouldBe listOf(2, 3)
                }
            }

            context("With a bridge that does not tag generations") {
                sessionManager.endpointIceRestarted("p1", transportWithGeneration(null)).also { drain() }
                sessionManager.endpointIceRestarted("p1", transportWithGeneration(null)).also { drain() }

                should("relay everything (the guard can not order untagged transports)") {
                    iceRestartedTransports.size shouldBe 2
                    iceRestartedTransports.map {
                        it.second.iceGeneration
                    } shouldBe listOf(GENERATION_UNSPECIFIED, GENERATION_UNSPECIFIED)
                }
            }
        }

        context("Debug state") {
            withNewConfig("jicofo.octo.enabled=true") {
                allocate("p1", region = "region-jvb1")
                allocate("p2", region = "region-jvb2")
                sessionManager.debugState shouldNotBe null
            }
        }
    }
}
