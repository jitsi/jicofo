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
import org.jitsi.jicofo.xmpp.XmppConnectionEnum
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration

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
        "$BASE.average-participant-packet-rate-pps"
            .from(JitsiConfig.newConfig).softDeprecated("use $BASE.average-participant-stress")
    }
    fun averageParticipantPacketRatePps() = averageParticipantPacketRatePps

    val averageParticipantStress: Double by config {
        "$BASE.average-participant-stress".from(JitsiConfig.newConfig)
    }
    fun averageParticipantStress() = averageParticipantStress

    val stressThreshold: Double by config { "$BASE.stress-threshold".from(JitsiConfig.newConfig) }
    fun stressThreshold() = stressThreshold

    val failureResetThreshold: Duration by config {
        "org.jitsi.focus.BRIDGE_FAILURE_RESET_THRESHOLD".from(JitsiConfig.legacyConfig)
            .convertFrom<Long> { Duration.ofMillis(it) }
        "$BASE.failure-reset-threshold".from(JitsiConfig.newConfig)
    }
    fun failureResetThreshold() = failureResetThreshold

    val participantRampupInterval: Duration by config {
        "$BASE.participant-rampup-interval".from(JitsiConfig.newConfig)
    }
    fun participantRampupInterval() = participantRampupInterval

    val selectionStrategy: BridgeSelectionStrategy by config {
        "org.jitsi.jicofo.BridgeSelector.BRIDGE_SELECTION_STRATEGY".from(JitsiConfig.legacyConfig)
            .convertFrom<String> { createSelectionStrategy(it) }
        "$BASE.selection-strategy".from(JitsiConfig.newConfig)
            .convertFrom<String> { createSelectionStrategy(it) }
    }

    private fun createSelectionStrategy(className: String): BridgeSelectionStrategy {
        return try {
            val clazz = Class.forName("${javaClass.getPackage().name}.$className")
            clazz.getConstructor().newInstance() as BridgeSelectionStrategy
        } catch (e: Exception) {
            val clazz = Class.forName(className)
            clazz.getConstructor().newInstance() as BridgeSelectionStrategy
        }
    }

    val healthChecksEnabled: Boolean by config {
        "org.jitsi.jicofo.HEALTH_CHECK_INTERVAL".from(JitsiConfig.legacyConfig)
            .convertFrom<Int> { it > 0 }
        "$BASE.health-checks.enabled".from(JitsiConfig.newConfig)
    }

    val healthChecksInterval: Duration by config {
        "org.jitsi.jicofo.HEALTH_CHECK_INTERVAL".from(JitsiConfig.legacyConfig)
            .convertFrom<Long> { Duration.ofMillis(it) }
        "$BASE.health-checks.interval".from(JitsiConfig.newConfig)
    }

    val healthChecksRetryDelay: Duration by config {
        "org.jitsi.jicofo.HEALTH_CHECK_2NDTRY_DELAY".from(JitsiConfig.legacyConfig)
            .convertFrom<Long> { Duration.ofMillis(it) }
        "$BASE.health-checks.retry-delay".from(JitsiConfig.newConfig)
        "$BASE.health-checks.interval".from(JitsiConfig.newConfig)
            .transformedBy { Duration.ofMillis(it.toMillis() / 2) }
    }

    val breweryJid: EntityBareJid? by optionalconfig {
        "org.jitsi.jicofo.BRIDGE_MUC".from(JitsiConfig.legacyConfig).convertFrom<String> {
            JidCreate.entityBareFrom(it)
        }
        "$BASE.brewery-jid".from(JitsiConfig.newConfig).convertFrom<String> {
            JidCreate.entityBareFrom(it)
        }
    }

    val xmppConnectionName: XmppConnectionEnum by config {
        "jicofo.bridge.xmpp-connection-name".from(JitsiConfig.newConfig)
    }

    companion object {
        const val BASE = "jicofo.bridge"
        @JvmField
        val config = BridgeConfig()
    }
}
