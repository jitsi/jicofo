/*
 * Copyright @ 2018 - present 8x8, Inc.
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
 *
 */
package org.jitsi.jicofo.jibri

import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.json.simple.JSONObject

/**
 * Counts total stats (failures by session type).
 */
class JibriStats {
    companion object {
        @JvmField
        val sipFailures = JicofoMetricsContainer.instance.registerCounter(
            "jibri_sip_failures",
            "Number of failures for a SIP jibri"
        )

        @JvmField
        val recordingFailures = JicofoMetricsContainer.instance.registerCounter(
            "jibri_recording_failures",
            "Number of failures for a recording jibri"
        )

        @JvmField
        val liveStreamingFailures = JicofoMetricsContainer.instance.registerCounter(
            "jibri_live_streaming_failures",
            "Number of failures for a live-streaming jibri"
        )

        @JvmStatic
        fun sessionFailed(type: JibriSession.Type) = when (type) {
            JibriSession.Type.SIP_CALL -> sipFailures.inc()
            JibriSession.Type.LIVE_STREAMING -> liveStreamingFailures.inc()
            JibriSession.Type.RECORDING -> recordingFailures.inc()
        }

        private fun globalStatsJson() = JSONObject().apply {
            put("total_sip_call_failures", sipFailures.get())
            put("total_live_streaming_failures", liveStreamingFailures.get())
            put("total_recording_failures", recordingFailures.get())
        }

        /**
         * Generate all jibri stats in JSON format -- the global metrics kept in this companion object, plus stats
         * from the given set of [recorders].
         */
        @JvmStatic
        fun getStats(recorders: Collection<BaseJibri?>) = globalStatsJson().apply {
            val sessions = recorders.filterNotNull().flatMap { it.jibriSessions }

            this["live_streaming_active"] = sessions.count {
                it.jibriType == JibriSession.Type.LIVE_STREAMING && it.isActive
            }
            this["recording_active"] = sessions.count { it.jibriType == JibriSession.Type.RECORDING && it.isActive }
            this["sip_call_active"] = sessions.count { it.jibriType == JibriSession.Type.SIP_CALL && it.isActive }
            this["live_streaming_pending"] = sessions.count {
                it.jibriType == JibriSession.Type.LIVE_STREAMING && it.isPending
            }
            this["recording_pending"] = sessions.count { it.jibriType == JibriSession.Type.RECORDING && it.isPending }
            this["sip_call_pending"] = sessions.count { it.jibriType == JibriSession.Type.SIP_CALL && it.isPending }
        }
    }
}
