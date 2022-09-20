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

import org.jitsi.jicofo.metrics.JicofoMetricsContainer.Companion.instance as metricsContainer

class ConferenceMetrics {
    companion object {
        @JvmField
        val totalConferencesCreated = metricsContainer.registerCounter(
            "conferences_created",
            "The number of conferences created on this Jicofo since it was started"
        )

        @JvmField
        val totalParticipants = metricsContainer.registerCounter(
            "participants",
            "The total number of participants that have connected to this Jicofo since it was started."
        )

        @JvmField
        val totalParticipantsNoMultiStream = metricsContainer.registerCounter(
            "participants_no_multi_stream",
            "Number of participants with no support for receiving multiple streams."
        )

        @JvmField
        val totalParticipantsNoSourceName = metricsContainer.registerCounter(
            "participants_no_source_name",
            "Number of participants with no support for source names."
        )

        @JvmField
        val totalParticipantsMoved = metricsContainer.registerCounter(
            "participants_moved",
            "Number of participants moved away from a failed bridge"
        )

        @JvmField
        val totalParticipantsIceFailed = metricsContainer.registerCounter(
            "participants_ice_failures",
            "Number of participants that reported an ICE failure"
        )

        @JvmField
        val totalParticipantsRequestedRestart = metricsContainer.registerCounter(
            "participants_restart_requests",
            "Number of times a participant requested a restart via session-terminate"
        )
    }
}
