/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024-Present 8x8, Inc.
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * The JSON structure included in the MUC config form from the room_metadata prosody module in jitsi-meet. Includes
 * only the fields that we need here in jicofo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RoomMetadata(
    val type: String,
    val metadata: Metadata?
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Metadata(val recording: Recording?, val visitors: Visitors?) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Recording(val isTranscribingEnabled: Boolean?)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Visitors(val live: Boolean?)
    }

    companion object {
        private val mapper = jacksonObjectMapper().apply {
            enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        }

        @Throws(JsonProcessingException::class, JsonMappingException::class)
        fun parse(string: String): RoomMetadata {
            return mapper.readValue(string)
        }
    }
}
