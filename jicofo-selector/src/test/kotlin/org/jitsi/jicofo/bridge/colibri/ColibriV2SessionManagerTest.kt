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
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.ParticipantProperties
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.PendingExecutor
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

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
    private val listener = object : ColibriSessionManager.Listener {
        override fun bridgeCountChanged(bridgeCount: Int) {}
        override fun bridgeRemoved(bridge: Bridge, participantIds: List<String>) {
            failedSessions.add(bridge)
        }
        override fun endpointRemoved(endpointId: String) {
            removedEndpoints.add(endpointId)
        }
    }

    /**
     * "Async" tasks (response handling, event emission) are queued and executed by [drain], modeling the fact that
     * in production they run on a separate thread after the initiating call has completed (and released its locks).
     */
    private val ioExecutor = PendingExecutor()

    // Initialized lazily so that construction happens after [beforeAny] has replaced the TaskPools executors.
    private val sessionManager by lazy {
        ColibriV2SessionManager(
            xmppConnection.xmppConnection,
            bridgeSelector,
            "test-conference",
            "test-meeting-id",
            false,
            null,
            createLogger()
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

        context("Debug state") {
            withNewConfig("jicofo.octo.enabled=true") {
                allocate("p1", region = "region-jvb1")
                allocate("p2", region = "region-jvb2")
                sessionManager.debugState shouldNotBe null
            }
        }
    }
}
