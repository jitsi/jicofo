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

class TranscriptionConfig private constructor() {
    val logger = createLogger()

    private val urlTemplate: String? by optionalconfig {
        "jicofo.transcription.url-template".from(JitsiConfig.newConfig).transformedBy {
            if (!it.contains("{{${MEETING_ID_TEMPLATE}}}")) {
                logger.warn("Transcriber URL template does not contain $MEETING_ID_TEMPLATE")
            }
            it
        }
    }

    private val httpHeadersProp: Map<String, String>? by optionalconfig {
        "jicofo.transcription.http-headers".from(JitsiConfig.newConfig)
            .convertFrom<ConfigObject> { cfg ->
                cfg.entries.associate { entry ->
                    entry.key to entry.value.unwrapped().toString()
                }
            }
    }

    val httpHeaders: Map<String, String>
        get() = httpHeadersProp ?: emptyMap()

    val pingEnabled: Boolean by config {
        "jicofo.transcription.ping.enabled".from(JitsiConfig.newConfig)
    }

    val pingInterval: Duration by config {
        "jicofo.transcription.ping.interval".from(JitsiConfig.newConfig)
    }

    val pingTimeout: Duration by config {
        "jicofo.transcription.ping.timeout".from(JitsiConfig.newConfig)
    }

    fun getUrl(meetingId: String): TemplatedUrl? = urlTemplate?.let {
        TemplatedUrl(it, requiredKeys = setOf(REGION_TEMPLATE)).apply {
            set(MEETING_ID_TEMPLATE, meetingId)
        }
    }

    companion object {
        @JvmField
        val config = TranscriptionConfig()

        const val MEETING_ID_TEMPLATE = "MEETING_ID"
        const val REGION_TEMPLATE = "REGION"
    }
}
