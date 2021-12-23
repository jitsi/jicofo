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
import org.jitsi.test.time.FakeClock
import org.jxmpp.jid.impl.JidCreate

class BridgeSelectorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val clock = FakeClock()
        val bridgeSelector = BridgeSelector(clock)
        // Test different types of jid (domain, entity bare, entity full).
        val jvb1 = bridgeSelector.addJvbAddress(JidCreate.from("jvb.example.com"))
        val jvb2 = bridgeSelector.addJvbAddress(JidCreate.from("jvb@example.com"))
        val jvb3 = bridgeSelector.addJvbAddress(JidCreate.from("jvb@example.com/goldengate"))

        context("Selection based on operational status") {
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
            // Jvb 1 and 2 are occupied by some conferences, 3 is free
            jvb1.setStats(stress = .1)
            jvb2.setStats(stress = 0.23)
            jvb3.setStats(stress = 0.0)

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
    }
}
