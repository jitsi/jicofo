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
import net.java.sip.communicator.service.protocol.*;
import org.eclipse.jetty.server.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;

/**
 * Checks the health of {@link FocusManager}.
 *
 * @author Lyubomir Marinov
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
     * The {@code Logger} utilized by the {@code Health} class to print
     * debug-related information.
     */
    private static final Logger logger = Logger.getLogger(Health.class);

    /**
     * The XMPP Service Discovery features required by {@code Health} from a
     * MUC service provided by the XMPP server associated with a
     * {@code FocusManager}.
     */
    private static final String[] MUC_FEATURES
        = { "http://jabber.org/protocol/muc" };

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
        // Discover a MUC service to perform the check on.
        String mucService = discoverMUCService(focusManager);

        if (mucService != null && mucService.isEmpty())
            mucService = null;

        // Generate a pseudo-random room name. Minimize the risk of clashing
        // with existing conferences.
        String roomName;

        do
        {
            roomName = generateRoomName();
            if (mucService != null)
                roomName = roomName + "@" + mucService;
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
     * Discovers a MUC service provided by the XMPP server associated with a
     * specific {@code FocusManager}.
     *
     * @param focusManager the {@code FocusManager} which is associated with the
     * XMPP server to discover a MUC service on
     * @return a MUC service provided by the XMPP server associated with
     * {@code focusManager} or {@code null} if no such MUC service is discovered
     */
    private static String discoverMUCService(FocusManager focusManager)
    {
        // OperationSetSimpleCaps
        ProtocolProviderService pps = focusManager.getProtocolProvider();
        OperationSetSimpleCaps ossc
            = focusManager.getOperationSet(OperationSetSimpleCaps.class);

        // XMPP domain
        // FIXME The XMPP domain (name) does not seem to be accessible through
        // focusManager so it is read from a ConfigurationService property like
        // ComponentsDiscovery does. Additionally, ComponentsDiscovery does not
        // appear to report the MUC service. All of the above are weird.
        ConfigurationService cfg = FocusBundleActivator.getConfigService();
        String xmppDomain = cfg.getString(FocusManager.XMPP_DOMAIN_PNAME);

        Set<String> items = ossc.getItems(xmppDomain);

        if (items != null)
        {
            for (String item : items)
            {
                if (ossc.hasFeatureSupport(item, MUC_FEATURES))
                    return item;
            }
        }
        return null;
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
            check(focusManager);
            status = HttpServletResponse.SC_OK;
        }
        catch (Exception ex)
        {
            if (logger.isDebugEnabled())
                logger.debug("Health check of Jicofo failed!", ex);

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
