/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2025-Present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jitsi.jicofo.MediaType

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AvModerationMessage::class, name = AvModerationMessage.TYPE),
    JsonSubTypes.Type(value = RoomMetadata::class, name = RoomMetadata.TYPE)
)
@JsonIgnoreProperties(ignoreUnknown = true)
sealed class JsonMessage(val type: String) {
    companion object {
        private val mapper = jacksonObjectMapper().apply {
            enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        }

        @JvmStatic
        @Throws(JsonProcessingException::class, JsonMappingException::class)
        fun parse(string: String): JsonMessage {
            return mapper.readValue(string)
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvModerationMessage(
    val room: String?,
    val enabled: Boolean? = null,
    val mediaType: MediaType? = null,
    val actor: String? = null,
    val whitelists: Map<MediaType, List<String>>? = null
) : JsonMessage(TYPE) {

    companion object {
        const val TYPE = "av_moderation"
    }
}

/**
 * The JSON structure included in the MUC config form from the room_metadata prosody module in jitsi-meet. Includes
 * only the fields that we need here in jicofo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RoomMetadata(val metadata: Metadata?) : JsonMessage(TYPE) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Metadata(
        val visitors: Visitors?,
        val startMuted: StartMuted?,
        val moderators: List<String>?,
        val participants: List<String>?,
        val recording: Recording?,
        val asyncTranscription: Boolean? = null,
        val participantsSoftLimit: Int? = null,
        val visitorsEnabled: Boolean? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Visitors(val live: Boolean?)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class StartMuted(val audio: Boolean?, val video: Boolean?)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Recording(val isTranscribingEnabled: Boolean?)
    }

    companion object {
        const val TYPE = "room_metadata"
    }
}
