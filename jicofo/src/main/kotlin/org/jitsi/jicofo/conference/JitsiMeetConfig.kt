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
package org.jitsi.jicofo.conference

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import java.lang.Boolean.parseBoolean

/** Configuration for a conference included in a conference request */
class JitsiMeetConfig(properties: Map<String, String>) {
    private val logger = createLogger()

    val rtcStatsEnabled = properties.getBoolean(RTCSTATS_ENABLED, default = true)
    val startAudioMuted: Int? = properties.getInt(START_AUDIO_MUTED)
    val startVideoMuted: Int? = properties.getInt(START_VIDEO_MUTED)

    private fun Map<String, String>.getInt(key: String): Int? = get(key).let {
        try {
            return if (it.isNullOrBlank()) null else Integer.parseInt(it)
        } catch (e: NumberFormatException) {
            logger.warn("Failed to parse the value of $key as an integer.")
            return null
        }
    }

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            put("rtcstatsEnabled", rtcStatsEnabled)
            put("startAudioMuted", startAudioMuted ?: "null")
            put("startVideoMuted", startVideoMuted ?: "null")
        }

    // Needed for createLogger() to work.
    companion object
}

private fun Map<String, String>.getBoolean(key: String, default: Boolean): Boolean = get(key).let {
    return if (it.isNullOrBlank()) default else parseBoolean(it)
}

/** The name of the start muted property for audio. */
const val START_AUDIO_MUTED = "startAudioMuted"

/** The name of the start muted property for video. */
const val START_VIDEO_MUTED = "startVideoMuted"

/** The name of the rtcstats enabled property. */
const val RTCSTATS_ENABLED = "rtcstatsEnabled"
