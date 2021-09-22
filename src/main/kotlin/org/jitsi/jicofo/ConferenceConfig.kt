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

import com.typesafe.config.ConfigObject
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import java.time.Duration
import java.util.TreeMap

@SuppressFBWarnings(value = ["BX_UNBOXING_IMMEDIATELY_REBOXED"], justification = "False positive.")
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

    val maxSsrcGroupsPerUser: Int by config {
        "jicofo.conference.max-ssrc-groups-per-user".from(newConfig)
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

    val useJsonEncodedSources: Boolean by config {
        "jicofo.conference.use-json-encoded-sources".from(newConfig)
    }

    val useRandomSharedDocumentName: Boolean by config {
        "jicofo.conference.shared-document.use-random-name".from(newConfig)
    }
    fun useRandomSharedDocumentName(): Boolean = useRandomSharedDocumentName

    private val sourceSignalingDelays: TreeMap<Int, Int> by config {
        "jicofo.conference.source-signaling-delays".from(newConfig)
            .convertFrom<ConfigObject> { cfg ->
                TreeMap(cfg.entries.associate { it.key.toInt() to it.value.unwrapped() as Int })
            }
    }

    /**
     * Get the number of milliseconds to delay signaling of Jingle sources given a certain [conferenceSize].
     */
    fun getSourceSignalingDelayMs(conferenceSize: Int) =
        sourceSignalingDelays.floorEntry(conferenceSize)?.value ?: 0

    /**
     * Whether to strip simulcast streams when signaling receivers. This option requires that jitsi-videobridge
     * uses the first SSRC in the SIM group as the target SSRC when rewriting streams, as this is the only SSRC
     * signaled to receivers.
     *
     * As an example, if a sender advertises simulcast with the following source groups:
     * SIM(1, 2, 3), FID(1, 4), FID(2, 5), FID(3, 6)
     *
     * If this option is enabled jicofo removes sources 2, 3, 5 and 6 when signaling to receivers of the stream.
     * This leaves just FID(1, 4), and assumes that jitsi-videobridge will use those two SSRCs for the rewritten stream.
     *
     * If the option is disabled, all sources are signaled to receivers. Lib-jitsi-meet has similar logic to strip
     * simulcast from remote streams.
     */
    val stripSimulcast: Boolean by config {
        "jicofo.conference.strip-simulcast".from(newConfig)
    }
    fun stripSimulcast() = stripSimulcast

    companion object {
        @JvmField
        val config = ConferenceConfig()
    }
}
