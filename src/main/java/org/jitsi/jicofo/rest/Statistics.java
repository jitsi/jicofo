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

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

/**
 * Adds statistics REST endpoint exposes some internal Jicofo stats.
 */
@Path("/stats")
public class Statistics
{
    /**
     * Returns json string with statistics.
     * @return json string with statistics.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public String getStats()
    {
        JicofoServices jicofoServices
                = Objects.requireNonNull(JicofoServices.getJicofoServicesSingleton(), "jicofoServices");
        return jicofoServices.getStats().toJSONString();
    }
}
