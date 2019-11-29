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
    @Inject
    protected FocusManagerProvider focusManagerProvider;

    /**
     * Returns json string with statistics.
     * @return json string with statistics.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getStats()
    {
        FocusManager focusManager = focusManagerProvider.get();

        JicofoStatisticsSnapshot snapshot
            = JicofoStatisticsSnapshot.generate(focusManager);
        JSONObject json = new JSONObject();
        json.put(CONFERENCES, snapshot.numConferences);
        json.put(LARGEST_CONFERENCE, snapshot.largestConferenceSize);
        json.put(TOTAL_CONFERENCES_CREATED, snapshot.totalConferencesCreated);
        json.put(PARTICIPANTS, snapshot.numParticipants);
        json.put(TOTAL_PARTICIPANTS, snapshot.totalNumParticipants);
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : snapshot.conferenceSizes)
        {
            conferenceSizesJson.add(size);
        }
        json.put(CONFERENCE_SIZES, conferenceSizesJson);

        return json.toJSONString();
    }
}
