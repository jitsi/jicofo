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

import com.fasterxml.jackson.annotation.*;
import org.eclipse.jetty.http.*;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import jakarta.servlet.http.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.time.*;
import java.util.*;

/**
 * A resource for pinning conferences to a specific bridge version.
 */
@Path("/pin")
public class Pin
{
    @NotNull
    private final JicofoServices jicofoServices
        = Objects.requireNonNull(JicofoServices.getJicofoServicesSingleton(), "jicofoServices");

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getPins()
    {
        return jicofoServices.getFocusManager().getPinnedConferencesJson().toJSONString();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pin(PinJson pinJson, @Context HttpServletRequest request)
    {
        try
        {
            EntityBareJid conferenceJid = JidCreate.entityBareFrom(pinJson.conferenceId);
            jicofoServices.getFocusManager().pinConference(conferenceJid, pinJson.jvbVersion,
                Duration.ofMinutes(pinJson.minutes));
            return Response.ok().build();
        }
        catch (XmppStringprepException x)
        {
            return Response.status(HttpStatus.BAD_REQUEST_400).build();
        }
        catch (Throwable t)
        {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build();
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
        catch (XmppStringprepException x)
        {
            return Response.status(HttpStatus.BAD_REQUEST_400).build();
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

        @JsonProperty(value = "duration-minutes", required = true)
        private Integer minutes;

        @JsonCreator
        public PinJson(@JsonProperty(value = "conference-id", required = true) String conferenceId,
                       @JsonProperty(value = "jvb-version", required = true) String jvbVersion,
                       @JsonProperty(value = "duration-minutes", required = true) Integer minutes)
        {
            this.conferenceId = conferenceId;
            this.jvbVersion = jvbVersion;
            this.minutes = minutes;
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
