/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc
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
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.utils.TemplatedUrl
import org.jitsi.utils.logging2.createLogger
import java.time.Duration

/** Configuration for live audio translation (the websocket endpoint a bridge connects to). */
class TranslationConfig private constructor() {
    val logger = createLogger()

    private val urlTemplate: String? by optionalconfig {
        "jicofo.translation.url-template".from(JitsiConfig.newConfig).transformedBy {
            if (!it.contains("{{$MEETING_ID_TEMPLATE}}")) {
                logger.warn("Translator URL template does not contain $MEETING_ID_TEMPLATE")
            }
            it
        }
    }

    private val httpHeadersProp: Map<String, String>? by optionalconfig {
        "jicofo.translation.http-headers".from(JitsiConfig.newConfig)
            .convertFrom<ConfigObject> { cfg ->
                cfg.entries.associate { entry ->
                    entry.key to entry.value.unwrapped().toString()
                }
            }
    }

    /** Static HTTP headers added to the translator connect (e.g. Cloudflare Access service-token credentials). */
    val httpHeaders: Map<String, String>
        get() = httpHeadersProp ?: emptyMap()

    /** Whether live translation is configured (an URL template is set). */
    val enabled: Boolean
        get() = urlTemplate != null

    /** How translation connects are placed on bridges. */
    val mode: Mode by config {
        "jicofo.translation.mode".from(JitsiConfig.newConfig).convertFrom<String> {
            Mode.valueOf(it.uppercase().replace('-', '_'))
        }
    }

    /**
     * The maximum number of target languages handled by a single translation connect. Only applies in [Mode.PER_SOURCE]
     * (a sender requesting more is split across multiple connects on its bridge); ignored in [Mode.SINGLE_BRIDGE].
     */
    val maxLanguagesPerConnect: Int by config {
        "jicofo.translation.max-languages-per-connect".from(JitsiConfig.newConfig)
    }

    /** Whether the bridge should send keepalive pings on the translator connect (to avoid idle-timeout). */
    val pingEnabled: Boolean by config {
        "jicofo.translation.ping.enabled".from(JitsiConfig.newConfig)
    }

    val pingInterval: Duration by config {
        "jicofo.translation.ping.interval".from(JitsiConfig.newConfig)
    }

    val pingTimeout: Duration by config {
        "jicofo.translation.ping.timeout".from(JitsiConfig.newConfig)
    }

    fun getUrl(meetingId: String): TemplatedUrl? = urlTemplate?.let {
        TemplatedUrl(it, requiredKeys = setOf(REGION_TEMPLATE)).apply {
            set(MEETING_ID_TEMPLATE, meetingId)
        }
    }

    /** How translation connects are placed on bridges. */
    enum class Mode {
        /** Each sender's audio is translated on its own (local) bridge, split by [maxLanguagesPerConnect]. */
        PER_SOURCE,

        /** A single connect on one selected bridge handles all sources and languages. */
        SINGLE_BRIDGE
    }

    companion object {
        @JvmField
        val config = TranslationConfig()

        const val MEETING_ID_TEMPLATE = "MEETING_ID"
        const val REGION_TEMPLATE = "REGION"

        /**
         * Merge per-room translation headers (from room metadata) over the static config headers, with the
         * per-room values taking precedence. Mirrors [TranscriptionConfig.processTranscriptionMetadata].
         *
         * Returns null when there are no per-room headers, so callers fall back to the static config headers
         * (i.e. `merged ?: TranslationConfig.config.httpHeaders`).
         *
         * @param translation the translation metadata from room metadata (e.g. a per-customer usage token)
         * @param baseHeaders the static headers from configuration
         */
        @JvmStatic
        fun processTranslationMetadata(
            translation: org.jitsi.jicofo.xmpp.RoomMetadata.Metadata.Translation?,
            baseHeaders: Map<String, String>
        ): Map<String, String>? {
            val customHeaders = translation?.httpHeaders ?: return null
            return baseHeaders.toMutableMap().apply { putAll(customHeaders) }
        }
    }
}
