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

        @JvmField
        val liveStreamingActive = JicofoMetricsContainer.instance.registerLongGauge(
            "jibri_live_streaming_active",
            "Current number of active jibris in live-streaming mode"
        )

        @JvmField
        val recordingActive = JicofoMetricsContainer.instance.registerLongGauge(
            "jibri_recording_active",
            "Current number of active jibris in recording mode"
        )

        @JvmField
        val sipActive = JicofoMetricsContainer.instance.registerLongGauge(
            "jibri_sip_active",
            "Current number of active jibris in SIP mode"
        )

        @JvmStatic
        fun sessionFailed(type: JibriSession.Type) = when (type) {
            JibriSession.Type.SIP_CALL -> sipFailures.inc()
            JibriSession.Type.LIVE_STREAMING -> liveStreamingFailures.inc()
            JibriSession.Type.RECORDING -> recordingFailures.inc()
        }
    }
}
