/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jicofo.auth;

import net.java.sip.communicator.util.Logger;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;

import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;

/**
 * Implements a Jetty <tt>Handler</tt> which is meant to be used as a servlet
 * that runs on "server path" secured by Shibboleth authentication. It should
 * be integrated with Apache through AJP connection which will provide valid
 * Shibboleth attributes to servlet session. Attributes can be used to obtain
 * user's identity provided by Shibboleth SP.
 * <br/><br/>
 * See for more info:
 * https://wiki.shibboleth.net/confluence/display/SHIB2/NativeSPJavaInstall
 *
 * @author Pawel Domas
 */
class ShibbolethHandler
    extends AbstractHandler
{
    /**
     * The logger instance used by Shibboleth handler.
     */
    private static final Logger logger
            = Logger.getLogger(ShibbolethHandler.class);

    private final ShibbolethAuthAuthority shibbolethAuthAuthority;

    /**
     * Initializes a new <tt>ShibbolethHandler</tt> instance.
     *
     * @param shibbolethAuthAuthority parent Shibboleth authentication authority
     */
    public ShibbolethHandler(ShibbolethAuthAuthority shibbolethAuthAuthority)
    {
        this.shibbolethAuthAuthority = shibbolethAuthAuthority;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handle(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException,
            ServletException
    {
        try
        {
            doHandle(target, baseRequest, request, response);
        }
        catch(Exception e)
        {
            logger.error(e, e);

            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Method prints to the log debug information about HTTP request
     * @param request <tt>HttpServletRequest</tt> for which debug info will
     *                be logged.
     */
    private void dumpRequestInfo(HttpServletRequest request)
    {
        logger.debug(request.getRequestURL());
        logger.debug("REMOTE USER: " + request.getRemoteUser());
        logger.debug("Headers: ");
        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements())
        {
            String headerName = headers.nextElement();
            logger.debug(headerName + ": " + request.getHeader(headerName));
        }
        logger.debug("Attributes: ");
        Enumeration<String> attributes = request.getAttributeNames();
        while (attributes.hasMoreElements())
        {
            String attributeName = attributes.nextElement();
            logger.debug(
                attributeName + ": " + request.getAttribute(attributeName));
        }
    }

    private Map<String, String> createPropertiesMap(HttpServletRequest request)
    {
        HashMap<String, String> propertiesMap = new HashMap<String, String>();

        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements())
        {
            String headerName = headers.nextElement();
            propertiesMap.put(headerName, request.getHeader(headerName));
        }

        Enumeration<String> attributes = request.getAttributeNames();
        while (attributes.hasMoreElements())
        {
            String attributeName = attributes.nextElement();
            propertiesMap.put(
                attributeName,
                String.valueOf(request.getAttribute(attributeName)));
        }

        return propertiesMap;
    }

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
    private String getShibAttr(HttpServletRequest request, String name)
    {
        String value = (String) request.getAttribute(name);
        if (value == null)
        {
            value = request.getHeader(name);
        }
        return value;
    }

    private void doHandle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException,
               ServletException
    {
        if (logger.isDebugEnabled())
        {
            dumpRequestInfo(request);
        }

        String room = request.getParameter("room");
        if (StringUtils.isNullOrEmpty(room))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Missing mandatory parameter 'room'");
            return;
        }
        // Extract room name from MUC address
        String fullRoom = room;
        room = MucUtil.extractName(room);

        String machineUID = request.getParameter("machineUID");
        if (StringUtils.isNullOrEmpty(machineUID))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Missing mandatory parameter 'machineUID'");
            return;
        }

        // Check 'mail' attribute which should be set by Shibboleth through AJP
        String email = getShibAttr(request, "mail");
        if (StringUtils.isNullOrEmpty(email))
        {
            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Attribute 'mail' not provided - check server configuration");
            return;
        }

        // User authenticated
        String sessionId
            = shibbolethAuthAuthority.authenticateUser(
                    machineUID, email, fullRoom, createPropertiesMap(request));

        if (sessionId == null)
        {
            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Authentication failed");
            return;
        }

        PrintWriter responseWriter = response.getWriter();

        String displayName = getShibAttr(request, "displayName");
        if (displayName == null)
        {
            displayName = email;
        }

        // Close this window or redirect ?
        boolean close = "true".equalsIgnoreCase(request.getParameter("close"));

        responseWriter.println("<html><head><head/><body>");
        responseWriter.println("<h1>Hello " + displayName + "!<h1/>");
        if (!close)
        {
            responseWriter.println(
                "<h2>You should be redirected back to the conference soon..." +
                        "<h2/>");
        }

        // Store session-id script
        String script =
            "<script>\n" +
                "(function() {\n" +
                " var sessionId = '" + sessionId + "';\n" +
                " localStorage.setItem('sessionId', sessionId);\n" +
                " console.info('sessionID :' + sessionId);\n";

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
            script += " window.location.href='../"+room+"';\n";
        }

        responseWriter.println(script +"})();\n</script>\n");

        responseWriter.println("</body></html>");

        response.setStatus(HttpServletResponse.SC_OK);
        baseRequest.setHandled(true);
    }
}
