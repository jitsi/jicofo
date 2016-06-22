package org.jitsi.jicofo.auth;

import com.google.api.client.auth.oauth2.*;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.http.*;
import com.google.api.client.http.javanet.*;
import com.google.api.client.json.jackson2.*;
import net.java.sip.communicator.util.*;
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
 * Created by pdomas on 20/06/16.
 */
public class OAuth2Handler
    extends AbstractHandler
{
    private final static Logger logger = Logger.getLogger(OAuth2Handler.class);

    private final OAuth2Authority authAuthority;

    public OAuth2Handler(OAuth2Authority oAuth2Authority)
    {
        authAuthority = oAuth2Authority;
    }

    private void dumpRequestInfo(HttpServletRequest request)
    {
        logger.info(request.getRequestURL());
        logger.info("REMOTE USER: " + request.getRemoteUser());
        logger.info("Headers: ");
        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements())
        {
            String headerName = headers.nextElement();
            logger.info(headerName + ": " + request.getHeader(headerName));
        }
        logger.info("Attributes: ");
        Enumeration<String> attributes = request.getAttributeNames();
        while (attributes.hasMoreElements())
        {
            String attributeName = attributes.nextElement();
            logger.info(
                attributeName + ": " + request.getAttribute(attributeName));
        }
    }

    @Override
    public void handle(String target,
                       Request baseRequest, HttpServletRequest request,
                       HttpServletResponse response)
        throws IOException,
               ServletException
    {
        dumpRequestInfo(request);

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

    GoogleTokenResponse requestAccessToken(String code)
        throws IOException, TokenResponseException
    {
            GoogleTokenResponse response =
                new GoogleAuthorizationCodeTokenRequest(
                        authAuthority.httpTransport,
                        authAuthority.jsonFactory,
                        OAuth2Authority.CLIENT_ID,
                        OAuth2Authority.CLIENT_SECRET,
                        code,
                        OAuth2Authority.REDIRECT_URL).execute();

            return response;

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

        String code = request.getParameter("code");
        if (StringUtils.isNullOrEmpty(code))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Missing mandatory parameter 'code'");
            return;
        }

        String state = request.getParameter("state");
        if (StringUtils.isNullOrEmpty(state))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Missing mandatory parameter 'state'");
            return;
        }

        Map<String,String> stateMap = new HashMap<>();
        UrlEncodedParser.parse(state, stateMap);
        logger.info("Parsed state: " + stateMap);

        String room = stateMap.get("room");
        if (StringUtils.isNullOrEmpty(room))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "The 'state' is missing mandatory parameter 'room'");
            return;
        }
        // Extract room name from MUC address
        String fullRoom = room;
        room = MucUtil.extractName(room);

        String machineUID = stateMap.get("machineUID");
        if (StringUtils.isNullOrEmpty(machineUID))
        {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "The 'state' is missing mandatory parameter 'machineUID'");
            return;
        }

        try
        {
            GoogleTokenResponse tokenResponse = requestAccessToken(code);
            logger.info("Token response: " + tokenResponse);


        }
        catch (TokenResponseException e)
        {
            if (e.getDetails() != null)
            {
                logger.error("Error: " + e.getDetails().getError());
                if (e.getDetails().getErrorDescription() != null)
                {
                    logger.error(e.getDetails().getErrorDescription());
                }
                if (e.getDetails().getErrorUri() != null)
                {
                    logger.error(e.getDetails().getErrorUri());
                }
            }
            else
            {
                System.err.println(e.getMessage());
            }
        }

        // Close this window or redirect ?
        boolean close = "true".equalsIgnoreCase(stateMap.get("close"));

        /*responseWriter.println("<html><head><head/><body>");
        responseWriter.println("<h1>Hello " + displayName + "!<h1/>");
        if (!close)
        {
            responseWriter.println(
                "<h2>You should be redirected back to the conference soon..." +
                    "<h2/>");
        }*/

        String sessionId = "dgdfg";
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

        //responseWriter.println(script +"})();\n</script>\n");

        //responseWriter.println("</body></html>");

        response.setStatus(HttpServletResponse.SC_OK);
        baseRequest.setHandled(true);
    }
}
