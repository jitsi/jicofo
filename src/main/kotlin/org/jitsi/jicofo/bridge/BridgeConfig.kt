/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

/**
 * Config for classes in the org.jicofo.bridge package.
 */
class BridgeConfig {
    val maxBridgeParticipants: Int by config {
        "org.jitsi.jicofo.BridgeSelector.MAX_PARTICIPANTS_PER_BRIDGE".from(JitsiConfig.legacyConfig)
        "$BASE.max-bridge-participants".from(JitsiConfig.newConfig)
    }
    fun maxBridgeParticipants() = maxBridgeParticipants

    val maxBridgePacketRatePps: Int by config {
        "org.jitsi.jicofo.BridgeSelector.MAX_BRIDGE_PACKET_RATE".from(JitsiConfig.legacyConfig)
        "$BASE.max-bridge-packet-rate".from(JitsiConfig.newConfig)
    }
    fun maxBridgePacketRatePps() = maxBridgePacketRatePps

    val averageParticipantPacketRatePps: Int by config {
        "org.jitsi.jicofo.BridgeSelector.AVG_PARTICIPANT_PACKET_RATE".from(JitsiConfig.legacyConfig)
        "$BASE.average-participant-packet-rate-pps".from(JitsiConfig.newConfig)
    }
    fun averageParticipantPacketRatePps() = averageParticipantPacketRatePps

    companion object {
        const val BASE = "jicofo.bridge"
        @JvmField
        val config = BridgeConfig()
    }
}

