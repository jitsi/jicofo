/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import java.time.Duration

class ConferenceConfig {
    val conferenceStartTimeout: Duration by config {
        "org.jitsi.focus.IDLE_TIMEOUT".from(legacyConfig)
        "jicofo.conference.initial-timeout".from(newConfig)
    }

    val enableAutoOwner: Boolean by config {
        "org.jitsi.jicofo.DISABLE_AUTO_OWNER".from(legacyConfig).transformedBy { !it }
        "jicofo.conference.enable-auto-owner".from(newConfig)
    }
    fun enableAutoOwner(): Boolean = enableAutoOwner

    val injectSsrcForRecvOnlyEndpoints: Boolean by config {
        "org.jitsi.jicofo.INJECT_SSRC_FOR_RECVONLY_ENDPOINTS".from(legacyConfig)
        "jicofo.conference.inject-ssrc-for-recv-only-endpoints".from(newConfig)
    }
    fun injectSsrcForRecvOnlyEndpoints(): Boolean = injectSsrcForRecvOnlyEndpoints

    val maxSsrcsPerUser: Int by config {
        "org.jitsi.jicofo.MAX_SSRC_PER_USER".from(legacyConfig)
        "jicofo.conference.max-ssrcs-per-user".from(newConfig)
    }

    val singleParticipantTimeout: Duration by config {
        "org.jitsi.jicofo.SINGLE_PARTICIPANT_TIMEOUT".from(legacyConfig)
        "jicofo.conference.single-participant-timeout".from(newConfig)
    }

    val minParticipants: Int by config {
        "jicofo.conference.min-participants".from(newConfig)
    }

    val enableLipSync: Boolean by config {
        "jicofo.conference.enable-lip-sync".from(newConfig)
    }
    fun enableLipSync(): Boolean = enableLipSync

    val useRandomSharedDocumentName: Boolean by config {
        "jicofo.conference.shared-document.use-random-name".from(newConfig)
    }
    fun useRandomSharedDocumentName(): Boolean = useRandomSharedDocumentName

    companion object {
        @JvmField
        val config = ConferenceConfig()
    }
}
