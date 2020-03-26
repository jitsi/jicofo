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
 */
package org.jitsi.jicofo.rest;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.jicofo.stats.*;
import org.jitsi.jicofo.util.*;
import org.json.simple.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

/**
 * Adds statistics REST endpoint exposes some internal Jicofo stats.
 */
@Path("/stats")
public class Statistics
{
    /**
     * The current number of jitsi-videobridge instances.
     */
    public static final String BREWERY_JVB_COUNT = "brewery_jvb_count";

    /**
     * The current number of jitsi-videobridge instances that are operational
     * (not failed).
     */
    public static final String BREWERY_JVB_OPERATIONAL_COUNT = "brewery_jvb_operational_count";

    /**
     * The current number of jigasi instances that support SIP.
     */
    public static final String BREWERY_JIGASI_SIP_COUNT = "brewery_jigasi_sip_count";

    /**
     * The current number of jigasi instances that support transcription.
     */
    public static final String BREWERY_JIGASI_TRANSCRIBER_COUNT = "brewery_jigasi_transcriber_count";

    /**
     * The current number of jibri instances for streaming.
     */
    public static final String BREWERY_JIBRI_COUNT = "brewery_jibri_count";

    /**
     * The current number of jibri instances for SIP.
     */
    public static final String BREWERY_JIBRI_SIP_COUNT = "brewery_jibri_sip_count";

    /**
     * How many times the live streaming has failed to start so far.
     */
    public static final String TOTAL_LIVE_STREAMING_FAILURES = "total_live_streaming_failures";

    /**
     * How many times the recording has failed to start so far.
     */
    public static final String TOTAL_RECORDING_FAILURES = "total_recording_failures";

    /**
     * How many times a SIP call has failed to start so far.
     */
    public static final String TOTAL_SIP_CALL_FAILURES = "total_sip_call_failures";

    @Inject
    protected FocusManagerProvider focusManagerProvider;

    @Inject
    protected JibriStatsProvider jibriStatsProvider;

    /**
     * Returns json string with statistics.
     * @return json string with statistics.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getStats()
    {
        FocusManager focusManager = focusManagerProvider.get();
        JibriStats jibriStats = jibriStatsProvider.get();

        JicofoStatisticsSnapshot snapshot
            = JicofoStatisticsSnapshot.generate(focusManager, jibriStats);
        JSONObject json = new JSONObject();
        json.put(CONFERENCES, snapshot.numConferences);
        json.put(LARGEST_CONFERENCE, snapshot.largestConferenceSize);
        json.put(TOTAL_CONFERENCES_CREATED, snapshot.totalConferencesCreated);
        json.put(PARTICIPANTS, snapshot.numParticipants);
        json.put(TOTAL_PARTICIPANTS, snapshot.totalNumParticipants);
        json.put(BREWERY_JVB_COUNT, snapshot.bridgeCount);
        json.put(BREWERY_JVB_OPERATIONAL_COUNT, snapshot.operationalBridgeCount);
        json.put(BREWERY_JIGASI_SIP_COUNT, snapshot.jigasiSipCount);
        json.put(BREWERY_JIGASI_TRANSCRIBER_COUNT, snapshot.jigasiTranscriberCount);
        json.put(BREWERY_JIBRI_COUNT, snapshot.jibriCount);
        json.put(BREWERY_JIBRI_SIP_COUNT, snapshot.sipJibriCount);
        json.put(TOTAL_LIVE_STREAMING_FAILURES, snapshot.totalLiveStreamingFailures);
        json.put(TOTAL_RECORDING_FAILURES, snapshot.totalRecordingFailures);
        json.put(TOTAL_SIP_CALL_FAILURES, snapshot.totalSipCallFailures);
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : snapshot.conferenceSizes)
        {
            conferenceSizesJson.add(size);
        }
        json.put(CONFERENCE_SIZES, conferenceSizesJson);

        return json.toJSONString();
    }
}
