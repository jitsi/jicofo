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
import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.*;

import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.util.*;
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
        if (!focusManager.conferenceRequest(roomName, JITSI_MEET_CONFIG))
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
            // At this point the health check has passed - now eventually list
            // JVBs known to this Jicofo instance
            String listJvbParam = request.getParameter(LIST_JVB_PARAM_NAME);

            if (Boolean.parseBoolean(listJvbParam))
            {
                JitsiMeetServices services
                    = focusManager.getJitsiMeetServices();

                Assert.notNull(services, "services");

                BridgeSelector bridgeSelector = services.getBridgeSelector();

                Assert.notNull(bridgeSelector, "bridgeSelector");

                List<String> activeJVBs = bridgeSelector.listActiveJVBs();

                // ABORT here if the list is empty
                if (activeJVBs.isEmpty())
                {
                    logger.error(
                        "The health check failed - 0 active JVB instances !");

                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    response.setStatus(status);
                    return;
                }

                JSONObject jsonRoot = new JSONObject();
                jsonRoot.put("jvbs", activeJVBs);
                response.getWriter().append(jsonRoot.toJSONString());
            }

            check(focusManager);

            status = HttpServletResponse.SC_OK;
        }
        catch (Exception ex)
        {
            logger.error("Health check of Jicofo failed!", ex);

            if (ex instanceof IOException)
                throw (IOException) ex;
            else if (ex instanceof ServletException)
                throw (ServletException) ex;
            else
                status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }

        response.setStatus(status);
    }
}
