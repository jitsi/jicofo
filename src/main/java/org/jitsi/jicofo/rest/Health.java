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
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.*;

import org.jitsi.jicofo.*;
import org.jitsi.util.Logger;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

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
     * Interval which we consider bad for a health check and we will print
     * some debug information.
     */
    private static final int BAD_HEALTH_CHECK_INTERVAL = 3000;

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
            roomName
                = JidCreate.entityBareFrom(
                    generateRoomName(),
                    mucService.asDomainBareJid());
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
     * Gets a JSON representation of the health (status) of a specific
     * {@link FocusManager}. The method is synchronized so anything other than
     * the health check itself (which is cached) needs to return very quickly.
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
    static synchronized void getJSON(
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
                List<Jid> activeJVBs = listBridges(focusManager);

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
                HealthChecksMonitor monitor = null;
                if (focusManager.isHealthChecksDebugEnabled())
                {
                    monitor = new HealthChecksMonitor();
                    monitor.start();
                }

                try
                {
                    check(focusManager);

                    status = cacheStatus(HttpServletResponse.SC_OK);
                }
                finally
                {
                    if (monitor != null)
                    {
                        monitor.stop();
                    }
                }
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
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(
                    threadMXBean.getAllThreadIds(), 100);
            StringBuilder dbg = new StringBuilder();
            for (ThreadInfo threadInfo : threadInfos)
            {
                dbg.append('"').append(threadInfo.getThreadName()).append('"');

                Thread.State state = threadInfo.getThreadState();
                dbg.append("\n   java.lang.Thread.State: ").append(state);

                if (threadInfo.getLockName() != null)
                {
                    dbg.append(" on ").append(threadInfo.getLockName());
                }
                dbg.append('\n');

                StackTraceElement[] stackTraceElements
                    = threadInfo.getStackTrace();
                for (int i = 0; i < stackTraceElements.length; i++)
                {
                    StackTraceElement ste = stackTraceElements[i];
                    dbg.append("\tat " + ste.toString());
                    dbg.append('\n');
                    if (i == 0 && threadInfo.getLockInfo() != null)
                    {
                        Thread.State ts = threadInfo.getThreadState();
                        if (ts == Thread.State.BLOCKED
                            || ts == Thread.State.WAITING
                            || ts == Thread.State.TIMED_WAITING)
                        {
                            dbg.append("\t-  " + ts + " on "
                                + threadInfo.getLockInfo());
                            dbg.append('\n');
                        }
                    }

                    for (MonitorInfo mi
                            : threadInfo.getLockedMonitors())
                    {
                        if (mi.getLockedStackDepth() == i) {
                            dbg.append("\t-  locked " + mi);
                            dbg.append('\n');
                        }
                    }
                }
                dbg.append("\n\n");
            }
            logger.error("Health check took "
                + (System.currentTimeMillis() - this.startedAt)
                + " ms. \n"
                + dbg.toString());
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
            this.monitorTimer.schedule(this, BAD_HEALTH_CHECK_INTERVAL);
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
