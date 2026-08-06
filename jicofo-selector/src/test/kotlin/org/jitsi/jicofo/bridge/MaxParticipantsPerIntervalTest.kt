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
package org.jitsi.jicofo.bridge

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests the `max-bridge-participants-per-interval` limit, i.e. a bridge being considered overloaded for a specific
 * conference because that conference recently added too many endpoints to it.
 */
class MaxParticipantsPerIntervalTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private fun createBridge(name: String, stress: Double) = Bridge(JidCreate.from(name)).apply {
        setStats(stress = stress, region = REGION)
    }
    private val bridge1 = createBridge("bridge1", 0.0)
    private val bridge2 = createBridge("bridge2", 0.1)
    private val bridges = listOf(bridge1, bridge2)
    private val participant = ParticipantProperties(REGION)

    /** The conference has [recentlyAdded] endpoints on [bridge1] and none on [bridge2] (which it doesn't use). */
    private fun onlyBridge1(recentlyAdded: Long) = mapOf(bridge1 to conferenceBridge(recentlyAdded))

    private fun conferenceBridge(recentlyAdded: Long, participantCount: Int = 1) =
        ConferenceBridgeProperties(participantCount, false, recentlyAdded)

    init {
        context("With the limit disabled (the default)") {
            BridgeConfig.config.maxBridgeParticipantsPerInterval shouldBe -1

            with(RegionBasedBridgeSelectionStrategy()) {
                should("not affect selection regardless of the number of recently added endpoints") {
                    select(bridges, onlyBridge1(1000), participant, true) shouldBe bridge1
                }
            }
        }
        context("With the limit enabled") {
            withNewConfig("$MAX_PER_INTERVAL_CONFIG=$MAX_PER_INTERVAL") {
                with(RegionBasedBridgeSelectionStrategy()) {
                    should("keep using the bridge below the limit") {
                        select(bridges, onlyBridge1(MAX_PER_INTERVAL - 1L), participant, true) shouldBe bridge1
                    }
                    should("select another bridge once the limit is reached") {
                        select(bridges, onlyBridge1(MAX_PER_INTERVAL.toLong()), participant, true) shouldBe bridge2
                        select(bridges, onlyBridge1(MAX_PER_INTERVAL + 100L), participant, true) shouldBe bridge2
                    }
                    should("prefer another bridge that the conference already uses") {
                        val bridge3 = createBridge("bridge3", 0.2)
                        select(
                            bridges + bridge3,
                            mapOf(
                                bridge1 to conferenceBridge(MAX_PER_INTERVAL.toLong()),
                                bridge3 to conferenceBridge(0)
                            ),
                            participant,
                            true
                        ) shouldBe bridge3
                    }
                    should("fall through to the least loaded bridge when all bridges are over the limit") {
                        select(
                            bridges,
                            mapOf(
                                bridge1 to conferenceBridge(MAX_PER_INTERVAL.toLong()),
                                bridge2 to conferenceBridge(MAX_PER_INTERVAL.toLong())
                            ),
                            participant,
                            true
                        ) shouldBe bridge1
                    }
                    should("not affect the initial selection for a conference with no bridges") {
                        select(bridges, emptyMap(), participant, true) shouldBe bridge1
                    }
                }
                context("With IntraRegionBridgeSelectionStrategy") {
                    with(IntraRegionBridgeSelectionStrategy()) {
                        should("keep using the bridge below the limit") {
                            select(bridges, onlyBridge1(MAX_PER_INTERVAL - 1L), participant, true) shouldBe bridge1
                        }
                        should("select another bridge once the limit is reached") {
                            select(bridges, onlyBridge1(MAX_PER_INTERVAL.toLong()), participant, true) shouldBe bridge2
                        }
                    }
                }
                context("With all bridges overloaded") {
                    // The last-resort tier (leastLoadedNotMaxedAlreadyInConference) intentionally does not honor the
                    // rate limit: it exists to return *some* bridge rather than fail the allocation.
                    val overloaded1 = createBridge("overloaded1", 0.9)
                    val overloaded2 = createBridge("overloaded2", 0.95)
                    overloaded1.isOverloaded shouldBe true

                    with(RegionBasedBridgeSelectionStrategy()) {
                        should("still select a bridge that the conference already uses") {
                            select(
                                listOf(overloaded1, overloaded2),
                                mapOf(
                                    overloaded1 to conferenceBridge(MAX_PER_INTERVAL.toLong()),
                                    overloaded2 to conferenceBridge(MAX_PER_INTERVAL.toLong())
                                ),
                                participant,
                                true
                            ) shouldBe overloaded1
                        }
                    }
                }
                context("The bridge-level state") {
                    should("not be affected") {
                        // The limit is specific to a conference. Nothing about the bridge itself changes, so other
                        // conferences (and load redistribution, sorting, etc) are unaffected.
                        val conferenceBridges = onlyBridge1(MAX_PER_INTERVAL + 100L)
                        with(RegionBasedBridgeSelectionStrategy()) {
                            select(bridges, conferenceBridges, participant, true) shouldBe bridge2
                        }

                        bridge1.isOverloaded shouldBe false
                        bridge1.correctedStress shouldBe 0.0

                        // A different conference, which has not added anything to bridge1, still gets bridge1.
                        with(RegionBasedBridgeSelectionStrategy()) {
                            select(bridges, mapOf(bridge1 to conferenceBridge(0)), participant, true) shouldBe bridge1
                        }
                    }
                }
            }
        }
    }
}

private const val REGION = "region"
private const val MAX_PER_INTERVAL = 10
private const val MAX_PER_INTERVAL_CONFIG = "jicofo.bridge.max-bridge-participants-per-interval"
