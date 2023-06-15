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
package org.jitsi.jicofo.rest

import jakarta.ws.rs.GET
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.JicofoServices.Companion.jicofoServicesSingleton
import org.jitsi.utils.logging2.createLogger
import org.json.simple.JSONObject
import java.util.*

/**
 * Get stats intended to be included in rtcstats reports.
 * Excludes health check conferences, excludes conferences that have explicitly disabled rtcstats.
 *
 * Returns a map of meetingId to conference state.
 */
@Path("/rtcstats")
class RtcStats {
    private val logger = createLogger()

    /**
     * Returns json string with statistics.
     * @return json string with statistics.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun rtcstats(): String {
        val conferenceStore: FocusManager = jicofoServicesSingleton?.focusManager
            ?: throw InternalServerErrorException("No conference store")

        val rtcstats = JSONObject()
        conferenceStore.getConferences().forEach { conference ->
            if (conference.includeInStatistics() && conference.isRtcStatsEnabled) {
                conference.meetingId?.let { meetingId ->
                    rtcstats.put(meetingId, conference.rtcstatsState)
                }
            }
        }

        return rtcstats.toJSONString()
    }
}
