/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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

    private void doHandle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException,
               ServletException
    {

        String room = request.getParameter("room");
        if (StringUtils.isNullOrEmpty(room))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Missing mandatory parameter 'room'");
            return;
        }
        // Extract room name from MUC address
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
        String email = (String) request.getAttribute("mail");
        if (StringUtils.isNullOrEmpty(email))
        {
            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Attribute 'mail' not provided - check server configuration");
            return;
        }

        // User authenticated
        String sessionId
            = shibbolethAuthAuthority.authenticateUser(machineUID, email);
        if (sessionId == null)
        {
            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Authentication failed");
            return;
        }

        PrintWriter responseWriter = response.getWriter();

        String displayName = (String) request.getAttribute("displayName");
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

        if (logger.isDebugEnabled())
        {
            responseWriter.println("<br/>Debug:");
            Enumeration<String> attributes = request.getAttributeNames();
            while (attributes.hasMoreElements())
            {
                String attributeName = attributes.nextElement();
                responseWriter.print("<br/>" + attributeName + ": ");
                responseWriter.print(
                        String.valueOf(request.getAttribute(attributeName)));
            }

            responseWriter.println("<br/>sessionID: " + sessionId);
        }
        responseWriter.println("</body></html>");

        response.setStatus(HttpServletResponse.SC_OK);
        baseRequest.setHandled(true);
    }
}
