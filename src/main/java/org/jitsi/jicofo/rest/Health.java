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

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.utils.logging.Logger;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.time.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

/**
 * Checks the health of {@link FocusManager}.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
@Path("/about/health")
@Singleton()
public class Health
{
    @Inject
    protected FocusManagerProvider focusManagerProvider;

    @Inject
    protected Clock clock;

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
     * Interval which we consider bad for a health check and we will print
     * some debug information.
     */
    private static final Duration BAD_HEALTH_CHECK_INTERVAL
        = Duration.ofSeconds(3);

    /**
     * The pseudo-random generator used to generate random input for
     * {@link FocusManager} such as room names.
     */
    private static final Random RANDOM = new Random();

    /**
     * The result from the last health check we ran.
     */
    private int cachedStatus = -1;

    /**
     * The maximum amount of time that we cache results for.
     */
    private static final Duration STATUS_CACHE_INTERVAL = Duration.ofSeconds(10);

    /**
     * The time when we ran our last health check.
     */
    private static Instant lastHealthCheckTime = Instant.MIN;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHealth(@QueryParam("list_jvb") boolean listJvbs)
    {
        JSONObject activeJvbsJson = new JSONObject();
        try
        {
            FocusManager focusManager = focusManagerProvider.get();
            if (listJvbs)
            {
                List<Jid> activeJvbs = listBridges(focusManager);
                if (activeJvbs.isEmpty())
                {
                    logger.error(
                        "The health check failed - 0 active JVB instances !");
                    throw new InternalServerErrorException();
                }
                activeJvbsJson.put("jvbs",
                    activeJvbs.stream().map(j -> j.toString())
                        .collect(Collectors.toList()));
            }

            if (Duration.between(
                    lastHealthCheckTime,
                    clock.instant()).compareTo(STATUS_CACHE_INTERVAL) < 0
                && cachedStatus > 0)
            {
                return Response.status(cachedStatus)
                    .entity(activeJvbsJson.toJSONString()).build();
            }

            HealthChecksMonitor monitor = null;
            if (focusManager.isHealthChecksDebugEnabled())
            {
                monitor = new HealthChecksMonitor();
                monitor.start();
            }
            try
            {
                check(focusManager);
                cacheStatus(HttpServletResponse.SC_OK);
                return Response.ok(activeJvbsJson.toJSONString()).build();
            }
            finally
            {
                if (monitor != null)
                {
                    monitor.stop();
                }
            }
        }
        catch (Exception ex)
        {
            logger.error("Health check of Jicofo failed!", ex);
            cacheStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return Response.serverError()
                .entity(activeJvbsJson.toJSONString()).build();
        }
    }

    /**
     * Records the specified status and the current time so that we can readily
     * return it if people ask us again in the next second or so.
     *
     * @param status the health check response status code we'd like to cache
     */
    private void cacheStatus(int status)
    {
        cachedStatus = status;
        lastHealthCheckTime = clock.instant();
    }

    /**
     * Returns a list of currently healthy JVBs known to Jicofo and
     * kept alive by our {@link JvbDoctor}.
     * @param focusManager our current context
     * @return the list of healthy bridges currently known to this focus.
     */
    private static List<Jid> listBridges(FocusManager focusManager)
    {
        JitsiMeetServices services
            = Objects.requireNonNull(
            focusManager.getJitsiMeetServices(), "services");

        BridgeSelector bridgeSelector
            = Objects.requireNonNull(
            services.getBridgeSelector(), "bridgeSelector");

        return bridgeSelector.listActiveJVBs();
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

        Jid mucService = services != null ? services.getMucService() : null;

        if (mucService == null)
        {
            logger.error(
                "No MUC service found on XMPP domain or Jicofo has not" +
                    " finished initial components discovery yet");

            throw new RuntimeException("No MUC component");
        }

        // Generate a pseudo-random room name. Minimize the risk of clashing
        // with existing conferences.
        EntityBareJid roomName;

        do
        {
            roomName = JidCreate.entityBareFrom(
                generateRoomName(),
                mucService.asDomainBareJid()
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
                Localpart.from(Health.class.getName()
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
     * Health check monitor schedules execution with a delay
     * {@link Health#BAD_HEALTH_CHECK_INTERVAL} if monitor is not stopped
     * by the time it executes we consider a health check was taking too much
     * time executing and we dump the stack trace in the logs.
     */
    private static class HealthChecksMonitor
        extends TimerTask
    {
        /**
         * The timer that will check for slow health executions.
         */
        private Timer monitorTimer;

        /**
         * The timestamp when monitoring was started.
         */
        private long startedAt = -1;

        @Override
        public void run()
        {
            // if there is no timer, this means we were stopped
            if (this.monitorTimer == null)
            {
                return;
            }

            // this monitoring was not stopped before the bad interval
            // this means it takes too much time
            String threadDump = ThreadDump.takeThreadDump();

            logger.error("Health check took "
                + (System.currentTimeMillis() - this.startedAt)
                + " ms. \n"
                + threadDump);
        }

        /**
         * Starts the monitor. Schedules execution in separate thread
         * after some interval, if monitor is not stopped and it executes
         * we consider the health check took too much time.
         */
        public void start()
        {
            this.startedAt = System.currentTimeMillis();
            this.monitorTimer = new Timer(getClass().getSimpleName(), true);
            this.monitorTimer
                .schedule(this, BAD_HEALTH_CHECK_INTERVAL.toMillis());
        }

        /**
         * Stops the monitor execution time.
         */
        public void stop()
        {
            if (this.monitorTimer != null)
            {
                this.monitorTimer.cancel();
                this.monitorTimer = null;
            }
        }
    }
}
