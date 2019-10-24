package org.jitsi.jicofo.rest;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.util.*;
import org.json.simple.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

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
        JSONObject json = new JSONObject();

        int conferenceCount = focusManager.getConferenceCount();
        json.put("conference_count", conferenceCount);

        return json.toJSONString();
    }
}
