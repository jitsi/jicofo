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
        fun parse(string: String): JsonMessage = mapper.readValue(string)
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
        val visitorsEnabled: Boolean? = null,
        val lobbyEnabled: Boolean? = null,
        val transcription: Transcription? = null,
        /**
         * Per-room live-translation connect config (e.g. a per-customer usage token as an HTTP header,
         * delivered to jicofo on the admin-only metadata path). Mirrors [transcription].
         */
        val translation: Translation? = null,
        /** Aggregated live-translation requests: sender endpoint id -> set of requested language codes. */
        val audioTranslationRequests: Map<String, List<String>>? = null,
        /**
         * What a recording of this room must look like, delivered to jicofo on the admin-only metadata path. It is
         * a separate field from [recording], which carries the client-visible recording state, so that the
         * admin-only path can add it without overwriting that.
         */
        val recordingParams: RecordingParams? = null,

        /**
         * The voice agents requested in the room, keyed by agent id (which becomes the agent's endpoint id). The
         * metadata also carries client-facing fields (display name, kind) that jicofo ignores.
         */
        val agents: Map<String, Agent>? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Visitors(
            val live: Boolean?,
            /**
             * The languages the visitors want transcriptions translated into, as a comma-separated list, or an empty
             * string when they want none.
             *
             * Visitors are in a MUC on a visitor node, so their presence is not visible here. Instead each visitor
             * node's mod_fmuc collects their requested languages and sends them to the main prosody, which aggregates
             * across nodes into this field (mod_visitors_component). This is the same field jigasi reads.
             */
            val transcribingLanguages: String? = null,
            /** How many visitors are requesting transcription, across all visitor nodes. */
            val transcribingCount: Long? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class StartMuted(val audio: Boolean?, val video: Boolean?)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Recording(val isTranscribingEnabled: Boolean?)

        /**
         * What a recording must look like. These say nothing about how Jibri makes it: Jibri selects the screen
         * resolution, the layout and the client options, and it refuses a request it cannot serve. We only pass
         * them on.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class RecordingParams(
            /** The resolution of one tile, in the "WIDTHxHEIGHT" format. */
            val tileResolution: String? = null,
            /** How many tiles the recording must show. */
            val tileCount: Int? = null,
            /** The value for the maxFullResolutionParticipants option of the recorder's client. */
            val maxFullResolutionParticipants: Int? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Transcription(
            val urlParams: Map<String, String>? = null,
            val httpHeaders: Map<String, String>? = null
        ) {
            override fun toString(): String = "Transcription(urlParams=${urlParams?.mapValues { "***" }}, " +
                "httpHeaders=${httpHeaders?.mapValues { "***" }})"
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Translation(
            val httpHeaders: Map<String, String>? = null
        ) {
            override fun toString(): String = "Translation(httpHeaders=${httpHeaders?.mapValues { "***" }})"
        }

        /**
         * A voice agent advertised in the room metadata. Jicofo needs its id and lifecycle state; the dial config
         * stays in the provisioning API, where the media relay fetches it by id.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Agent(val state: String? = null) {
            /** Whether jicofo should host this agent: a failed or ended record is a request nobody will answer. */
            fun isRequested() = state != "failed" && state != "ended"
        }
    }

    companion object {
        const val TYPE = "room_metadata"
    }
}
