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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.optionalconfig
import java.net.URI

class RecordingConfig private constructor() {
    val multiTrackRecorderUrlTemplate: String? by optionalconfig {
        "jicofo.recording.multi-track-recorder-url-template".from(JitsiConfig.newConfig)
    }

    fun multiTrackRecorderUrl(meetingId: String): URI? = multiTrackRecorderUrlTemplate?.let {
        URI(it.replace(MEETING_ID_TEMPLATE, meetingId))
    }

    companion object {
        @JvmField
        val config = RecordingConfig()

        const val MEETING_ID_TEMPLATE = "MEETING_ID"
    }
}
