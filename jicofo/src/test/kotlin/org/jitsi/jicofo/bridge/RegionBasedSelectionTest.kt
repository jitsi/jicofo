/*
 * Copyright @ 2022 - present 8x8, Inc.
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
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.bridge.Regions.ApSouth
import org.jitsi.jicofo.bridge.Regions.EuCentral
import org.jitsi.jicofo.bridge.Regions.EuWest
import org.jitsi.jicofo.bridge.Regions.UsEast
import org.jitsi.jicofo.bridge.Regions.UsWest
import org.jitsi.jicofo.bridge.StressLevels.High
import org.jitsi.jicofo.bridge.StressLevels.Low
import org.jitsi.jicofo.bridge.StressLevels.Medium

class RegionBasedSelectionTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val bridges = createBridges()
    private val bridgesList = bridges.values.flatMap { it.values }
    private fun BridgeSelectionStrategy.select(
        bridges: List<Bridge> = bridgesList,
        conferenceBridges: Map<Bridge, Int> = emptyMap(),
        participantRegion: Regions? = null
    ) = select(bridges, conferenceBridges, participantRegion?.region, true)

    init {
        context("Without region groups") {
            withNewConfig(maxBpConfig) {
                BridgeConfig.config.maxBridgeParticipants shouldBe maxBp

                with(RegionBasedBridgeSelectionStrategy()) {
                    context("In a single region") {
                        select().stress shouldBe Low.stress
                        select(participantRegion = ApSouth) shouldBe bridges[ApSouth][Low]
                        select(
                            participantRegion = ApSouth,
                            conferenceBridges = mapOf(bridges[ApSouth][Medium] to 1)
                        ) shouldBe bridges[ApSouth][Medium]
                        select(
                            participantRegion = ApSouth,
                            conferenceBridges = mapOf(bridges[ApSouth][Medium] to maxBp)
                        ) shouldBe bridges[ApSouth][Low]
                        select(
                            participantRegion = ApSouth,
                            conferenceBridges = mapOf(bridges[ApSouth][Low] to maxBp)
                        ) shouldBe bridges[ApSouth][Medium]
                        select(
                            participantRegion = ApSouth,
                            conferenceBridges = mapOf(
                                bridges[ApSouth][Low] to 2,
                                bridges[ApSouth][Medium] to 1
                            )
                        ) shouldBe bridges[ApSouth][Low]
                        select(
                            conferenceBridges = mapOf(
                                bridges[ApSouth][Low] to 2,
                                bridges[ApSouth][Medium] to 1
                            )
                        ) shouldBe bridges[ApSouth][Low]
                    }
                    context("In multiple regions") {
                        select(
                            conferenceBridges = mapOf(
                                bridges[ApSouth][Low] to 2,
                                bridges[EuWest][Medium] to 1,
                                bridges[EuWest][High] to 1
                            )
                        ) shouldBe bridges[ApSouth][Low]
                        select(
                            participantRegion = EuWest,
                            conferenceBridges = mapOf(
                                bridges[ApSouth][Low] to 2,
                                bridges[EuWest][Medium] to 1,
                                bridges[EuWest][High] to 1
                            )
                        ) shouldBe bridges[EuWest][Medium]
                        select(
                            participantRegion = EuCentral,
                            conferenceBridges = mapOf(
                                bridges[ApSouth][Low] to 2,
                                bridges[EuWest][Medium] to 1,
                                bridges[EuWest][High] to 1
                            )
                        ) shouldBe bridges[EuCentral][Low]
                        select(
                            participantRegion = EuCentral,
                            conferenceBridges = mapOf(
                                bridges[ApSouth][Low] to 2,
                                bridges[EuWest][Medium] to 1,
                                bridges[EuWest][High] to 1,
                                bridges[EuCentral][Low] to maxBp
                            )
                        ) shouldBe bridges[EuCentral][Medium]
                    }
                }
            }
        }
        context("With region groups") {
            withNewConfig(regionGroupsConfig) {
                BridgeConfig.config.maxBridgeParticipants shouldBe maxBp

                with(RegionBasedBridgeSelectionStrategy()) {
                    select(participantRegion = ApSouth) shouldBe bridges[ApSouth][Low]
                    select(participantRegion = EuWest) shouldBe bridges[EuWest][Low]

                    // When there are no bridges available in the region, regions in the group should be preferred
                    select(
                        participantRegion = EuWest,
                        bridges = bridgesList.filterNot { it.region == EuWest.region }
                    ) shouldBe bridges[EuCentral][Low]
                    select(
                        participantRegion = EuWest,
                        bridges = bridgesList.filterNot { it.region == EuWest.region },
                        conferenceBridges = mapOf(
                            bridges[UsEast][Low] to 1
                        )
                    ) shouldBe bridges[EuCentral][Low]

                    select(
                        participantRegion = EuCentral,
                        conferenceBridges = mapOf(
                            bridges[ApSouth][Low] to 2,
                            bridges[EuWest][Medium] to 1,
                            bridges[EuWest][High] to 1,
                            bridges[EuCentral][Low] to maxBp
                        )
                    ) shouldBe bridges[EuWest][Medium]
                    select(
                        participantRegion = UsEast,
                        conferenceBridges = mapOf(
                            bridges[ApSouth][Low] to 2,
                            bridges[EuWest][Medium] to 1,
                            bridges[EuWest][High] to 1,
                            bridges[EuCentral][Low] to maxBp
                        )
                    ) shouldBe bridges[UsEast][Low]
                    context("Initial selection in the local region group, but not in the local region") {
                        select(participantRegion = UsWest, conferenceBridges = emptyMap()) shouldBe bridges[UsEast][Low]
                    }
                }
            }
        }
    }
}

private const val maxBp = 10
const val maxBpConfig = "jicofo.bridge.max-bridge-participants=$maxBp"
val regionGroupsConfig = """
    jicofo.local-region = ${UsEast.region}
    jicofo.bridge.selection-strategy=RegionBasedBridgeSelectionStrategy
    jicofo.bridge.region-groups = [
       [ "${UsEast.region}", "${UsWest.region}" ],
       [ "${EuCentral.region}", "${EuWest.region}" ],
    ]
    $maxBpConfig
""".trimIndent()

private fun mockBridge(r: Regions, s: StressLevels) = mockk<Bridge> {
    every { region } returns r.region
    every { stress } returns s.stress
    every { isOverloaded } returns (s == High)
    every { lastReportedStressLevel } returns s.stress
    every { relayId } returns "dummy"
    every { this@mockk.toString() } returns "MockBridge[region=$region, stress=$stress]"
}

// Create a Low, Medium and High stress bridge in each region.
private fun createBridges() = RegionToBridgesMap(mutableMapOf()).apply {
    Regions.values().forEach { r ->
        put(r, StressToBridgeMap(StressLevels.values().associateWith { mockBridge(r, it) }))
    }
}

// Hide the "!!" here for convenience.
private class RegionToBridgesMap(
    private val m: MutableMap<Regions, StressToBridgeMap>
) : MutableMap<Regions, StressToBridgeMap> by m {
    override fun get(key: Regions): StressToBridgeMap = m[key]!!
}

// Hide the "!!" here for convenience.
private class StressToBridgeMap(private val m: Map<StressLevels, Bridge>) : Map<StressLevels, Bridge> by m {
    override fun get(key: StressLevels): Bridge = m[key]!!
}

private enum class Regions(val region: String) {
    ApSouth("ap-south"),
    EuCentral("eu-central"),
    EuWest("eu-west"),
    UsEast("us-east"),
    UsWest("us-west")
}

private enum class StressLevels(val stress: Double) {
    Low(0.0),
    Medium(0.3),
    High(0.9)
}
