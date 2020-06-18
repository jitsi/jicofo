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
package org.jitsi.jicofo.health;

import org.jitsi.health.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.Logger;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;
import org.osgi.framework.*;

import java.time.*;
import java.util.*;
import java.util.logging.*;

/**
 * Checks the health of {@link FocusManager}.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
public class Health
    extends AbstractHealthCheckService
{
    private FocusManager focusManager;

    /**
     * The {@code Logger} utilized by the {@code Health} class to print
     * debug-related information.
     */
    private static final Logger logger = Logger.getLogger(Health.class);

    /**
     * The {@code JitsiMeetConfig} properties to be utilized for the purposes of
     * checking the health (status) of Jicofo.
     */
    private static final Map<String,String> JITSI_MEET_CONFIG
        = Collections.emptyMap();

    /**
     * The pseudo-random generator used to generate random input for
     * {@link FocusManager} such as room names.
     */
    private static final Random RANDOM = new Random();

    /**
     * A prefix to the MUC names created for the purpose of health checks.
     * Note that external code (e.g. prosody modules) might use this string to
     * recognize these rooms.
     */
    private static final String ROOM_NAME_PREFIX = "__jicofo-health-check";

    /**
     * The name of the property to enable health checks. When enabled, health
     * checks will be performed periodically (every 10 seconds) and the result
     * available on the {@code /about/health} HTTP endpoint.
     */
    private static final String ENABLE_HEALTH_CHECKS_PNAME
            = "org.jitsi.jicofo.health.ENABLE_HEALTH_CHECKS";

    /**
     * Counts how many health checks took too long.
     */
    private long totalSlowHealthChecks = 0;

    public Health()
    {
        super(Duration.ofSeconds(10),
              Duration.ofSeconds(30),
              Duration.ofSeconds(20));
    }

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService cfg
                = ServiceUtils2.getService(
                        bundleContext, ConfigurationService.class);

        if (cfg != null && cfg.getBoolean(ENABLE_HEALTH_CHECKS_PNAME, false))
        {
            focusManager
                = Objects.requireNonNull(
                    ServiceUtils2.getService(bundleContext, FocusManager.class),
                    "Can not find FocusManager.");

            super.start(bundleContext);
        }
        else
        {
            logger.info("Health checks are disabled.");
        }
    }


    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        focusManager = null;

        super.stop(bundleContext);
    }

    @Override
    public void performCheck()
        throws Exception
    {
        Objects.requireNonNull(focusManager, "FocusManager is not set.");

        long start = System.currentTimeMillis();

        check(focusManager);

        long duration = System.currentTimeMillis() - start;

        if (duration > 3000)
        {
            logger.error("Health check took too long: " + duration + "ms");
            totalSlowHealthChecks++;
        }
    }

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
        if (services == null)
        {
            throw new RuntimeException("No JitsiMeetServices available");
        }

        FocusComponent focusComponent = Main.getFocusXmppComponent();
        if (focusComponent == null)
        {
            throw new RuntimeException("No Jicofo XMPP component");
        }
        if (!focusComponent.isConnectionAlive())
        {
            throw new RuntimeException("Jicofo XMPP component not connected");
        }

        BridgeSelector bridgeSelector = services.getBridgeSelector();
        if (bridgeSelector == null)
        {
            throw new RuntimeException("No BridgeSelector available");
        }

        if (bridgeSelector.getOperationalBridgeCount() <= 0)
        {
            throw new RuntimeException(
                    "No operational bridges available (total bridge count: "
                            + bridgeSelector.getBridgeCount() + ")");
        }

        // Generate a pseudo-random room name. Minimize the risk of clashing
        // with existing conferences.
        EntityBareJid roomName;

        do
        {
            roomName = JidCreate.entityBareFrom(
                generateRoomName(),
                focusManager.getConferenceMucService().asDomainBareJid()
            );
        }
        while (focusManager.getConference(roomName) != null);

        // Create a conference with the generated room name.
        if (!focusManager.conferenceRequest(
                roomName,
                JITSI_MEET_CONFIG,
                Level.WARNING /* conference logging level */,
                false /* don't include in statistics */))
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
    private static Localpart generateRoomName()
    {
        try
        {
            return
                Localpart.from(ROOM_NAME_PREFIX
                    + "-"
                    + Long.toHexString(
                        System.currentTimeMillis() + RANDOM.nextLong()));
        }
        catch (XmppStringprepException e)
        {
            // ignore, cannot happen
            return null;
        }
    }

    /**
     * @return how many health checks took too long so far.
     */
    public long getTotalSlowHealthChecks()
    {
        return totalSlowHealthChecks;
    }
}
