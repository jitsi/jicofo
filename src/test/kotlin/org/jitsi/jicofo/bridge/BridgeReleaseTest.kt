/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022-Present 8x8, Inc.
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
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.setNewConfig
import org.jitsi.test.time.FakeClock
import org.jxmpp.jid.impl.JidCreate

/**
 * This test simulates the intended procedure to upgrade the set of bridges connected to a jicofo instance, and
 * verifies the selection logic.
 */
class BridgeReleaseTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.SingleInstance

    val clock = FakeClock()

    override suspend fun afterSpec(spec: Spec) = super.afterSpec(spec).also {
        setNewConfig("", true)
    }
    init {
        context("Test") {
            // This config is required for the whole test. Set it here instead of in [beforeSpec] because this executes
            // earlier.
            setNewConfig(
                """
                $regionBasedConfig
                jicofo.bridge.max-bridge-participants=$maxBp
                """.trimIndent(),
                true
            )

            BridgeConfig.config.maxBridgeParticipants shouldBe maxBp

            val selector = BridgeSelector(clock)
            // We start with bridges from a single "old" release.
            val old1 = selector.createBridge("old1", oldVersion, 0.1)
            val old2 = selector.createBridge("old2", oldVersion, 0.2)
            val old3 = selector.createBridge("old3", oldVersion, 0.3)

            // Initially we only have bridges from the "old" release. Verify the basics.
            // Select the least loaded bridge
            selector.testSelect() shouldBe old1
            selector.testSelect(version = oldVersion) shouldBe old1
            selector.testSelect(mapOf(old1 to 1)) shouldBe old1
            // Select an existing conference bridge
            selector.testSelect(mapOf(old2 to 1)) shouldBe old2
            selector.testSelect(mapOf(old3 to 1)) shouldBe old3
            selector.testSelect(mapOf(old2 to 2, old3 to 1)) shouldBe old2
            // Fail if the version doesn't match
            selector.testSelect(version = "invalid-version") shouldBe null
            // Fail with inconsistent version pinning
            selector.testSelect(mapOf(old1 to 1), version = newVersion) shouldBe null
            // Honor max-participants-per-bridge
            selector.testSelect(mapOf(old1 to maxBp)) shouldBe old2
            selector.testSelect(mapOf(old1 to maxBp, old3 to 1)) shouldBe old3
            selector.testSelect(mapOf(old1 to maxBp, old3 to maxBp)) shouldBe old2
            // Select the least loaded if all are full
            selector.testSelect(mapOf(old1 to maxBp, old2 to maxBp, old3 to maxBp)) shouldBe old1

            // We add a new bridge with a new release.
            val new1 = selector.createBridge("new1", newVersion, 0.0, drain = true)
            // An old one should be used even though the new one has stress 0, because the new one is drained.
            selector.testSelect() shouldBe old1
            selector.testSelect(version = newVersion) shouldBe new1
            selector.testSelect(mapOf(new1 to 1)) shouldBe new1
            new1.setStats(stress = 0.3, drain = true)
            // And more bridges with a new release.
            val new2 = selector.createBridge("new2", newVersion, 0.2, drain = true)
            val new3 = selector.createBridge("new3", newVersion, 0.1, drain = true)
            selector.testSelect(version = newVersion) shouldBe new3
            selector.testSelect(mapOf(new2 to 1)) shouldBe new2
            selector.testSelect(mapOf(new1 to 1)) shouldBe new1
            selector.testSelect(mapOf(new1 to 1), version = newVersion) shouldBe new1
            selector.testSelect(mapOf(new2 to 1)) shouldBe new2
            selector.testSelect(mapOf(new2 to 1), version = newVersion) shouldBe new2
            selector.testSelect(mapOf(new3 to 1)) shouldBe new3
            selector.testSelect(mapOf(new3 to 1), version = newVersion) shouldBe new3
            selector.testSelect(mapOf(new1 to 1), version = oldVersion) shouldBe null
            // Honor max-participants-per-bridge, even though all new bridges are in drain.
            selector.testSelect(mapOf(new1 to maxBp)) shouldBe new3
            selector.testSelect(mapOf(new1 to maxBp, new2 to 1)) shouldBe new2
            selector.testSelect(mapOf(new1 to maxBp, new2 to maxBp)) shouldBe new3
            selector.testSelect(mapOf(new1 to maxBp, new2 to maxBp, new3 to maxBp)) shouldBe new3

            // Everything should work the same unless a conference is pinned to the new version.
            // Select the least loaded bridge
            selector.testSelect() shouldBe old1
            selector.testSelect(version = oldVersion) shouldBe old1
            selector.testSelect(mapOf(old1 to 1)) shouldBe old1
            // Select an existing conference bridge
            selector.testSelect(mapOf(old2 to 1)) shouldBe old2
            selector.testSelect(mapOf(old3 to 1)) shouldBe old3
            selector.testSelect(mapOf(old2 to 2, old3 to 1)) shouldBe old2
            // Fail if the version doesn't match
            selector.testSelect(version = "invalid-version") shouldBe null
            // Fail with inconsistent version pinning
            selector.testSelect(mapOf(old1 to 1), version = newVersion) shouldBe null
            // Honor max-participants-per-bridge
            selector.testSelect(mapOf(old1 to maxBp)) shouldBe old2
            selector.testSelect(mapOf(old1 to maxBp, old3 to 1)) shouldBe old3
            selector.testSelect(mapOf(old1 to maxBp, old3 to maxBp)) shouldBe old2
            selector.testSelect(mapOf(old1 to maxBp, old2 to maxBp, old3 to maxBp)) shouldBe old1

            // Switch the releases
            setOf(old1, old2, old3).forEach { it.setStats(drain = true) }
            setOf(new1, new2, new3).forEach { it.setStats(drain = false) }

            // Select the new version for new conferences
            old1.setStats(stress = 0.0, drain = true)
            // old1 should not be selected because it is in drain
            selector.testSelect() shouldBe new3
            selector.testSelect(mapOf(new3 to maxBp)) shouldBe new2
            // Select the old version for existing conferences or pinned
            selector.testSelect(mapOf(old1 to 1)) shouldBe old1
            // Should select old2 even though all old bridges are in drain.
            selector.testSelect(mapOf(old1 to maxBp)) shouldBe old2
            selector.testSelect(version = oldVersion) shouldBe old1
        }
    }
}

private fun BridgeSelector.createBridge(jid: String, version: String, stress: Double, drain: Boolean = false) =
    addJvbAddress(JidCreate.from(jid)).apply {
        setStats(
            version = version,
            stress = stress,
            drain = drain,
            region = "region",
            relayId = jid
        )
    }

private val maxBp = 80
private const val oldVersion = "old"
private const val newVersion = "new"

private fun BridgeSelector.testSelect(
    conferenceBridges: Map<Bridge, Int> = emptyMap(),
    version: String? = null
) = selectBridge(conferenceBridges, participantRegion = "region", version = version)
