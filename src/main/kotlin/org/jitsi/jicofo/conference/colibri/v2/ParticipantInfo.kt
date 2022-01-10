/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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
package org.jitsi.jicofo.conference.colibri.v2

import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.OrderedJsonObject

/**
 * Represents the information for a specific participant/endpoint needed for colibri2.
 */
internal class ParticipantInfo(
    val id: String,
    val statsId: String?,
    var sources: ConferenceSourceMap = ConferenceSourceMap(),
    var session: Colibri2Session,
    /** Whether the participant is force audio muted. */
    var audioMuted: Boolean = false,
    /** Whether the participant is force video muted. */
    var videoMuted: Boolean = false
) {
    fun toJson() = OrderedJsonObject().apply {
        put("id", id)
        put("stats_id", statsId.toString())
        put("sources", sources.toJson())
        put("bridge", session.bridge.jid.resourceOrNull.toString())
        put("audio_muted", audioMuted)
        put("video_muted", videoMuted)
    }
}
