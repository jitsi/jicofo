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
import org.jitsi.config.withNewConfig
import org.jxmpp.jid.impl.JidCreate

class BridgeSelectionStrategyTest : ShouldSpec() {
    init {
        val localRegion = "local-region"
        val strategy: RegionBasedBridgeSelectionStrategy = createWithNewConfig("jicofo.local-region=$localRegion") {
            RegionBasedBridgeSelectionStrategy()
        }

        context("testRegionBasedSelection") {
            val region1 = "region1"
            val region2 = localRegion
            val region3 = "region3"
            val bridge1 = Bridge(JidCreate.from("bridge1")).apply { setStats(region = region1) }
            val bridge2 = Bridge(JidCreate.from("bridge2")).apply { setStats(region = region2) }
            val bridge3 = Bridge(JidCreate.from("bridge3")).apply { setStats(region = region3) }
            val localBridge = bridge2

            val props1 = ParticipantProperties(region1)
            val props2 = ParticipantProperties(region2)
            val props3 = ParticipantProperties(region3)

            val propsInvalid = ParticipantProperties("invalid region")
            val propsNull = ParticipantProperties(null)

            val allBridges = listOf(bridge1, bridge2, bridge3)
            val conferenceBridges: MutableMap<Bridge, ConferenceBridgeProperties> = HashMap()

            // Initial selection should select a bridge in the participant's region/ if possible
            strategy.select(allBridges, conferenceBridges, props1, true) shouldBe bridge1
            strategy.select(allBridges, conferenceBridges, props2, true) shouldBe bridge2

            // Or a bridge in the local region otherwise
            strategy.select(allBridges, conferenceBridges, propsInvalid, true) shouldBe localBridge
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe localBridge

            conferenceBridges[bridge3] = ConferenceBridgeProperties(1)
            strategy.select(allBridges, conferenceBridges, props3, true) shouldBe bridge3
            strategy.select(allBridges, conferenceBridges, props2, true) shouldBe bridge2
            // A participant in an unknown region should be allocated on a local bridge.
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe localBridge

            conferenceBridges[bridge2] = ConferenceBridgeProperties(1)
            // A participant in an unknown region should be allocated on the least loaded (according to the order of
            // 'allBridges') existing conference bridge.
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe bridge2

            // A participant in a region with no bridges should also be allocated on the least loaded (according to the
            // order of 'allBridges') existing conference bridge.
            strategy.select(allBridges, conferenceBridges, propsInvalid, true) shouldBe bridge2
        }
        context("Port of BridgeSelectionStrategyTest.java#preferLowestStress") {
            val lowStressRegion = "lowStressRegion"
            val mediumStressRegion = "mediumStressRegion"
            val highStressRegion = "highStressRegion"

            val lowStressBridge = createBridge(lowStressRegion, 0.1)
            val mediumStressBridge = createBridge(mediumStressRegion, 0.3)
            val highStressBridge = createBridge(highStressRegion, 0.8)
            val allBridges = listOf(lowStressBridge, mediumStressBridge, highStressBridge)

            val lowStressProps = ParticipantProperties(lowStressRegion)
            val mediumStressProps = ParticipantProperties(mediumStressRegion)
            val highStressProps = ParticipantProperties(highStressRegion)

            val propsInvalid = ParticipantProperties("invalid region")
            val propsNull = ParticipantProperties(null)

            val conferenceBridges = mutableMapOf<Bridge, ConferenceBridgeProperties>()
            // Initial selection should select a non-overloaded bridge in the participant's region if possible. If not,
            // it should select the lowest loaded bridge.
            strategy.select(allBridges, conferenceBridges, highStressProps, true) shouldBe lowStressBridge
            strategy.select(allBridges, conferenceBridges, mediumStressProps, true) shouldBe mediumStressBridge
            strategy.select(allBridges, conferenceBridges, propsInvalid, true) shouldBe lowStressBridge

            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe lowStressBridge

            // Now assume that the low-stressed bridge is in the conference.
            conferenceBridges[lowStressBridge] = ConferenceBridgeProperties(1)
            strategy.select(allBridges, conferenceBridges, lowStressProps, true) shouldBe lowStressBridge
            strategy.select(allBridges, conferenceBridges, mediumStressProps, true) shouldBe mediumStressBridge
            // A participant in an unknown region should be allocated on the
            // existing conference bridge.
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe lowStressBridge

            // Now assume that a medium-stressed bridge is also in the conference.
            conferenceBridges[mediumStressBridge] = ConferenceBridgeProperties(1)
            // A participant in an unknown region should be allocated on the least
            // loaded (according to the order of 'allBridges') existing conference
            // bridge.
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe lowStressBridge
            // A participant in a region with no bridges should also be allocated
            // on the least loaded (according to the order of 'allBridges') existing
            // conference bridge.
            strategy.select(allBridges, conferenceBridges, propsInvalid, true) shouldBe lowStressBridge
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

            val mediumStressProps1 = ParticipantProperties(mediumStressRegion1)
            val mediumStressProps2 = ParticipantProperties(mediumStressRegion2)
            val highStressProps = ParticipantProperties(highStressRegion)

            val propsInvalid = ParticipantProperties("invalid region")
            val propsNull = ParticipantProperties(null)

            val conferenceBridges = mutableMapOf<Bridge, ConferenceBridgeProperties>()

            // Initial selection should select a non-overloaded bridge in the participant's region if possible. If not,
            // it should select the lowest loaded bridge.
            strategy.select(allBridges, conferenceBridges, highStressProps, true) shouldBe mediumStressBridge1
            strategy.select(allBridges, conferenceBridges, mediumStressProps2, true) shouldBe mediumStressBridge2
            strategy.select(allBridges, conferenceBridges, propsInvalid, true) shouldBe mediumStressBridge1
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe mediumStressBridge1

            conferenceBridges[mediumStressBridge2] = ConferenceBridgeProperties(1)
            strategy.select(allBridges, conferenceBridges, mediumStressProps1, true) shouldBe mediumStressBridge1
            strategy.select(allBridges, conferenceBridges, mediumStressProps2, true) shouldBe mediumStressBridge2
            // A participant in an unknown region should be allocated on the existing conference bridge.
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe mediumStressBridge2

            // Now assume that a high-stressed bridge is in the conference.
            conferenceBridges[highStressBridge] = ConferenceBridgeProperties(1)
            // A participant in an unknown region should be allocated on the least
            // loaded (according to the order of 'allBridges') existing conference
            // bridge.
            strategy.select(allBridges, conferenceBridges, propsNull, true) shouldBe mediumStressBridge2
            // A participant in a region with no bridges should also be allocated
            // on the least loaded (according to the order of 'allBridges') existing
            // conference bridge.
            strategy.select(allBridges, conferenceBridges, propsInvalid, true) shouldBe mediumStressBridge2
        }
    }
}

private fun createBridge(region: String, stress: Double) = Bridge(JidCreate.from(region)).apply {
    setStats(stress = stress, region = region)
}

private fun <T : Any> createWithNewConfig(config: String, block: () -> T): T {
    lateinit var ret: T
    withNewConfig(config) {
        ret = block()
    }
    return ret
}
