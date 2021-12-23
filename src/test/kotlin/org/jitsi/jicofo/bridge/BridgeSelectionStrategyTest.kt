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

class BridgeSelectionStrategy2Test : ShouldSpec() {
    init {
        context("testRegionBasedSelection") {
            val region1 = "region1"
            val region2 = "region2"
            val region3 = "region3"
            val bridge1 = Bridge(JidCreate.from("bridge1")).apply { setStats(region = region1) }
            val bridge2 = Bridge(JidCreate.from("bridge2")).apply { setStats(region = region2) }
            val bridge3 = Bridge(JidCreate.from("bridge3")).apply { setStats(region = region3) }

            val strategy: BridgeSelectionStrategy = RegionBasedBridgeSelectionStrategy()

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
    }
}
