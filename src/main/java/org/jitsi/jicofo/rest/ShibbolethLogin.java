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

import com.google.common.html.*;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.auth.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Path("/login")
public class ShibbolethLogin
{
    /**
     * Retrieves Shibboleth attribute value for given name. In case of
     * Apache+Shibboleth deployment attributes are retrieved with
     * <tt>getAttribute</tt> while when nginx+Shibboleth is used then they
     * are passed as request headers.
     *
     * @param request <tt>HttpServletRequest</tt> instance used to obtain
     *                Shibboleth attributes.
     * @param name the name of Shibboleth attribute to get.
     *
     * @return Shibboleth attribute value retrieved from the request or
     *         <tt>null</tt> if there is no value for given <tt>name</tt>.
     */
    private static String getShibAttr(HttpServletRequest request, String name)
    {
        String value = (String) request.getAttribute(name);
        if (value == null)
        {
            value = request.getHeader(name);
        }
        return value;
    }

    @NotNull
    private final ShibbolethAuthAuthority shibbolethAuthAuthority;

    ShibbolethLogin(@NotNull ShibbolethAuthAuthority shibbolethAuthAuthority)
    {
        this.shibbolethAuthAuthority = shibbolethAuthAuthority;
    }

    @GET
    public String login(
            @QueryParam("room") String room,
            @QueryParam("machineUID") String machineUid,
            @QueryParam("close") String closeParam,
            @Context HttpServletRequest request,
            @Context HttpServletResponse response)
    {
        if (room == null)
        {
            throw new BadRequestExceptionWithMessage("Missing mandatory parameter 'room'");
        }
        if (machineUid == null)
        {
            throw new BadRequestExceptionWithMessage("Missing mandatory parameter 'machineUID'");
        }

        String email = getShibAttr(request, "mail");
        if (isBlank(email))
        {
            // I don't understand why we're returning 500 instead of 400 here, but I'm replicating existing behavior.
            throw new InternalServerErrorWithMessage("Attribute 'mail' not provided - check server configuration");
        }

        EntityBareJid roomJid;
        try
        {
            roomJid = JidCreate.entityBareFrom(room);
        }
        catch (XmppStringprepException e)
        {
            // This used to return 500, but is clearly a bad request.
            throw new BadRequestExceptionWithMessage("Room name is not a valid JID");
        }

        String sessionId = shibbolethAuthAuthority.authenticateUser(machineUid, email, roomJid);
        if (sessionId == null)
        {
            // I don't understand why we're returning 500 instead of 403 here, but I'm replicating existing behavior.
            throw new InternalServerErrorWithMessage("Authentication failed");
        }

        String displayName = getShibAttr(request, "displayName");
        if (displayName == null)
        {
            displayName = email;
        }

        // Close this window or redirect ?
        boolean close = "true".equalsIgnoreCase(closeParam);

        return createResponse(displayName, close, sessionId, roomJid);
    }

    private String createResponse(
            String displayName,
            boolean close,
            String sessionId,
            EntityBareJid roomJid)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("<html><head><head/><body>\n");
        sb.append("<h1>Hello ").append(HtmlEscapers.htmlEscaper().escape(displayName)).append("!<h1/>\n");
        if (!close)
        {
            sb.append("<h2>You should be redirected back to the conference soon...<h2/>\n");
        }

        // Store session-id script
        String script =
            "<script>\n" +
                "(function() {\n" +
                " var sessionId = '" + sessionId + "';\n" +
                " localStorage.setItem('sessionId', sessionId);\n" +
                " console.info('sessionID :' + sessionId);\n" +
                " var displayName = '" + displayName + "';\n" +
                " console.info('displayName :' + displayName);\n" +
                " var settings = localStorage.getItem('features/base/settings');\n" +
                " console.info('settings :' + settings);\n" +
                " if (settings){\n" +
                "     try {\n" +
                "	        var settingsObj = JSON.parse(settings);\n" +
                "	        if ( settingsObj && !settingsObj.displayName ) {\n" +
                "	            settingsObj.displayName = displayName;\n" +
                "	            localStorage.setItem('features/base/settings', JSON.stringify(settingsObj));\n" +
                "         }\n" +
                "     }\n" +
                "   catch(e){\n" +
                "     console.error('Unable to parse settings JSON');\n" +
                "   }\n" +
                " }\n" ;
        if (close)
        {
            // Pass session id and close the popup
            script += "var opener = window.opener;\n"+
                    "if (opener) {\n"+
                    "   var res = opener.postMessage(" +
                    "      { sessionId: sessionId },\n" +
                    "      window.opener.location.href);\n"+
                    "   console.info('res: ', res);\n" +
                    "   window.close();\n"+
                    "} else {\n" +
                    "   console.error('No opener !');\n"+
                    "}\n";
        }
        else
        {
            // Redirect back to the conference room
            script += " window.location.href='../" + roomJid.getLocalpart() + "';\n";
        }

        sb.append(script).append("})();\n</script>\n");

        sb.append("</body></html>\n");

        return sb.toString();
    }
}

class BadRequestExceptionWithMessage extends BadRequestException
{
    public BadRequestExceptionWithMessage(String message)
    {
        super(Response.status(400, message).build());
    }
}

class InternalServerErrorWithMessage extends InternalServerErrorException
{
    public InternalServerErrorWithMessage(String message)
    {
        super(Response.status(500, message).build());
    }
}
