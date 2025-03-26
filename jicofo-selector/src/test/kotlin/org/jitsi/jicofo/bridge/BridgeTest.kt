/*
 * Copyright @ 2020 - present 8x8, Inc.
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
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.times
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jxmpp.jid.impl.JidCreate

class BridgeTest : ShouldSpec({
    context("when comparing two bridges") {
        should("the bridge that is operational should have higher priority (should compare lower)") {
            val bridge1: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { correctedStress } returns 10.0
            }

            val bridge2: Bridge = mockk {
                every { isOperational } returns false
                every { isInGracefulShutdown } returns true
                every { correctedStress } returns .1
            }
            Bridge.compare(bridge1, bridge2) shouldBeLessThan 0
        }

        should("the bridge that is in graceful shutdown mode should have lower priority (should compare higher)") {
            val bridge1: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { correctedStress } returns 10.0
            }

            val bridge2: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns true
                every { correctedStress } returns .1
            }
            Bridge.compare(bridge1, bridge2) shouldBeLessThan 0
        }

        should("the bridge that is stressed should have lower priority (should compare higher)") {
            val bridge1: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { correctedStress } returns 10.0
            }

            val bridge2: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { correctedStress } returns .1
            }
            Bridge.compare(bridge2, bridge1) shouldBeLessThan 0
        }
    }
    context("notOperationalThresholdTest") {
        val clock = FakeClock()
        val bridge = Bridge(JidCreate.from("bridge"), clock)
        val failureResetThreshold = BridgeConfig.config.failureResetThreshold
        bridge.isOperational shouldBe true

        bridge.isOperational = false
        bridge.isOperational shouldBe false

        clock.elapse(failureResetThreshold.times(100))
        bridge.isOperational shouldBe false

        bridge.isOperational = true
        bridge.isOperational shouldBe true

        bridge.isOperational = false
        bridge.isOperational shouldBe false

        clock.elapse(failureResetThreshold.dividedBy(2))
        bridge.isOperational shouldBe false

        bridge.isOperational = true
        bridge.isOperational shouldBe false

        clock.elapse(failureResetThreshold)
        bridge.isOperational shouldBe true
    }
    context("Setting stats") {
        // This mostly makes sure the test framework works as expected.
        val bridge = Bridge(JidCreate.from("bridge"))

        bridge.correctedStress shouldBe 0
        bridge.region shouldBe null

        bridge.setStats(stress = 0.1)
        bridge.correctedStress shouldBe 0.1
        bridge.region shouldBe null

        // The different stats should be updated independently.
        bridge.setStats(region = "region")
        bridge.correctedStress shouldBe 0.1
        bridge.region shouldBe "region"

        // The different stats should be updated independently.
        bridge.setStats(stress = 0.2)
        bridge.correctedStress shouldBe 0.2
        bridge.region shouldBe "region"
    }
})

fun Bridge.setStats(
    stress: Double? = null,
    region: String? = null,
    relayId: String? = region,
    version: String? = null,
    colibri2: Boolean = true,
    gracefulShutdown: Boolean = false,
    drain: Boolean = false
) = setStats(
    ColibriStatsExtension().apply {
        stress?.let { addStat(ColibriStatsExtension.Stat("stress_level", it)) }
        region?.let {
            addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.REGION, it))
        }
        relayId?.let {
            addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.RELAY_ID, it))
        }
        version?.let { addStat(ColibriStatsExtension.Stat("version", version)) }
        if (colibri2) addStat("colibri2", "true")
        if (gracefulShutdown) addStat(ColibriStatsExtension.SHUTDOWN_IN_PROGRESS, "true")
        addStat(ColibriStatsExtension.DRAIN, if (drain) "true" else "false")
    }
)
