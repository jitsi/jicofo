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

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.*;
import org.json.simple.*;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

/**
 * An interface which exposes detailed internal state for the purpose of debugging.
 */
@Path("/debug")
public class Debug
{
    @NotNull
    private final JicofoServices jicofoServices
            = Objects.requireNonNull(JicofoServices.getJicofoServicesSingleton(), "jicofoServices");

    /**
     * Returns json string with statistics.
     * @return json string with statistics.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NotNull
    public String getDebug(@DefaultValue("false") @QueryParam("full") boolean full)
    {
        return jicofoServices.getDebugState(full).toJSONString();
    }

    @GET
    @Path("conference/{confId}")
    @Produces(MediaType.APPLICATION_JSON)
    @NotNull
    public String confDebug(@PathParam("confId") String confId)
    {
        OrderedJsonObject confJson = jicofoServices.getConferenceDebugState(confId);
        return confJson.toJSONString();
    }

    @GET
    @Path("xmpp-caps")
    @Produces(MediaType.APPLICATION_JSON)
    @NotNull
    public String xmppCaps()
    {
        return XmppCapsStats.getStats().toJSONString();
    }

    @GET
    @Path("/conferences")
    @Produces(MediaType.APPLICATION_JSON)
    @NotNull
    @SuppressWarnings("unchecked")
    public String conferences()
    {
        JSONArray conferencesJson = new JSONArray();
        for (JitsiMeetConference c : jicofoServices.getFocusManager().getAllConferences())
        {
            conferencesJson.add(c.getRoomName().toString());
        }
        return conferencesJson.toJSONString();
    }
}
