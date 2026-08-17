/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo.metrics

import org.jitsi.jicofo.metrics.JicofoMetricsContainer.Companion.instance as metricsContainer

/**
 * Metrics for the in-place ICE restart flow. They live here rather than next to either end of the flow because it
 * spans modules: the request comes in in the `jicofo` module (Participant/JitsiMeetConferenceImpl) while the colibri2
 * exchange with the bridge happens in `jicofo-selector`.
 *
 * The naming matches jitsi-videobridge's `ice_restarts_*` metrics, which cover the other half of the same flow.
 */
class IceRestartMetrics {
    companion object {
        /** A participant asked for an in-place ICE restart via session-info. */
        @JvmField
        val requested = metricsContainer.registerCounter(
            "ice_restarts_requested",
            "Number of in-place ICE restarts requested by a participant."
        )

        /** The bridge's rotated transport was signaled back to the participant in a Jingle transport-info. */
        @JvmField
        val relayed = metricsContainer.registerCounter(
            "ice_restarts_relayed",
            "Number of in-place ICE restarts for which the bridge's rotated transport was signaled to the participant."
        )

        /**
         * The bridge answered with the transport it already had, because it did not need to restart (its own
         * transport has not connected yet). Nothing was relayed and the participant keeps the session it has, so
         * this is neither a completed restart nor a failed one.
         */
        @JvmField
        val notNeeded = metricsContainer.registerCounter(
            "ice_restarts_not_needed",
            "Number of in-place ICE restarts for which the bridge kept its existing transport."
        )

        /**
         * An ICE restart did not complete: the request was rejected (disabled, rate-limited, stale bridge-session ID)
         * or the bridge's response could not be relayed (no transport in the response, stale generation, participant
         * or Jingle session gone).
         */
        @JvmField
        val failed = metricsContainer.registerCounter(
            "ice_restarts_failed",
            "Number of in-place ICE restarts that were rejected or could not be completed."
        )
    }
}
