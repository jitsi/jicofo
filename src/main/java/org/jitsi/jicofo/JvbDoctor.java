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
package org.jitsi.jicofo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.assertions.*;
import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.osgi.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.Logger;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The class is responsible for doing health checks of currently known
 * jitsi-videobridge instances.
 *
 * Listens to <tt>BridgeEvent#BRIDGE_UP</tt>/<tt>BridgeEvent#BRIDGE_DOWN</tt>
 * and schedules/cancels new health check jobs. When a health check task fails
 * <tt>BridgeEvent#HEALTH_CHECK_FAILED</tt> is triggered.
 *
 * Class is started by listing on OSGi activator list in
 * {@link JicofoBundleConfig}
 *
 * @author Pawel Domas
 */
public class JvbDoctor
    extends EventHandlerActivator
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
     * Constant array for health check feature discovery.
     */
    private static final String[] HEALTH_CHECK_FEATURES
        = new String[]
        {
            DiscoveryUtil.FEATURE_HEALTH_CHECK
        };

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
    private BundleContext osgiBc;

    /**
     * Health check tasks map.
     */
    private final Map<String, ScheduledFuture> tasks
        = new ConcurrentHashMap<>();

    /**
     * <tt>ScheduledExecutorService</tt> reference used to schedule periodic
     * health check tasks.
     */
    private OSGIServiceRef<ScheduledExecutorService> executorServiceRef;

    /**
     * <tt>EventAdmin</tt> reference.
     */
    private OSGIServiceRef<EventAdmin> eventAdminRef;

    /**
     * XMPP protocol provider.
     */
    private ProtocolProviderService protocolProvider;

    /**
     * Capabilities operation set used to detect health check support on
     * the bridge.
     */
    private OperationSetSimpleCaps capsOpSet;

    /**
     * XMPP operation set obtained from {@link #protocolProvider}.
     */
    private OperationSetDirectSmackXmpp xmppOpSet;

    /**
     * Creates new instance of <tt>JvbDoctor</tt>.
     */
    public JvbDoctor()
    {
        super(new String[] { BridgeEvent.BRIDGE_UP, BridgeEvent.BRIDGE_DOWN });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void start(BundleContext bundleContext)
        throws Exception
    {
        if (this.osgiBc != null)
        {
            throw new IllegalStateException("Started already?");
        }

        healthCheckInterval
            = FocusBundleActivator.getConfigService().getLong(
                    HEALTH_CHECK_INTERVAL_PNAME,
                    DEFAULT_HEALTH_CHECK_INTERVAL);
        if (healthCheckInterval <= 0)
        {
            logger.warn("JVB health-checks disabled");
            return;
        }

        secondChanceDelay
            = FocusBundleActivator.getConfigService().getLong(
                    SECOND_CHANCE_DELAY_PNAME,
                    DEFAULT_HEALTH_CHECK_INTERVAL / 2);

        this.osgiBc = bundleContext;

        this.eventAdminRef = new OSGIServiceRef<>(osgiBc, EventAdmin.class);

        this.executorServiceRef
            = new OSGIServiceRef<>(osgiBc, ScheduledExecutorService.class);

        // We assume that in Jicofo there is only one XMPP provider running at a
        // time.
        protocolProvider
            = ServiceUtils.getService(osgiBc, ProtocolProviderService.class);

        Assert.notNull(protocolProvider, "protocolProvider");

        // Assert XMPP protocol used.
        if (!ProtocolNames.JABBER.equals(protocolProvider.getProtocolName()))
        {
            throw new IllegalArgumentException(
                    "ProtocolProvider is not an XMPP one");
        }

        xmppOpSet
            = protocolProvider.getOperationSet(
                    OperationSetDirectSmackXmpp.class);

        Assert.notNull(xmppOpSet, "xmppOpSet");

        capsOpSet
            = protocolProvider.getOperationSet(
                    OperationSetSimpleCaps.class);

        Assert.notNull(capsOpSet, "capsOpSet");

        super.start(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (this.osgiBc == null)
            return;

        super.stop(bundleContext);

        try
        {
            // Remove scheduled tasks
            ArrayList<String> bridges = new ArrayList<>(tasks.keySet());
            for (String bridge : bridges)
            {
                removeBridge(bridge);
            }
        }
        finally
        {
            this.eventAdminRef = null;
            this.executorServiceRef = null;
            this.protocolProvider = null;
            this.osgiBc = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void handleEvent(Event event)
    {
        if (!BridgeEvent.isBridgeEvent(event))
        {
            throw new RuntimeException("We should NEVER get non-BridgeEvents!");
        }

        BridgeEvent bridgeEvent = (BridgeEvent) event;

        switch (bridgeEvent.getTopic())
        {
        case BridgeEvent.BRIDGE_UP:
            addBridge(bridgeEvent.getBridgeJid());
            break;
        case BridgeEvent.BRIDGE_DOWN:
            removeBridge(bridgeEvent.getBridgeJid());
            break;
        default:
            logger.error("Received unwanted event: " + event.getTopic());
            break;
        }
    }

    private void addBridge(String bridgeJid)
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

    private void removeBridge(String bridgeJid)
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

    private void notifyHealthCheckFailed(String bridgeJid, XMPPError error)
    {
        EventAdmin eventAdmin = eventAdminRef.get();
        if (eventAdmin == null)
        {
            logger.error(
                    "Unable to trigger health-check failed event: "
                        + "no EventAdmin service found!");
            return;
        }

        logger.warn("Health check failed on: " + bridgeJid + " error: "
                + (error != null ? error.toXML() : "timeout"));

        eventAdmin.postEvent(BridgeEvent.createHealthFailed(bridgeJid));
    }

    private class HealthCheckTask implements Runnable
    {
        private final String bridgeJid;

        /**
         * Indicates whether or not the bridge has health-check support.
         * If set to <tt>null</tt> it means that we don't know that yet
         * (there was no successful disco-info exchange so far).
         */
        private Boolean hasHealthCheckSupport;

        public HealthCheckTask(String bridgeJid)
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

        private boolean checkTaskStillValid()
        {
            synchronized (JvbDoctor.this)
            {
                if (!tasks.containsKey(bridgeJid))
                {
                    logger.info(
                            "Health check task cancelled for: " + bridgeJid);
                    return false;
                }
                return true;
            }
        }

        private void verifyHealthCheckSupport()
        {
            if (hasHealthCheckSupport == null)
            {
                // Check if that bridge comes with health check support
                List<String> jvbFeatures = capsOpSet.getFeatures(bridgeJid);
                if (jvbFeatures != null)
                {
                    hasHealthCheckSupport
                        = DiscoveryUtil.checkFeatureSupport(
                                HEALTH_CHECK_FEATURES, jvbFeatures);
                    if (!hasHealthCheckSupport)
                    {
                        logger.warn(
                                bridgeJid + " does not support health checks!");
                    }
                }
                else
                {
                    logger.warn(
                           "Failed to check for health check support on "
                               + bridgeJid);
                }
            }
        }

        private HealthCheckIQ newHealthCheckIQ(String bridgeJid)
        {
            HealthCheckIQ healthIq = new HealthCheckIQ();
            healthIq.setTo(bridgeJid);
            healthIq.setType(IQ.Type.GET);
            return healthIq;
        }

        private void doHealthCheck()
        {
            // If XMPP is currently not connected skip the health-check
            if (!protocolProvider.isRegistered())
            {
                logger.debug(
                        "XMPP disconnected - skipping health check for: "
                            + bridgeJid);
                return;
            }

            XmppConnection connection;

            // Sync on start/stop and bridges state
            synchronized (JvbDoctor.this)
            {
                if (!checkTaskStillValid())
                    return;

                // Check for health-check support
                verifyHealthCheckSupport();

                if (!Boolean.TRUE.equals(hasHealthCheckSupport))
                {
                    // This JVB does not support health-checks
                    return;
                }

                connection = xmppOpSet.getXmppConnection();

                logger.debug("Sending health-check request to: " + bridgeJid);
            }

            Packet response = connection.sendPacketAndGetReply(
                    newHealthCheckIQ(bridgeJid));

            // On timeout we'll give it one more try
            if (response == null && secondChanceDelay > 0)
            {
                try
                {
                    if (!checkTaskStillValid())
                        return;

                    logger.warn(bridgeJid + " health-check timed out,"
                            + " but will give it another try after: "
                            + secondChanceDelay);

                    Thread.sleep(secondChanceDelay);

                    if (!checkTaskStillValid())
                        return;

                    response = connection.sendPacketAndGetReply(
                            newHealthCheckIQ(bridgeJid));
                }
                catch (InterruptedException e)
                {
                    logger.error("Second chance delay wait interrupted", e);
                }
            }

            // Sync on start/stop and bridges state
            synchronized (JvbDoctor.this)
            {
                if (!checkTaskStillValid())
                    return;

                logger.debug(
                        "Health check response from: " + bridgeJid + ": "
                            + IQUtils.responseToXML(response));

                if (!(response instanceof IQ))
                {
                    if (response != null)
                    {
                        logger.error("Response not an IQ: " + response.toXML());
                    }
                    else
                    {
                        notifyHealthCheckFailed(bridgeJid, null);
                    }
                    return;
                }

                IQ responseIQ = (IQ) response;
                IQ.Type responseType = responseIQ.getType();

                if (IQ.Type.RESULT.equals(responseType))
                {
                    // OK
                    return;
                }

                if (IQ.Type.ERROR.equals(responseType))
                {
                    XMPPError error = responseIQ.getError();
                    String condition = error.getCondition();

                    if (XMPPError.Condition.interna_server_error.toString()
                            .equals(condition)
                        || XMPPError.Condition.service_unavailable.toString()
                            .equals(condition))
                    {
                        // Health check failure
                        notifyHealthCheckFailed(bridgeJid, error);
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
}
