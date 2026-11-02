/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo

import com.typesafe.config.ConfigObject
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.metaconfig.config
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.clientrequirements.RequirementLevel

/**
 * A capability that clients are required to advertise.
 */
data class ClientRequirement(
    val feature: Features,
    val level: RequirementLevel,
    /** Text (in English) which describes how to add support for the feature. Sent to the client. */
    val details: String? = null,
    /** A URL with more information. Sent to the client. */
    val url: String? = null
)

/**
 * Configuration for the capabilities that clients are required to advertise (see [ClientRequirement]).
 *
 * The set of requirements is maintained in [defaultRequirements] below, and can be adjusted with configuration.
 */
class ClientRequirementsConfig {
    private val logger = createLogger()

    val enabled: Boolean by config {
        "jicofo.conference.client-requirements.enabled".from(newConfig)
    }

    /**
     * Whether to actually skip inviting endpoints which miss a requirement with a "hard" level. When this is false
     * jicofo only logs and updates metrics, and notifies the endpoint as if the requirement were "soft".
     */
    val enforce: Boolean by config {
        "jicofo.conference.client-requirements.enforce".from(newConfig)
    }

    /**
     * The configured overrides, keyed by the name of a [Features]. A null [Override.level] means that the requirement
     * is disabled (level "off" in configuration).
     */
    private val overrides: Map<Features, Override> by config {
        "jicofo.conference.client-requirements.requirements".from(newConfig)
            .convertFrom<ConfigObject> { cfg -> parseOverrides(cfg) }
    }

    /**
     * The effective list of requirements: [defaultRequirements] with the configured [overrides] applied. An override
     * can change the level, details and url of a requirement, disable it (with level "off"), or add a new one.
     */
    val requirements: List<ClientRequirement> by lazy {
        val map = defaultRequirements.associateBy { it.feature }.toMutableMap()
        overrides.forEach { (feature, override) ->
            val existing = map[feature]
            val level = override.level
            if (level == null) {
                map.remove(feature)
            } else {
                map[feature] = ClientRequirement(
                    feature = feature,
                    level = level,
                    details = override.details ?: existing?.details,
                    url = override.url ?: existing?.url
                )
            }
        }
        map.values.sortedBy { it.feature.name }.also {
            logger.info("Client requirements: enabled=$enabled, enforce=$enforce, requirements=$it")
        }
    }

    private fun parseOverrides(cfg: ConfigObject): Map<Features, Override> {
        val result = mutableMapOf<Features, Override>()
        cfg.entries.forEach { entry ->
            val feature = Features.values().find { it.name == entry.key }
            if (feature == null) {
                logger.error("Ignoring a requirement for an unknown feature: ${entry.key}")
                return@forEach
            }
            val value = entry.value as? ConfigObject
            if (value == null) {
                logger.error("Ignoring a requirement which is not an object: ${entry.key}")
                return@forEach
            }
            val config = value.toConfig()
            val levelString = if (config.hasPath(LEVEL)) config.getString(LEVEL) else null
            if (levelString != null && levelString != OFF && RequirementLevel.parseString(levelString) == null) {
                logger.error("Ignoring a requirement with an invalid level: ${entry.key}=$levelString")
                return@forEach
            }
            result[feature] = Override(
                level = if (levelString == null || levelString == OFF) {
                    null
                } else {
                    RequirementLevel.parseString(levelString)
                },
                details = if (config.hasPath(DETAILS)) config.getString(DETAILS) else null,
                url = if (config.hasPath(URL)) config.getString(URL) else null
            )
        }
        return result
    }

    private data class Override(val level: RequirementLevel?, val details: String?, val url: String?)

    companion object {
        @JvmField
        val config = ClientRequirementsConfig()

        private const val LEVEL = "level"
        private const val DETAILS = "details"
        private const val URL = "url"
        private const val OFF = "off"

        /**
         * The capabilities that clients are required to advertise. Add entries here when the deployment depends on a
         * client capability, e.g.:
         *
         * ClientRequirement(
         *     feature = Features.SSRC_REWRITING_V1,
         *     level = RequirementLevel.HARD,
         *     details = "Update to jitsi-meet 10.2 or later.",
         *     url = "https://jitsi.github.io/handbook/"
         * )
         *
         * The level, details and url of an entry can be changed with configuration, and an entry can be disabled with
         * a level of "off" (see [Override]).
         */
        val defaultRequirements: List<ClientRequirement> = emptyList()
    }
}
