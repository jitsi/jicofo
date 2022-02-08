/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.health.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;

import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;

import static org.jitsi.jicofo.bridge.BridgeConfig.config;

/**
 * The class is responsible for doing health checks of currently known
 * jitsi-videobridge instances.
 *
 * @author Pawel Domas
 */
public class JvbDoctor
    implements BridgeSelector.EventHandler
{
    /**
     * The logger.
     */
    private static final Logger logger = new LoggerImpl(JvbDoctor.class.getName());

    /**
     * Tells how often we send health checks to the bridge in ms.
     */
    private final long healthCheckInterval = config.getHealthChecksInterval().toMillis();

    /**
     * 2nd chance delay which tells how long we will wait to retry the health
     * check after 1st attempt has timed out.
     */
    private final long secondChanceDelay = config.getHealthChecksRetryDelay().toMillis();

    /**
     * Health check tasks map.
     */
    private final Map<Bridge, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    private final HealthCheckListener listener;

    /**
     * Creates new instance of <tt>JvbDoctor</tt>.
     */
    public JvbDoctor(HealthCheckListener listener)
    {
        this.listener = listener;
    }

    private AbstractXMPPConnection getConnection()
    {
        JicofoServices jicofoServices = Objects.requireNonNull(JicofoServices.jicofoServicesSingleton);
        XmppProvider xmppProvider = jicofoServices.getXmppServices().getServiceConnection();

        return xmppProvider.getXmppConnection();
    }

    synchronized public void shutdown()
    {
        // Remove scheduled tasks
        ArrayList<Bridge> bridges = new ArrayList<>(tasks.keySet());
        for (Bridge bridge : bridges)
        {
            bridgeRemoved(bridge);
        }
    }

    @Override
    public void bridgeRemoved(Bridge bridge)
    {
        ScheduledFuture<?> healthTask = tasks.remove(bridge);
        if (healthTask == null)
        {
            logger.warn("Trying to remove a bridge that does not exist anymore: " + bridge);
            return;
        }

        logger.info("Stopping health-check task for: " + bridge);

        healthTask.cancel(true);
    }

    @Override
    public void bridgeAdded(Bridge bridge)
    {
        if (tasks.containsKey(bridge))
        {
            logger.warn("Trying to add already existing bridge: " + bridge);
            return;
        }

        ScheduledFuture<?> healthTask
                = TaskPools.getScheduledPool().scheduleAtFixedRate(
                new HealthCheckTask(bridge),
                healthCheckInterval,
                healthCheckInterval,
                TimeUnit.MILLISECONDS);

        tasks.put(bridge, healthTask);

        logger.info("Scheduled health-check task for: " + bridge);
    }

    private class HealthCheckTask implements Runnable
    {
        private final Bridge bridge;

        private HealthCheckTask(Bridge bridge)
        {
            this.bridge = bridge;
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
                // If the task was canceled while running it will throw InterruptedException, ignore it.
                if (taskInvalid() && e instanceof InterruptedException)
                {
                    logger.debug("The task has been canceled.");
                }
                else
                {
                    logger.error("Error when doing health-check on: " + bridge, e);
                }
            }
        }

        private boolean taskInvalid()
        {
            synchronized (JvbDoctor.this)
            {
                if (!tasks.containsKey(bridge))
                {
                    logger.info("Health check task canceled for: " + bridge);
                    return true;
                }
                return false;
            }
        }

        private HealthCheckIQ newHealthCheckIQ(Bridge bridge)
        {
            HealthCheckIQ healthIq = new HealthCheckIQ();
            healthIq.setTo(bridge.getJid());
            healthIq.setType(IQ.Type.get);
            return healthIq;
        }

        /**
         * Performs a health check.
         * @throws org.jivesoftware.smack.SmackException.NotConnectedException when XMPP is not connected,
         * the task should terminate.
         */
        private void doHealthCheck()
            throws SmackException.NotConnectedException, InterruptedException
        {
            AbstractXMPPConnection connection = getConnection();
            // If XMPP is currently not connected skip the health-check
            if (!connection.isConnected())
            {
                logger.warn("XMPP disconnected - skipping health check for: " + bridge);
                return;
            }

            if (taskInvalid())
            {
                return;
            }

            logger.debug("Sending health-check request to: " + bridge);

            IQ response = UtilKt.sendIqAndGetResponse(connection, newHealthCheckIQ(bridge));

            // On timeout we'll give it one more try
            if (response == null && secondChanceDelay > 0)
            {
                try
                {
                    if (taskInvalid())
                        return;

                    logger.warn(bridge + " health-check timed out,"
                            + " but will give it another try after: "
                            + secondChanceDelay);

                    Thread.sleep(secondChanceDelay);

                    if (taskInvalid())
                        return;

                    response = UtilKt.sendIqAndGetResponse(connection, newHealthCheckIQ(bridge));
                }
                catch (InterruptedException e)
                {
                    logger.error(bridge + " second chance delay wait interrupted", e);
                }
            }

            // Sync on start/stop and bridges state
            synchronized (JvbDoctor.this)
            {
                if (taskInvalid())
                    return;

                if (response == null)
                {
                    logger.warn("Health check timed out for: " + bridge);
                    listener.healthCheckTimedOut(bridge.getJid());
                    return;
                }

                IQ.Type responseType = response.getType();
                if (IQ.Type.result.equals(responseType))
                {
                    // OK
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Health check passed on: " + bridge);
                    }
                    listener.healthCheckPassed(bridge.getJid());
                    return;
                }

                if (IQ.Type.error.equals(responseType))
                {
                    StanzaError error = response.getError();
                    StanzaError.Condition condition = error.getCondition();

                    if (StanzaError.Condition.internal_server_error.equals(condition)
                        || StanzaError.Condition.service_unavailable.equals(condition))
                    {
                        // Health check failure
                        logger.warn("Health check failed for: " + bridge + ": " + error.toXML().toString());
                        listener.healthCheckFailed(bridge.getJid());
                    }
                    else
                    {
                        logger.error(
                                "Unexpected error returned by the bridge: " + bridge + ", err: " + response.toXML());
                    }
                }
            }
        }
    }
}
