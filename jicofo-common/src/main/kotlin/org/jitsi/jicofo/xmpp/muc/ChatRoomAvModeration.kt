/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2023-Present 8x8, Inc.
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.observableWhenChanged

/**
 * AvModeration related functions for a specific chat room.
 */
class ChatRoomAvModeration(parentLogger: Logger) {
    val logger = createChildLogger(parentLogger)

    fun reset() {
        byMediaType.forEach { it.value.reset() }
    }

    fun isEnabled(mediaType: MediaType): Boolean = byMediaType[mediaType]?.enabled ?: run {
        logger.warn("Invalid media type $mediaType")
        false
    }

    fun isAllowedToUnmute(mediaType: MediaType, jid: String): Boolean =
        byMediaType[mediaType]?.let {
            !it.enabled || it.whitelist.contains(jid)
        } ?: run {
            logger.warn("Invalid media type $mediaType")
            // We suppress errors for simplicity. Return consistent with isEnabled.
            true
        }

    fun setEnabled(mediaType: MediaType, enabled: Boolean) {
        byMediaType[mediaType]?.let {
            it.enabled = enabled
        } ?: logger.warn("Invalid media type $mediaType")
    }

    fun setWhitelist(mediaType: MediaType, whitelist: List<String>) {
        byMediaType[mediaType]?.let {
            it.whitelist = whitelist
        } ?: logger.warn("Invalid media type $mediaType")
    }

    private val byMediaType = mapOf(
        MediaType.AUDIO to AvModerationForMediaType(MediaType.AUDIO),
        MediaType.VIDEO to AvModerationForMediaType(MediaType.VIDEO)
    )

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    private inner class AvModerationForMediaType(mediaType: MediaType) {
        var enabled: Boolean by observableWhenChanged(false) { _, _, newValue ->
            logger.info("Setting enabled=$newValue for $mediaType")
        }
        var whitelist: List<String> by observableWhenChanged(emptyList()) { _, _, newValue ->
            logger.info("Setting whitelist for $mediaType: $newValue")
        }

        fun reset() {
            enabled = false
            whitelist = emptyList()
        }
    }
}
