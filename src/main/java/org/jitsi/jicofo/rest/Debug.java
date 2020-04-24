package org.jitsi.jicofo.rest;

import org.jitsi.impl.protocol.xmpp.*;
import org.json.simple.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * REST endpoint for adjusting debug options.
 */
@Path("/debug")
public class Debug
{
    /**
     * Returns json string with statistics.
     * @return json string with statistics.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getDebug()
    {
        JSONObject stats = new JSONObject();

        stats.put("xmpp_debug", PacketDebugger.isPacketLoggingEnabled());

        return stats.toJSONString();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String setDebug(JSONObject debugRequest)
    {
        String xmpp_debug = String.valueOf(debugRequest.get("xmpp_debug"));

        if ("true".equalsIgnoreCase(xmpp_debug))
        {
            PacketDebugger.setPacketLoggingEnabled(true);
        }
        else if ("false".equalsIgnoreCase(xmpp_debug))
        {
            PacketDebugger.setPacketLoggingEnabled(false);
        }

        return getDebug();
    }
}
