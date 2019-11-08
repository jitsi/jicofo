package org.jitsi.jicofo.rest;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.stats.*;
import org.jitsi.jicofo.util.*;
import org.json.simple.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

@Path("/stats")
public class Statistics
{
    @Inject
    protected FocusManagerProvider focusManagerProvider;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getStats()
    {
        FocusManager focusManager = focusManagerProvider.get();

        JicofoStatisticsSnapshot snapshot = JicofoStatisticsSnapshot.generate(focusManager);
        JSONObject json = new JSONObject();
        json.put(CONFERENCES, snapshot.numConferences);
        json.put(LARGEST_CONFERENCE, snapshot.largestConferenceSize);
        json.put(TOTAL_CONFERENCES_CREATED, snapshot.totalConferencesCreated);
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : snapshot.conferenceSizes)
        {
            conferenceSizesJson.add(size);
        }
        json.put(CONFERENCE_SIZES, conferenceSizesJson);

        return json.toJSONString();
    }
}
