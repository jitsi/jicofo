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

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.health.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

import static org.jitsi.jicofo.bridge.BridgeConfig.config;
import static org.jitsi.jicofo.xmpp.UtilKt.sendIqAndGetResponse;

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
    private final Map<Bridge, PeriodicHealthCheckTask> tasks = new ConcurrentHashMap<>();

    private final HealthCheckListener listener;

    @NotNull
    private final XmppProvider xmppProvider;

    /**
     * Creates new instance of <tt>JvbDoctor</tt>.
     */
    public JvbDoctor(HealthCheckListener listener, @NotNull XmppProvider xmppProvider)
    {
        this.xmppProvider = xmppProvider;
        this.listener = listener;
    }

    private AbstractXMPPConnection getConnection()
    {
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
    public void bridgeFailedHealthCheck(@NotNull Bridge bridge)
    {
        // JvbDoctor is the source of these events, no need for additional handling.
    }

    @Override
    public void bridgeRemoved(@NotNull Bridge bridge)
    {
        PeriodicHealthCheckTask healthTask = tasks.remove(bridge);
        if (healthTask == null)
        {
            logger.warn("Trying to remove a bridge that does not exist anymore: " + bridge);
            return;
        }

        logger.info("Stopping health-check task for: " + bridge);

        healthTask.cancel();
    }

    @Override
    public void bridgeAdded(@NotNull Bridge bridge)
    {
        if (tasks.containsKey(bridge))
        {
            logger.warn("Trying to add already existing bridge: " + bridge);
            return;
        }

        Runnable task = config.getUsePresenceForHealth()
                ? new HealthCheckPresenceTask(bridge)
                : new HealthCheckTask(bridge);

        PeriodicHealthCheckTask periodicTask
            = new PeriodicHealthCheckTask(task, healthCheckInterval);

        tasks.put(bridge, periodicTask);

        logger.info("Scheduled health-check task for: " + bridge);
    }

    @Override
    public void bridgeIsShuttingDown(@NotNull Bridge bridge)
    {
    }

    private static class PeriodicHealthCheckTask
    {
        private Runnable innerTask;

        private final ScheduledFuture<?> future;
        private Future<?> innerFuture;
        private final Object lock = new Object();

        private PeriodicHealthCheckTask(Runnable task, long healthCheckInterval)
        {
            innerTask = task;
            future = TaskPools.getScheduledPool().scheduleWithFixedDelay(
                () -> innerFuture = TaskPools.getIoPool().submit(runInner),
                healthCheckInterval,
                healthCheckInterval,
                TimeUnit.MILLISECONDS);
        }

        private final Runnable runInner = () -> {
            synchronized (lock)
            {
                innerTask.run();
            }
        };

        private void cancel()
        {
            future.cancel(true);
            if (innerFuture != null)
            {
                innerFuture.cancel(true);
            }
        }
    }

    private class HealthCheckTask extends AbstractHealthCheckTask
    {
        private HealthCheckTask(Bridge bridge)
        {
            super(bridge);
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
        @Override
        protected void doHealthCheck()
            throws SmackException.NotConnectedException
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

            IQ response = sendIqAndGetResponse(connection, newHealthCheckIQ(bridge));

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

                    response = sendIqAndGetResponse(connection, newHealthCheckIQ(bridge));
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
                        logger.warn("Health check failed for: " + bridge + ": " + error.toXML());
                        listener.healthCheckFailed(bridge.getJid());
                    }
                    else
                    {
                        logger.error(
                                "Unexpected error returned by the bridge: " + bridge + ", err: " + response.toXML()
                        );
                    }
                }
            }
        }
    }

    private abstract class AbstractHealthCheckTask implements Runnable
    {
        protected final Bridge bridge;
        AbstractHealthCheckTask(Bridge bridge)
        {
            this.bridge = bridge;
        }
        protected abstract void doHealthCheck()
                throws SmackException.NotConnectedException, InterruptedException;

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

        protected boolean taskInvalid()
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

    }

    private class HealthCheckPresenceTask extends AbstractHealthCheckTask
    {
        private HealthCheckPresenceTask(Bridge bridge)
        {
            super(bridge);
        }

        /**
         * Performs a health check.
         * @throws org.jivesoftware.smack.SmackException.NotConnectedException when XMPP is not connected,
         * the task should terminate.
         */
        @Override
        protected void doHealthCheck()
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

            logger.debug("Checking presence for health for: " + bridge);

            boolean healthy = bridge.isHealthy();
            boolean timeout = bridge.getTimeSinceLastPresence().compareTo(config.getPresenceHealthTimeout()) > 0;

            // Sync on start/stop and bridges state
            synchronized (JvbDoctor.this)
            {
                if (taskInvalid())
                    return;

                if (timeout)
                {
                    logger.warn("Health check timed out for: " + bridge);
                    listener.healthCheckTimedOut(bridge.getJid());
                }
                else if (!healthy)
                {
                    logger.warn("JVB reported unhealthy" + bridge);
                    listener.healthCheckFailed(bridge.getJid());
                }
                else // healthy
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Health check passed on: " + bridge);
                    }
                    // TODO: we should be able to do this directly when we receive presence with healthy=true, but
                    // I don't want to modify the flow right now.
                    listener.healthCheckPassed(bridge.getJid());
                }
            }
        }
    }
}
