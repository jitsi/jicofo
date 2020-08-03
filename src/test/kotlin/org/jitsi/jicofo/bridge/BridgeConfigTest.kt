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

import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.ConfigTest
import org.jitsi.jicofo.bridge.BridgeConfig.Companion.config

class BridgeConfigTest : ConfigTest() {
    init {
        context("with no config the defaults from reference.conf should be used") {
            config.maxBridgeParticipants shouldBe -1
            config.maxBridgePacketRatePps shouldBe 50000
            config.averageParticipantPacketRatePps shouldBe 500
        }
        context("with legacy config") {
            withLegacyConfig(legacyConfig) {
                config.maxBridgeParticipants shouldBe 111
                config.maxBridgePacketRatePps shouldBe 111
                config.averageParticipantPacketRatePps shouldBe 111
            }
        }
        context("with new config") {
            withNewConfig(newConfig) {
                config.maxBridgeParticipants shouldBe 222
                config.maxBridgePacketRatePps shouldBe 222
                config.averageParticipantPacketRatePps shouldBe 222
            }
        }
        context("with both legacy and new config the legacy values should be used") {
            withLegacyConfig(legacyConfig) {
                withNewConfig(newConfig) {
                    config.maxBridgeParticipants shouldBe 111
                    config.maxBridgePacketRatePps shouldBe 111
                    config.averageParticipantPacketRatePps shouldBe 111
                }
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
        max-bridge-packet-rate=222
        average-participant-packet-rate-pps=222
    }
}    
""".trimIndent()
