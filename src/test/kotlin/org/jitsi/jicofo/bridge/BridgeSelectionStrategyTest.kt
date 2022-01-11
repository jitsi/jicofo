/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jxmpp.jid.impl.JidCreate

class BridgeSelectionStrategyTest : ShouldSpec() {
    init {
        val strategy: BridgeSelectionStrategy = RegionBasedBridgeSelectionStrategy()

        context("testRegionBasedSelection") {
            val region1 = "region1"
            val region2 = "region2"
            val region3 = "region3"
            val bridge1 = Bridge(JidCreate.from("bridge1")).apply { setStats(region = region1) }
            val bridge2 = Bridge(JidCreate.from("bridge2")).apply { setStats(region = region2) }
            val bridge3 = Bridge(JidCreate.from("bridge3")).apply { setStats(region = region3) }

            val allBridges = listOf(bridge1, bridge2, bridge3)
            val conferenceBridges: MutableMap<Bridge, Int> = HashMap()

            // Initial selection should select a bridge in the participant's region/ if possible
            strategy.select(allBridges, conferenceBridges, region1, true) shouldBe bridge1
            strategy.select(allBridges, conferenceBridges, region2, true) shouldBe bridge2

            // Or a bridge in the local region otherwise
            // This is not actually implemented.
            // val localBridge = bridge1
            // strategy.select(allBridges, conferenceBridges, "invalid region", true) shouldBe localBridge
            // strategy.select(allBridges, conferenceBridges, null, true) shouldBe localBridge

            conferenceBridges[bridge3] = 1
            strategy.select(allBridges, conferenceBridges, region3, true) shouldBe bridge3
            strategy.select(allBridges, conferenceBridges, region2, true) shouldBe bridge2
            // A participant in an unknown region should be allocated on the existing conference bridge.
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe bridge3

            conferenceBridges[bridge2] = 1
            // A participant in an unknown region should be allocated on the least loaded (according to the order of
            // 'allBridges') existing conference bridge.
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe bridge2

            // A participant in a region with no bridges should also be allocated on the least loaded (according to the
            // order of 'allBridges') existing conference bridge.
            strategy.select(allBridges, conferenceBridges, "invalid region", true) shouldBe bridge2
        }
        context("Port of BridgeSelectionStrategyTest.java#preferLowestStress") {
            val lowStressRegion = "lowStressRegion"
            val mediumStressRegion = "mediumStressRegion"
            val highStressRegion = "highStressRegion"

            val lowStressBridge = createBridge(lowStressRegion, 0.1)
            val mediumStressBridge = createBridge(mediumStressRegion, 0.3)
            val highStressBridge = createBridge(highStressRegion, 0.8)
            val allBridges = listOf(lowStressBridge, mediumStressBridge, highStressBridge)

            val conferenceBridges = mutableMapOf<Bridge, Int>()
            // Initial selection should select a bridge in the participant's region.
            strategy.select(allBridges, conferenceBridges, highStressRegion, true) shouldBe highStressBridge
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true) shouldBe mediumStressBridge
            strategy.select(allBridges, conferenceBridges, "invalid region", true) shouldBe lowStressBridge

            strategy.select(allBridges, conferenceBridges, null, true) shouldBe lowStressBridge

            // Now assume that the low-stressed bridge is in the conference.
            conferenceBridges[lowStressBridge] = 1
            strategy.select(allBridges, conferenceBridges, lowStressRegion, true) shouldBe lowStressBridge
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true) shouldBe mediumStressBridge
            // A participant in an unknown region should be allocated on the
            // existing conference bridge.
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe lowStressBridge

            // Now assume that a medium-stressed bridge is also in the conference.
            conferenceBridges[mediumStressBridge] = 1
            // A participant in an unknown region should be allocated on the least
            // loaded (according to the order of 'allBridges') existing conference
            // bridge.
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe lowStressBridge
            // A participant in a region with no bridges should also be allocated
            // on the least loaded (according to the order of 'allBridges') existing
            // conference bridge.
            strategy.select(allBridges, conferenceBridges, "invalid region", true) shouldBe lowStressBridge
        }
        context("Port of BridgeSelectionStrategyTest.java#preferRegionWhenStressIsEqual") {
            // Here we specify 3 bridges in 3 different regions: one high-stressed and two medium-stressed.
            val mediumStressRegion1 = "mediumStressRegion1"
            val mediumStressRegion2 = "mediumStressRegion2"
            val highStressRegion = "highStressRegion"

            val mediumStressBridge1 = createBridge(mediumStressRegion1, 0.25)
            val mediumStressBridge2 = createBridge(mediumStressRegion2, 0.3)
            val highStressBridge = createBridge(highStressRegion, 0.8)
            val allBridges = listOf(mediumStressBridge1, mediumStressBridge2, highStressBridge)

            val conferenceBridges = mutableMapOf<Bridge, Int>()

            // Initial selection should select a bridge in the participant's region.
            strategy.select(allBridges, conferenceBridges, highStressRegion, true) shouldBe highStressBridge
            strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true) shouldBe mediumStressBridge2
            strategy.select(allBridges, conferenceBridges, "invalid region", true) shouldBe mediumStressBridge1
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe mediumStressBridge1

            conferenceBridges[mediumStressBridge2] = 1
            strategy.select(allBridges, conferenceBridges, mediumStressRegion1, true) shouldBe mediumStressBridge1
            strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true) shouldBe mediumStressBridge2
            // A participant in an unknown region should be allocated on the existing conference bridge.
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe mediumStressBridge2

            // Now assume that a high-stressed bridge is in the conference.
            conferenceBridges[highStressBridge] = 1
            // A participant in an unknown region should be allocated on the least
            // loaded (according to the order of 'allBridges') existing conference
            // bridge.
            strategy.select(allBridges, conferenceBridges, null, true) shouldBe mediumStressBridge2
            // A participant in a region with no bridges should also be allocated
            // on the least loaded (according to the order of 'allBridges') existing
            // conference bridge.
            strategy.select(allBridges, conferenceBridges, "invalid region", true) shouldBe mediumStressBridge2
        }
    }
}

private fun createBridge(region: String, stress: Double) = Bridge(JidCreate.from(region)).apply {
    setStats(stress = stress, region = region)
}
