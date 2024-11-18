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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.config.withLegacyConfig
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.bridge.BridgeConfig.Companion.config
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.utils.mins

class BridgeConfigTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        MetaconfigSettings.cacheEnabled = false
        context("with no config the defaults from reference.conf should be used") {
            config.maxBridgeParticipants shouldBe 80
        }
        context("with legacy config") {
            withLegacyConfig(legacyConfig) {
                config.maxBridgeParticipants shouldBe 111
            }
        }
        context("with new config") {
            withNewConfig(newConfig) {
                config.maxBridgeParticipants shouldBe 222
            }
        }
        context("with both legacy and new config the legacy values should be used") {
            withLegacyConfig(legacyConfig) {
                withNewConfig(newConfig) {
                    config.maxBridgeParticipants shouldBe 111
                }
            }
        }
        context("Selection strategy") {
            context("With default config") {
                config.selectionStrategy.shouldBeInstanceOf<SingleBridgeSelectionStrategy>()
            }
            context("With legacy config") {
                withLegacyConfig(
                    "org.jitsi.jicofo.BridgeSelector.BRIDGE_SELECTION_STRATEGY=SplitBridgeSelectionStrategy"
                ) {
                    config.selectionStrategy.shouldBeInstanceOf<SplitBridgeSelectionStrategy>()
                }
            }
            context("With new config") {
                withNewConfig("jicofo.bridge.selection-strategy=RegionBasedBridgeSelectionStrategy") {
                    config.selectionStrategy.shouldBeInstanceOf<RegionBasedBridgeSelectionStrategy>()
                }
            }
        }
        context("Health checks") {
            withNewConfig("jicofo.bridge.health-checks.interval = 2 minutes".trimIndent()) {
                config.healthChecksInterval shouldBe 2.mins
                config.healthChecksRetryDelay shouldBe 1.mins
            }
        }
        context("Region groups") {
            config.regionGroups shouldBe emptyMap()

            withNewConfig(
                """
                jicofo.bridge.region-groups = [
                    [ "us-east", "us-west" ],
                    [ "eu-central", "eu-west"]
                ]
                """.trimIndent()
            ) {
                config.regionGroups shouldBe mapOf(
                    "us-east" to setOf("us-east", "us-west"),
                    "us-west" to setOf("us-east", "us-west"),
                    "eu-central" to setOf("eu-central", "eu-west"),
                    "eu-west" to setOf("eu-central", "eu-west")
                )
                config.getRegionGroup(null) shouldBe emptySet()
                config.getRegionGroup("abc") shouldBe setOf("abc")
                config.getRegionGroup("us-east") shouldBe setOf("us-east", "us-west")
            }
        }
    }
}
private val legacyConfig = """
org.jitsi.jicofo.BridgeSelector.MAX_PARTICIPANTS_PER_BRIDGE=111
org.jitsi.jicofo.BridgeSelector.MAX_BRIDGE_PACKET_RATE=111
org.jitsi.jicofo.BridgeSelector.AVG_PARTICIPANT_PACKET_RATE=111
""".trimIndent()

private val newConfig = """
jicofo {
    bridge {
        max-bridge-participants=222
        average-participant-packet-rate-pps=222
    }
}    
""".trimIndent()
