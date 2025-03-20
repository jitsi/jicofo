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

import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
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
class BridgeConfig private constructor() {
    val maxBridgeParticipants: Int by config {
        "org.jitsi.jicofo.BridgeSelector.MAX_PARTICIPANTS_PER_BRIDGE".from(JitsiConfig.legacyConfig)
        "$BASE.max-bridge-participants".from(JitsiConfig.newConfig)
    }

    val averageParticipantStress: Double by config {
        "$BASE.average-participant-stress".from(JitsiConfig.newConfig)
    }

    val stressThreshold: Double by config { "$BASE.stress-threshold".from(JitsiConfig.newConfig) }

    /**
     * How long the "failed" state should be sticky for. Once a [Bridge] goes in a non-operational state (via
     * [.setIsOperational]) it will be considered non-operational for at least this amount of time.
     * See the tests for example behavior.
     */
    val failureResetThreshold: Duration by config {
        "org.jitsi.focus.BRIDGE_FAILURE_RESET_THRESHOLD".from(JitsiConfig.legacyConfig)
            .convertFrom<Long> { Duration.ofMillis(it) }
        "$BASE.failure-reset-threshold".from(JitsiConfig.newConfig)
    }

    val participantRampupInterval: Duration by config {
        "$BASE.participant-rampup-interval".from(JitsiConfig.newConfig)
    }

    val selectionStrategy: BridgeSelectionStrategy by config {
        "org.jitsi.jicofo.BridgeSelector.BRIDGE_SELECTION_STRATEGY".from(JitsiConfig.legacyConfig)
            .convertFrom<String> { createSelectionStrategy(it) }
        "$BASE.selection-strategy".from(JitsiConfig.newConfig)
            .convertFrom<String> { createSelectionStrategy(it) }
    }

    val participantSelectionStrategy: BridgeSelectionStrategy? by config {
        "$BASE.participant-selection-strategy".from(JitsiConfig.newConfig)
            .convertFrom<String> { createSelectionStrategy(it) }
    }

    val visitorSelectionStrategy: BridgeSelectionStrategy? by config {
        "$BASE.visitor-selection-strategy".from(JitsiConfig.newConfig)
            .convertFrom<String> { createSelectionStrategy(it) }
    }

    val topologyStrategy: TopologySelectionStrategy by config {
        "$BASE.topology-strategy".from(JitsiConfig.newConfig)
            .convertFrom<String> { createTopologyStrategy(it) }
    }

    private fun <T> createClassInstance(className: String): T {
        return try {
            val clazz = Class.forName("${javaClass.getPackage().name}.$className")
            clazz.getConstructor().newInstance() as T
        } catch (e: Exception) {
            val clazz = Class.forName(className)
            clazz.getConstructor().newInstance() as T
        }
    }

    private fun createSelectionStrategy(className: String): BridgeSelectionStrategy = createClassInstance(className)

    private fun createTopologyStrategy(className: String): TopologySelectionStrategy = createClassInstance(className)

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

    val usePresenceForHealth: Boolean by config {
        "$BASE.health-checks.use-presence".from(JitsiConfig.newConfig)
    }

    val presenceHealthTimeout: Duration by config {
        "$BASE.health-checks.presence-timeout".from(JitsiConfig.newConfig)
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

    val regionGroups: Map<String, Set<String>> by config {
        "jicofo.bridge".from(JitsiConfig.newConfig).convertFrom<ConfigObject> {
            val regionGroupsConfigList = it["region-groups"] as? ConfigList ?: emptyList<ConfigValue>()
            val regionGroups = regionGroupsConfigList.map { regionsConfigList ->
                (regionsConfigList as? ConfigList ?: emptyList<ConfigValue>()).map { region ->
                    region.unwrapped().toString()
                }.toSet()
            }.toSet()
            mutableMapOf<String, Set<String>>().apply {
                regionGroups.forEach { regionGroup ->
                    regionGroup.forEach { region ->
                        this[region] = regionGroup
                    }
                }
            }
        }
    }

    fun getRegionGroup(region: String?): Set<String> =
        if (region == null) emptySet() else regionGroups[region] ?: setOf(region)

    val iceFailureDetection = IceFailureDetectionConfig()
    val loadRedistribution = LoadRedistributionConfig()

    companion object {
        const val BASE = "jicofo.bridge"

        @JvmField
        val config = BridgeConfig()
    }
}

class IceFailureDetectionConfig internal constructor() {
    val enabled: Boolean by config {
        "$BASE.enabled".from(JitsiConfig.newConfig)
    }
    val interval: Duration by config {
        "$BASE.interval".from(JitsiConfig.newConfig)
    }
    val minEndpoints: Int by config {
        "$BASE.min-endpoints".from(JitsiConfig.newConfig)
    }
    val threshold: Double by config {
        "$BASE.threshold".from(JitsiConfig.newConfig)
    }
    val timeout: Duration by config {
        "$BASE.timeout".from(JitsiConfig.newConfig)
    }

    companion object {
        const val BASE = "jicofo.bridge.ice-failure-detection"
    }
}

class LoadRedistributionConfig internal constructor() {
    val enabled: Boolean by config {
        "$BASE.enabled".from(JitsiConfig.newConfig)
    }
    val interval: Duration by config {
        "$BASE.interval".from(JitsiConfig.newConfig)
    }
    val timeout: Duration by config {
        "$BASE.timeout".from(JitsiConfig.newConfig)
    }
    val stressThreshold: Double by config {
        "$BASE.stress-threshold".from(JitsiConfig.newConfig)
    }
    val endpoints: Int by config {
        "$BASE.endpoints".from(JitsiConfig.newConfig)
    }

    override fun toString(): String =
        "LoadRedistributionConfig(enabled=$enabled, interval=$interval, timeout=$timeout, " +
            "stressThreshold=$stressThreshold, endpoints=$endpoints)"

    companion object {
        const val BASE = "jicofo.bridge.load-redistribution"
    }
}
