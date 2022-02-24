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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.util.context
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.test.time.FakeClock
import org.jxmpp.jid.impl.JidCreate

class BridgeSelectorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val clock = FakeClock()
        // Test different types of jid (domain, entity bare, entity full).
        val jid1 = JidCreate.from("jvb1.example.com")
        val jid2 = JidCreate.from("jvb2@example.com")
        val jid3 = JidCreate.from("jvb3@example.com/goldengate")

        context("Selection based on operational status") {
            val bridgeSelector = BridgeSelector(clock)
            val jvb1 = bridgeSelector.addJvbAddress(jid1).apply { setStats() }
            val jvb2 = bridgeSelector.addJvbAddress(jid2).apply { setStats() }
            val jvb3 = bridgeSelector.addJvbAddress(jid3).apply { setStats() }

            bridgeSelector.selectBridge() shouldBeIn setOf(jvb1, jvb2, jvb3)

            // Bridge 1 is down
            jvb1.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBeIn setOf(jvb2, jvb3)

            // Bridge 2 is down
            jvb2.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBe jvb3

            // Bridge 1 is up again, but 3 is down instead
            jvb1.setIsOperational(true)
            jvb3.setIsOperational(false)
            // We need to elapse time after setting isOperational=true because isOperational=false is sticky
            clock.elapse(BridgeConfig.config.failureResetThreshold)
            bridgeSelector.selectBridge() shouldBe jvb1
        }
        context("Selection based on stress level") {
            val bridgeSelector = BridgeSelector(clock)
            val jvb1 = bridgeSelector.addJvbAddress(jid1).apply { setStats(stress = 0.1) }
            val jvb2 = bridgeSelector.addJvbAddress(jid2).apply { setStats(stress = 0.23) }
            val jvb3 = bridgeSelector.addJvbAddress(jid3).apply { setStats(stress = 0.0) }

            bridgeSelector.selectBridge() shouldBe jvb3

            // Now Jvb 3 gets occupied the most
            jvb3.setStats(stress = 0.3)
            bridgeSelector.selectBridge() shouldBe jvb1

            // Jvb 1 is gone
            jvb1.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBe jvb2

            // All bridges down
            jvb2.setIsOperational(false)
            jvb3.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBe null

            jvb1.setIsOperational(true)
            jvb2.setIsOperational(true)
            jvb3.setIsOperational(true)
            // We need to elapse time after setting isOperational=true because isOperational=false is sticky
            clock.elapse(BridgeConfig.config.failureResetThreshold)

            jvb1.setStats(stress = .01)
            jvb2.setStats(stress = 0.0)
            jvb3.setStats(stress = 0.0)
            bridgeSelector.selectBridge() shouldBeIn setOf(jvb2, jvb3)

            // JVB 2 least occupied
            jvb1.setStats(stress = .01)
            jvb2.setStats(stress = 0.0)
            jvb3.setStats(stress = .01)
            bridgeSelector.selectBridge() shouldBe jvb2
        }
        context(config = regionBasedConfig, name = "Mixing versions") {
            val bridgeSelector = BridgeSelector(clock)
            val jvb1 = bridgeSelector.addJvbAddress(jid1).apply { setStats(version = "v1", stress = 0.9, region = "r") }
            val jvb2 = bridgeSelector.addJvbAddress(jid2).apply { setStats(version = "v2", stress = 0.1, region = "r") }

            context("With explicit API call") {
                bridgeSelector.selectBridge(version = "v1") shouldBe jvb1
                bridgeSelector.selectBridge(version = "v2") shouldBe jvb2
                bridgeSelector.selectBridge(version = "v-nonexistent") shouldBe null
            }
            context("From an existing conference bridge") {
                bridgeSelector.selectBridge(conferenceBridges = mapOf(jvb1 to 1)) shouldBe jvb1
                val jvb3 = bridgeSelector.addJvbAddress(jid3).apply {
                    setStats(version = "v1", stress = 0.1, region = "r")
                }
                bridgeSelector.selectBridge(conferenceBridges = mapOf(jvb1 to 1)) shouldBe jvb3
            }
        }
        context(config = regionBasedConfig, name = "Selection with a conference bridge removed from the selector") {
            val regionBasedSelector = BridgeSelector(clock)
            val jvb1 = regionBasedSelector.addJvbAddress(jid1).apply { setStats(stress = 0.2, region = "r1") }
            val jvb2 = regionBasedSelector.addJvbAddress(jid2).apply { setStats(stress = 0.5, region = "r2") }
            val jvb3 = regionBasedSelector.addJvbAddress(jid3).apply { setStats(stress = 0.1, region = "r3") }

            regionBasedSelector.removeJvbAddress(jid3)

            regionBasedSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 1, jvb3 to 1), null) shouldBe jvb1
            regionBasedSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 1, jvb3 to 1), "r2") shouldBe jvb2
        }
        context(config = splitConfig, name = "SplitBridgeSelectionStrategy") {
            val splitSelector = BridgeSelector(clock)
            val jvb1 = splitSelector.addJvbAddress(jid1).apply { setStats(stress = 0.2, region = "r1") }
            val jvb2 = splitSelector.addJvbAddress(jid2).apply { setStats(stress = 0.5, region = "r2") }
            val jvb3 = splitSelector.addJvbAddress(jid3).apply { setStats(stress = 0.1, region = "r3") }

            splitSelector.selectBridge() shouldBeIn setOf(jvb1, jvb2, jvb3)
            splitSelector.selectBridge(mapOf(jvb1 to 1), null) shouldBeIn setOf(jvb2, jvb3)
            splitSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 1), null) shouldBe jvb3
            splitSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 2, jvb3 to 3), null) shouldBe jvb1

            splitSelector.removeJvbAddress(jid1)

            splitSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 2, jvb3 to 3), null) shouldBe jvb2
        }
        context(config = colibri2Config, "Colibri2 support") {
            val selector = BridgeSelector(clock)
            val jvb1 = selector.addJvbAddress(jid1).apply { setStats(stress = 0.2, colibri2 = false) }
            selector.selectBridge() shouldBe null

            val jvb2 = selector.addJvbAddress(jid2).apply { setStats(stress = 0.9) }
            selector.selectBridge() shouldBe jvb2

            jvb2.setStats(stress = 0.9, gracefulShutdown = true)
            selector.selectBridge() shouldBe jvb2
        }
        context("Lost bridges stats") {
            val selector = BridgeSelector(clock)
            selector.lostBridges() shouldBe 0

            val jvb1 = selector.addJvbAddress(jid1)
            selector.lostBridges() shouldBe 0

            should("Increment the lost bridges stat when a bridge goes away") {
                selector.removeJvbAddress(jvb1.jid)
                selector.lostBridges() shouldBe 1
            }
            should("Not increment the lost bridges stat when a bridge in graceful-shutdown goes away") {
                jvb1.setStats(gracefulShutdown = true)
                selector.removeJvbAddress(jvb1.jid)
                selector.lostBridges() shouldBe 0
            }
        }
        xcontext("Performance") {
            withNewConfig(regionGroupsConfig) {
                // Config caching has a huge impact!
                MetaconfigSettings.cacheEnabled = true

                val numBridges = 500
                val times = 1000

                val selector = BridgeSelector(clock)
                for (i in 1..numBridges) {
                    selector.addJvbAddress(JidCreate.from("jvb-$i")).apply {
                        // Force the worst-case in RegionBasedBridgeSelectionStrategy (the last "least loaded" branch).
                        setStats(stress = 0.99, region = "bridge-region")
                    }
                }

                repeat(10) {
                    val start = System.currentTimeMillis()
                    for (i in 0..times) {
                        selector.selectBridge(participantRegion = "participant-region-no-match")
                    }
                    val end = System.currentTimeMillis()
                    val avgNs = (end - start) * 1_000_000.toDouble() / times
                    println("Took ${end - start} ms to select $times times (an average of $avgNs ns per selection)")
                }
            }
        }
    }
}

private const val enableOctoConfig = "jicofo.octo.enabled=true"
val regionBasedConfig = """
    $enableOctoConfig
    jicofo.bridge.selection-strategy=RegionBasedBridgeSelectionStrategy
""".trimIndent()
private val splitConfig = """
    $enableOctoConfig
    jicofo.bridge.selection-strategy=SplitBridgeSelectionStrategy
""".trimIndent()

private val colibri2Config = "jicofo.colibri.enable-colibri2=true"
