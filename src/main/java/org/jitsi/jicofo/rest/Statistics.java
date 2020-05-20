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

import org.jitsi.impl.protocol.xmpp.colibri.*;
import org.jitsi.jicofo.util.*;
import org.json.simple.*;

import java.lang.management.*;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * Adds statistics REST endpoint exposes some internal Jicofo stats.
 */
@Path("/stats")
public class Statistics
{
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
    @SuppressWarnings("unchecked")
    public String getStats()
    {
        JSONObject stats = new JSONObject();

        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we merge the FocusManager and Jibri stats in the root object.
        stats.putAll(focusManagerProvider.get().getStats());
        stats.putAll(jibriStatsProvider.get().getStats());
        stats.putAll(ColibriConferenceImpl.stats.toJson());

        stats.put(
            "threads", ManagementFactory.getThreadMXBean().getThreadCount());

        return stats.toJSONString();
    }
}
