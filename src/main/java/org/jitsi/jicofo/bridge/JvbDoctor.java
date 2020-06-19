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
package org.jitsi.jicofo.bridge;

import org.jitsi.jicofo.*;
import org.jitsi.service.configuration.*;
import org.jitsi.xmpp.extensions.health.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.jicofo.osgi.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import org.jxmpp.jid.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The class is responsible for doing health checks of currently known
 * jitsi-videobridge instances.
 *
 * Listens to <tt>BridgeEvent#BRIDGE_UP</tt>/<tt>BridgeEvent#BRIDGE_OFFLINE</tt>
 * and schedules/cancels new health check jobs. When a health check task fails
 * <tt>BridgeEvent#HEALTH_CHECK_FAILED</tt> is triggered.
 *
 * Class is started by listing on OSGi activator list in
 * {@link JicofoBundleConfig}
 *
 * @author Pawel Domas
 */
public class JvbDoctor
{
    /**
     * The logger.
     */
    private static final Logger logger = Logger.getLogger(JvbDoctor.class);

    /**
     * The name of the configuration property used to configure health check
     * intervals.
     */
    public static final String HEALTH_CHECK_INTERVAL_PNAME
        = "org.jitsi.jicofo.HEALTH_CHECK_INTERVAL";

    /**
     * The name of the configuration property used to configure 2nd chance
     * delay. This is how long we will wait to retry the health check after 1st
     * timeout.
     */
    public static final String SECOND_CHANCE_DELAY_PNAME
        = "org.jitsi.jicofo.HEALTH_CHECK_2NDTRY_DELAY";

    /**
     * Default value for JVB health checks is 10 seconds.
     */
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL = 10000;

    /**
     * Tells how often we send health checks to the bridge in ms.
     */
    private long healthCheckInterval;

    /**
     * 2nd chance delay which tells how long we will wait to retry the health
     * check after 1st attempt has timed out.
     */
    private long secondChanceDelay;

    /**
     * OSGi bundle context.
     */
    private BundleContext bundleContext;

    /**
     * Health check tasks map.
     */
    private final Map<Jid, ScheduledFuture> tasks
        = new ConcurrentHashMap<>();

    /**
     * <tt>ScheduledExecutorService</tt> reference used to schedule periodic
     * health check tasks.
     */
    private OSGIServiceRef<ScheduledExecutorService> executorServiceRef;

    private final HealthCheckListener listener;

    /**
     * Creates new instance of <tt>JvbDoctor</tt>.
     */
    public JvbDoctor(HealthCheckListener listener)
    {
        this.listener = listener;
    }

    private XmppConnection getConnection() {
        FocusManager focusManager
                = ServiceUtils2.getService(bundleContext, FocusManager.class);
        ProtocolProviderService protocolProvider
                = focusManager.getJvbProtocolProvider();
        OperationSetDirectSmackXmpp xmppOpSet
                = protocolProvider.getOperationSet(OperationSetDirectSmackXmpp.class);

        return protocolProvider.isRegistered()
                ? xmppOpSet.getXmppConnection() : null;
    }

    synchronized public void start(
            BundleContext bundleContext,
            Collection<Bridge> initialBridges)
    {
        if (this.bundleContext != null)
        {
            throw new IllegalStateException("Started already?");
        }
        this.bundleContext = bundleContext;

        ConfigurationService configurationService
                = ServiceUtils2.getService(bundleContext, ConfigurationService.class);
        healthCheckInterval
            = configurationService.getLong(
                    HEALTH_CHECK_INTERVAL_PNAME,
                    DEFAULT_HEALTH_CHECK_INTERVAL);
        if (healthCheckInterval <= 0)
        {
            logger.warn("JVB health-checks disabled");
            return;
        }

        secondChanceDelay
            = configurationService.getLong(
                    SECOND_CHANCE_DELAY_PNAME,
                    DEFAULT_HEALTH_CHECK_INTERVAL / 2);

        this.executorServiceRef
            = new OSGIServiceRef<>(bundleContext, ScheduledExecutorService.class);

        initializeHealthChecks(initialBridges);
    }

    /**
     * Initializes bridge health checks.
     *
     * @param bridges - the list of bridges connected at the time when
     * the {@link JvbDoctor} bundle starts.
     */
    private void initializeHealthChecks(Collection<Bridge> bridges)
    {
        for (Bridge b : bridges)
        {
            Jid bridgeJid = b.getJid();

            if (!tasks.containsKey(bridgeJid))
            {
                addBridge(bridgeJid);
            }
        }
    }

    synchronized public void stop()
    {
        if (this.bundleContext == null)
        {
            return;
        }
        bundleContext = null;

        try
        {
            // Remove scheduled tasks
            ArrayList<Jid> bridges = new ArrayList<>(tasks.keySet());
            for (Jid bridge : bridges)
            {
                removeBridge(bridge);
            }
        }
        finally
        {
            this.executorServiceRef = null;
            this.bundleContext = null;
        }
    }

    void addBridge(Jid bridgeJid)
    {
        if (tasks.containsKey(bridgeJid))
        {
            logger.warn("Trying to add already existing bridge: " + bridgeJid);
            return;
        }

        ScheduledExecutorService executorService = executorServiceRef.get();
        if (executorService == null)
        {
            throw new IllegalStateException(
                    "No ScheduledExecutorService running!");
        }

        ScheduledFuture healthTask
            = executorService.scheduleAtFixedRate(
                    new HealthCheckTask(bridgeJid),
                    healthCheckInterval,
                    healthCheckInterval,
                    TimeUnit.MILLISECONDS);

        tasks.put(bridgeJid, healthTask);

        logger.info("Scheduled health-check task for: " + bridgeJid);
    }

    void removeBridge(Jid bridgeJid)
    {
        ScheduledFuture healthTask = tasks.remove(bridgeJid);
        if (healthTask == null)
        {
            logger.warn(
                    "Trying to remove a bridge that does not exist anymore: "
                        + bridgeJid);
            return;
        }

        logger.info("Stopping health-check task for: " + bridgeJid);

        healthTask.cancel(true);
    }

    private class HealthCheckTask implements Runnable
    {
        private final Jid bridgeJid;

        private HealthCheckTask(Jid bridgeJid)
        {
            this.bridgeJid = bridgeJid;
        }

        @Override
        public void run()
        {
            try
            {
                doHealthCheck();
            }
            catch (Exception e)
            {
                logger.error(
                        "Error when doing health-check on: " + bridgeJid,
                        e);
            }
        }

        private boolean taskInvalid()
        {
            synchronized (JvbDoctor.this)
            {
                if (!tasks.containsKey(bridgeJid))
                {
                    logger.info(
                            "Health check task canceled for: " + bridgeJid);
                    return true;
                }
                return false;
            }
        }

        private HealthCheckIQ newHealthCheckIQ(Jid bridgeJid)
        {
            HealthCheckIQ healthIq = new HealthCheckIQ();
            healthIq.setTo(bridgeJid);
            healthIq.setType(IQ.Type.get);
            return healthIq;
        }

        /**
         * Performs a health check.
         * @throws OperationFailedException when XMPP got disconnected -
         * the task should terminate.
         */
        private void doHealthCheck()
            throws OperationFailedException
        {
            XmppConnection connection = getConnection();
            // If XMPP is currently not connected skip the health-check
            if (connection == null)
            {
                logger.warn(
                        "XMPP disconnected - skipping health check for: "
                            + bridgeJid);
                return;
            }

            if (taskInvalid())
            {
                return;
            }

            logger.debug("Sending health-check request to: " + bridgeJid);

            IQ response
                = connection.sendPacketAndGetReply(
                        newHealthCheckIQ(bridgeJid));

            // On timeout we'll give it one more try
            if (response == null && secondChanceDelay > 0)
            {
                try
                {
                    if (taskInvalid())
                        return;

                    logger.warn(bridgeJid + " health-check timed out,"
                            + " but will give it another try after: "
                            + secondChanceDelay);

                    Thread.sleep(secondChanceDelay);

                    if (taskInvalid())
                        return;

                    response
                        = connection.sendPacketAndGetReply(
                                newHealthCheckIQ(bridgeJid));
                }
                catch (InterruptedException e)
                {
                    logger.error(
                        bridgeJid + " second chance delay wait interrupted", e);
                }
            }

            // Sync on start/stop and bridges state
            synchronized (JvbDoctor.this)
            {
                if (taskInvalid())
                    return;

                logger.debug(
                        "Health check response from: " + bridgeJid + ": "
                            + IQUtils.responseToXML(response));

                if (response == null)
                {
                    logger.warn("Health check timed out for: " + bridgeJid);
                    listener.healthCheckFailed(bridgeJid);
                    return;
                }

                IQ.Type responseType = response.getType();
                if (IQ.Type.result.equals(responseType))
                {
                    // OK
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Health check passed on: " + bridgeJid);
                    }
                    listener.healthCheckPassed(bridgeJid);
                    return;
                }

                if (IQ.Type.error.equals(responseType))
                {
                    XMPPError error = response.getError();
                    XMPPError.Condition condition = error.getCondition();

                    if (XMPPError.Condition.internal_server_error
                            .equals(condition)
                        || XMPPError.Condition.service_unavailable
                            .equals(condition))
                    {
                        // Health check failure
                        logger.warn("Health check failed for: " + bridgeJid
                                + ": " + error.toXML().toString());
                        listener.healthCheckFailed(bridgeJid);
                    }
                    else
                    {
                        logger.error(
                                "Unexpected error returned by the bridge: "
                                    + bridgeJid + ", err: " + response.toXML());
                    }
                }
            }
        }
    }

    interface HealthCheckListener
    {
        void healthCheckPassed(Jid bridgeJid);
        void healthCheckFailed(Jid bridgeJid);
    }
}
