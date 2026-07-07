/*
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.jicofo.conference.translation

import org.jitsi.jicofo.TranslationConfig
import org.jitsi.jicofo.bridge.colibri.ColibriSessionManager
import org.jitsi.jicofo.bridge.colibri.TranslationRequest
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Ties the aggregated live-translation request map (from RoomMetadata) to the conference: it maintains the synthetic
 * audio [Source]s in the conference source map, signals them to the bridges, and drives the translator `<connect>`.
 *
 * Synthetic sources are owned by the sender endpoint and survive the sender's unrelated source add/remove (they are
 * only removed when the request is dropped, the sender's audio source disappears, or the sender leaves — reflected via
 * [reapply], which the conference calls on the relevant events).
 *
 * All side effects on the conference source map and colibri session manager happen here; the pure naming/SSRC logic
 * lives in [TranslationSourceManager].
 */
class ConferenceTranslationManager(
    private val conferenceSources: ValidatingConferenceSourceMap,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)
    private val sourceManager = TranslationSourceManager()
    private var requests: Map<String, List<String>> = emptyMap()

    /**
     * Per-room translator connect headers (config headers merged with room metadata, e.g. a per-customer usage
     * token), or null to use the static config headers. Stored so [reapply] reuses the last value.
     */
    private var customHeaders: Map<String, String>? = null

    /** Update the aggregated request map (and per-room connect headers) and re-apply. */
    @Synchronized
    fun setRequests(
        requests: Map<String, List<String>>,
        customHeaders: Map<String, String>?,
        colibriSessionManager: ColibriSessionManager?,
        meetingId: String?
    ) {
        this.requests = requests
        this.customHeaders = customHeaders
        apply(colibriSessionManager, meetingId)
    }

    /** Re-apply the last request map (e.g. after a participant left or removed a source, or once colibri is ready). */
    @Synchronized
    fun reapply(colibriSessionManager: ColibriSessionManager?, meetingId: String?) =
        apply(colibriSessionManager, meetingId)

    private fun apply(colibriSessionManager: ColibriSessionManager?, meetingId: String?) {
        if (colibriSessionManager == null || meetingId == null) {
            // Not ready yet; will be applied from JitsiMeetConferenceImpl once colibri is initialized.
            return
        }

        val result = sourceManager.update(requests, ::baseName, ::ssrcInUse)

        // Diff the desired synthetic sources against what is currently in the conference source map and apply the delta.
        val affected = mutableSetOf<String>()
        for (sender in conferenceSources.unmodifiable().keys.toList()) {
            val existingSynthetic = conferenceSources[sender]?.sources?.filter { it.synthetic }?.toSet() ?: emptySet()
            val desired = result.sourcesBySender[sender] ?: emptySet()
            val toRemove = existingSynthetic - desired
            val toAdd = desired - existingSynthetic

            try {
                if (toRemove.isNotEmpty()) {
                    conferenceSources.tryToRemove(sender, EndpointSourceSet(toRemove))
                    affected += sender
                }
                if (toAdd.isNotEmpty()) {
                    conferenceSources.tryToAdd(sender, EndpointSourceSet(toAdd))
                    affected += sender
                }
            } catch (e: Exception) {
                logger.error("Failed to update synthetic translation sources for $sender", e)
            }
        }

        // Signal the updated source sets to the bridges.
        affected.forEach { sender ->
            colibriSessionManager.updateParticipant(sender, sources = conferenceSources[sender])
        }

        // Enable/update/disable the translator connect(s). The colibri layer decides placement (per-source or
        // single-bridge) based on configuration.
        val url = if (result.isEmpty) null else TranslationConfig.config.getUrl(meetingId)
        val translationRequests = result.sourcesBySender.mapNotNull { (sender, sources) ->
            val baseName = baseName(sender) ?: return@mapNotNull null
            TranslationRequest(sender, baseName, sources.map { it.name!! }.sorted())
        }
        colibriSessionManager.setTranslator(url, translationRequests, customHeaders)

        logger.info(
            "Applied audio translation: requests=$requests, translationRequests=$translationRequests, " +
                "signaledBridgesFor=$affected, translator=${if (url != null) "enabled" else "disabled"}"
        )
    }

    /** The base (first non-synthetic audio) source name for a sender, or null if it has none / is not present. */
    private fun baseName(senderId: String): String? {
        val audio = conferenceSources[senderId]?.sources?.firstOrNull {
            it.mediaType == MediaType.AUDIO && !it.synthetic
        } ?: return null
        return audio.name ?: Source.nameForIdAndMediaType(senderId, MediaType.AUDIO, 0)
    }

    /** Whether an SSRC is already used by a non-synthetic source in the conference. */
    private fun ssrcInUse(ssrc: Long): Boolean = conferenceSources.unmodifiable().values.any { set ->
        set.sources.any { !it.synthetic && it.ssrc == ssrc }
    }
}
