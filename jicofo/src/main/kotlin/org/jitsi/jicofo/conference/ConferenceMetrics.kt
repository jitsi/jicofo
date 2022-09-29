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
package org.jitsi.jicofo.conference

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.utils.stats.ConferenceSizeBuckets
import org.jitsi.jicofo.metrics.JicofoMetricsContainer.Companion.instance as metricsContainer

@SuppressFBWarnings("MS_CANNOT_BE_FINAL")
class ConferenceMetrics {
    companion object {
        @JvmField
        val conferencesCreated = metricsContainer.registerCounter(
            "conferences_created",
            "The number of conferences created on this Jicofo since it was started"
        )

        @JvmField
        val participants = metricsContainer.registerCounter(
            "participants",
            "The total number of participants that have connected to this Jicofo since it was started."
        )

        @JvmField
        val participantsNoMultiStream = metricsContainer.registerCounter(
            "participants_no_multi_stream",
            "Number of participants with no support for receiving multiple streams."
        )

        @JvmField
        val participantsNoSourceName = metricsContainer.registerCounter(
            "participants_no_source_name",
            "Number of participants with no support for source names."
        )

        @JvmField
        val participantsMoved = metricsContainer.registerCounter(
            "participants_moved",
            "Number of participants moved away from a failed bridge"
        )

        @JvmField
        val participantsIceFailed = metricsContainer.registerCounter(
            "participants_ice_failed",
            "Number of participants that reported an ICE failure"
        )

        @JvmField
        val participantsRequestedRestart = metricsContainer.registerCounter(
            "participants_restart_requested",
            "Number of times a participant requested a restart via session-terminate"
        )

        @JvmField
        val largestConference = metricsContainer.registerLongGauge(
            "largest_conference",
            "The current largest conference."
        )

        @JvmField
        val currentParticipants = metricsContainer.registerLongGauge(
            "participants_current",
            "The current number of participants."
        )

        /**
         * TODO: convert to a [Metric]
         */
        @JvmField
        var conferenceSizes = ConferenceSizeBuckets()

        @JvmField
        val participantPairs = metricsContainer.registerLongGauge(
            "participants_pairs",
            "The number of pairs of participants (the sum of n*(n-1) for each conference)"
        )
    }
}
