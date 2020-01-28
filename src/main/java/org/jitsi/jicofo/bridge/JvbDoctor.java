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
import org.jitsi.xmpp.extensions.health.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.event.*;
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
    implements RegistrationStateChangeListener
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
    private final Map<Jid, ScheduledFuture> tasks
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
    private XmppConnection connection;

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

        Objects.requireNonNull(protocolProvider, "protocolProvider");

        // Assert XMPP protocol used.
        if (!ProtocolNames.JABBER.equals(protocolProvider.getProtocolName()))
        {
            throw new IllegalArgumentException(
                    "ProtocolProvider is not an XMPP one");
        }

        protocolProvider.addRegistrationStateChangeListener(this);
        connection
            = Objects.requireNonNull(
                    protocolProvider.getOperationSet(
                            OperationSetDirectSmackXmpp.class),
                    "xmppOpSet")
                 .getXmppConnection();

        capsOpSet
            = protocolProvider.getOperationSet(
                    OperationSetSimpleCaps.class);

        Objects.requireNonNull(capsOpSet, "capsOpSet");

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
            ArrayList<Jid> bridges = new ArrayList<>(tasks.keySet());
            for (Jid bridge : bridges)
            {
                removeBridge(bridge);
            }
        }
        finally
        {
            this.eventAdminRef = null;
            this.executorServiceRef = null;
            if (this.protocolProvider != null)
            {
                this.protocolProvider
                    .removeRegistrationStateChangeListener(this);
            }
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

    private void addBridge(Jid bridgeJid)
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

    private void removeBridge(Jid bridgeJid)
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

    private void notifyHealthCheckFailed(Jid bridgeJid, XMPPError error)
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

    /**
     * When the xmpp protocol provider got registered, its maybe reconnection
     * we need to get the connection. It can happen that on startup the initial
     * obtaining the connection returns null and we get it later when the
     * provider got actually registered.
     *
     * @param registrationStateChangeEvent
     */
    @Override
    public void registrationStateChanged(
        RegistrationStateChangeEvent registrationStateChangeEvent)
    {
        RegistrationState newState = registrationStateChangeEvent.getNewState();

        if (RegistrationState.REGISTERED.equals(newState))
        {
            connection
                = Objects.requireNonNull(
                    protocolProvider.getOperationSet(
                        OperationSetDirectSmackXmpp.class),
                    "xmppOpSet").getXmppConnection();
        }
    }

    private class HealthCheckTask implements Runnable
    {
        private final Jid bridgeJid;

        /**
         * Indicates whether or not the bridge has health-check support.
         * If set to <tt>null</tt> it means that we don't know that yet
         * (there was no successful disco-info exchange so far).
         */
        private Boolean hasHealthCheckSupport;

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
            // If XMPP is currently not connected skip the health-check
            if (!protocolProvider.isRegistered() || connection == null)
            {
                logger.warn(
                        "XMPP disconnected - skipping health check for: "
                            + bridgeJid);
                return;
            }

            // Sync on start/stop and bridges state
            synchronized (JvbDoctor.this)
            {
                if (taskInvalid())
                    return;

                // Check for health-check support
                verifyHealthCheckSupport();

                if (!Boolean.TRUE.equals(hasHealthCheckSupport))
                {
                    // This JVB does not support health-checks
                    return;
                }

                logger.debug("Sending health-check request to: " + bridgeJid);
            }

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
                    logger.error("Second chance delay wait interrupted", e);
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
                    notifyHealthCheckFailed(bridgeJid, null);
                    return;
                }

                IQ.Type responseType = response.getType();
                if (IQ.Type.result.equals(responseType))
                {
                    // OK
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
