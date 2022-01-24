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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.jetty.http.HttpStatus;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;

/**
 * A resource for pinning conferences to a specific bridge version.
 */
@Path("/pin")
public class Pin
{
    @NotNull
    private final JicofoServices jicofoServices
        = Objects.requireNonNull(JicofoServices.jicofoServicesSingleton, "jicofoServices");

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getPins()
    {
        return jicofoServices.getFocusManager().getPinnedConferences().toJSONString();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pin(PinJson pinJson, @Context HttpServletRequest request)
    {
        try
        {
            EntityBareJid conferenceJid = JidCreate.entityBareFrom(pinJson.conferenceId);
            jicofoServices.getFocusManager().pinConference(conferenceJid, pinJson.jvbVersion, pinJson.duration);
            return Response.ok().build();
        }
        catch (Throwable t)
        {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build();
        }
    }

    /**
     * Holds the JSON for the pin POST request
     */
    public static class PinJson
    {
        @JsonProperty(value = "conference-id", required = true)
        private String conferenceId;

        @JsonProperty(value = "jvb-version", required = true)
        private String jvbVersion;

        @JsonProperty(value = "duration")
        private Integer duration;

        @JsonCreator
        public PinJson(@JsonProperty(value = "conference-id", required = true) String conferenceId,
                       @JsonProperty(value = "jvb-version", required = true) String jvbVersion,
                       @JsonProperty(value = "duration") Integer duration)
        {
            this.conferenceId = conferenceId;
            this.jvbVersion = jvbVersion;
            this.duration = duration;
        }
    }

    @Path("/remove")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response unpin(UnpinJson unpinJson, @Context HttpServletRequest request)
    {
        try
        {
            EntityBareJid conferenceJid = JidCreate.entityBareFrom(unpinJson.conferenceId);
            jicofoServices.getFocusManager().unpinConference(conferenceJid);
            return Response.ok().build();
        }
        catch (Throwable t)
        {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build();
        }
    }

    /**
     * Holds the JSON for the unpin POST request
     */
    public static class UnpinJson
    {
        @JsonProperty(value = "conference-id", required = true)
        private String conferenceId;

        @JsonCreator
        public UnpinJson(@JsonProperty(value = "conference-id", required = true) String conferenceId)
        {
            this.conferenceId = conferenceId;
        }
    }
}
