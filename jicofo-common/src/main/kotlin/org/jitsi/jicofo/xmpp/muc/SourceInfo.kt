/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp.muc

import org.jitsi.jicofo.conference.source.VideoType
import org.jitsi.utils.MediaType
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

/** The information about a source contained in a jitsi-meet SourceInfo extension. */
data class SourceInfo(
    val name: String,
    val muted: Boolean,
    val videoType: VideoType?,
    val mediaType: MediaType?
)

/**
 *  Parse the JSON string encoded in a SourceInfo XML extension as a set of [SourceInfo]s.
 *  @throws ParseException if the string is not valid JSON.
 *  @throws IllegalArgumentException if the JSON in not in the expected format.
 */
@kotlin.jvm.Throws(IllegalArgumentException::class)
fun parseSourceInfoJson(s: String): Set<SourceInfo> {
    val json = JSONParser().parse(s) as? JSONObject ?: throw IllegalArgumentException("Illegal SourceInfo JSON: $s")

    return json.map {
        val name = it.key as? String ?: throw IllegalArgumentException("Invalid source name ${it.key}")
        val sourceJson = it.value as? JSONObject ?: throw IllegalArgumentException("Invalid source value: ${it.value}")
        val muted = sourceJson["muted"] as? Boolean ?: true
        val videoType = when (val videoTypeValue = sourceJson["videoType"]) {
            null -> null
            !is String -> throw IllegalArgumentException("Invalid videoType: $it")
            else -> VideoType.parseString(videoTypeValue)
        }

        val mediaTypeField = sourceJson["mediaType"] as? String
        val mediaType = when {
            "audio".equals(mediaTypeField, ignoreCase = true) -> MediaType.AUDIO
            "video".equals(mediaTypeField, ignoreCase = true) -> MediaType.VIDEO
            else -> parseMediaTypeFromName(name)
        }

        SourceInfo(name, muted, videoType, mediaType)
    }.toSet()
}

/**
 * If not explicitly signaled the media type is encoded in the name as in `abcd-a0` (an audio source) and `abcd-v5`
 * (a video source).
 */
private fun parseMediaTypeFromName(name: String): MediaType? = name.indexOf('-').let { indexOfDash ->
    if (indexOfDash < 0 || indexOfDash == name.length - 1) {
        null
    } else {
        when (name[indexOfDash + 1]) {
            'a' -> MediaType.AUDIO
            'v' -> MediaType.VIDEO
            else -> null
        }
    }
}
