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
package org.jitsi.jicofo.rest;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.*;

import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.json.simple.*;

/**
 * Checks the health of {@link FocusManager}.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
public class Health
{
    /**
     * The {@code JitsiMeetConfig} properties to be utilized for the purposes of
     * checking the health (status) of Jicofo.
     */
    private static final Map<String,String> JITSI_MEET_CONFIG
        = Collections.emptyMap();

    /**
     * The name of the parameter that triggers known bridges listing in health
     * check response;
     */
    private static final String LIST_JVB_PARAM_NAME = "list_jvb";

    /**
     * The {@code Logger} utilized by the {@code Health} class to print
     * debug-related information.
     */
    private static final Logger logger = Logger.getLogger(Health.class);

    /**
     * The pseudo-random generator used to generate random input for
     * {@link FocusManager} such as room names.
     */
    private static final Random RANDOM = new Random();

    /**
     * The result from the last health check we ran.
     */
    private static int cachedStatus = -1;

    /**
     * The time when we ran our last health check.
     */
    private static long cachedStatusTimestamp = -1;

    /**
     * The maximum number of millis that we cache results for.
     */
    private static final int STATUS_CACHE_INTERVAL = 1000;

    /**
     * Checks the health (status) of a specific {@link FocusManager}.
     *
     * @param focusManager the {@code FocusManager} to check the health (status)
     * of
     * @throws Exception if an error occurs while checking the health (status)
     * of {@code focusManager} or the check determines that {@code focusManager}
     * is not healthy 
     */
    private static void check(FocusManager focusManager)
        throws Exception
    {
        // Get the MUC service to perform the check on.
        JitsiMeetServices services = focusManager.getJitsiMeetServices();

        String mucService = services != null ? services.getMucService() : null;

        if (StringUtils.isNullOrEmpty(mucService))
        {
            logger.error(
                "No MUC service found on XMPP domain or Jicofo has not" +
                " finished initial components discovery yet");

            throw new RuntimeException("No MUC component");
        }

        // Generate a pseudo-random room name. Minimize the risk of clashing
        // with existing conferences.
        String roomName;

        do
        {
            roomName = generateRoomName() + "@" + mucService;
        }
        while (focusManager.getConference(roomName) != null);

        // Create a conference with the generated room name.
        if (!focusManager.conferenceRequest(
                    roomName,
                    JITSI_MEET_CONFIG,
                    Level.WARNING /* conference logging level */))
        {
            throw new RuntimeException(
                    "Failed to create conference with room name " + roomName);
        }
    }

    /**
     * Generates a pseudo-random room name which is not guaranteed to be unique.
     *
     * @return a pseudo-random room name which is not guaranteed to be unique
     */
    private static String generateRoomName()
    {
        return
            Health.class.getName()
                + "-"
                + Long.toHexString(
                        System.currentTimeMillis() + RANDOM.nextLong());
    }

    /**
     * Gets a JSON representation of the health (status) of a specific
     * {@link FocusManager}.
     *
     * @param focusManager the {@code FocusManager} to get the health (status)
     * of in the form of a JSON representation
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    static void getJSON(
            FocusManager focusManager,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        int status;

        try
        {
            // Callers may ask us to return the list of healthy bridges that
            // Jicofo knows about.
            String listJvbParam = request.getParameter(LIST_JVB_PARAM_NAME);

            if (Boolean.parseBoolean(listJvbParam))
            {
                //caller asked that we check for active bridges.
                // we fail in case we don't
                List<String> activeJVBs = listBridges(focusManager);

                // ABORT here if the list is empty
                if (activeJVBs.isEmpty())
                {
                    logger.error(
                        "The health check failed - 0 active JVB instances !");

                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    response.setStatus(status);
                    return;
                }
                else
                {
                    JSONObject jsonRoot = new JSONObject();
                    jsonRoot.put("jvbs", activeJVBs);
                    response.getWriter().append(jsonRoot.toJSONString());
                }
            }

            //now check Jicofo's health .. unless if we just did that in which
            //case we return the cached result.
            if(System.currentTimeMillis() - cachedStatusTimestamp
                    < STATUS_CACHE_INTERVAL
                && cachedStatus > 0)
            {
                //return a cached result
                status = cachedStatus;
            }
            else
            {
                check(focusManager);

                status = cacheStatus(HttpServletResponse.SC_OK);
            }
        }
        catch (Exception ex)
        {
            logger.error("Health check of Jicofo failed!", ex);

            if (ex instanceof IOException)
                throw (IOException) ex;
            else if (ex instanceof ServletException)
                throw (ServletException) ex;
            else
                status
                    = cacheStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        response.setStatus(status);
    }

    /**
     * Records the specified status and the current time so that we can readily
     * return it if people ask us again in the next second or so.
     *
     * @param status the health check response status code we'd like to cache
     * @return the <tt>status</tt> param for convenience reasons;
     */
    private static int cacheStatus(int status)
    {
        Health.cachedStatus = status;
        Health.cachedStatusTimestamp = System.currentTimeMillis();

        return status;
    }

    /**
     * Returns a list of currently healthy JVBs known to Jicofo and
     * kept alive by our {@link org.jitsi.jicofo.JvbDoctor}.
     * @param focusManager our current context
     * @return the list of healthy bridges currently known to this focus.
     */
    private static List<String> listBridges(FocusManager focusManager)
    {
        JitsiMeetServices services
            = focusManager.getJitsiMeetServices();

        Assert.notNull(services, "services");
        BridgeSelector bridgeSelector = services.getBridgeSelector();
        Assert.notNull(bridgeSelector, "bridgeSelector");

        List<String> activeJVBs = bridgeSelector.listActiveJVBs();

        return activeJVBs;
    }
}
