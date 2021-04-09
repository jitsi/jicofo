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

import kotlin.*;
import org.jitsi.health.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jivesoftware.smack.*;
import org.jivesoftware.smackx.ping.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import static org.jitsi.jicofo.health.HealthConfig.config;

/**
 * Checks the health of {@link FocusManager}.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
public class JicofoHealthChecker implements HealthCheckService
{
    /**
     * The {@code Logger} utilized by the {@code Health} class to print
     * debug-related information.
     */
    private static final Logger logger = new LoggerImpl(JicofoHealthChecker.class.getName());

    /**
     * The {@code JitsiMeetConfig} properties to be utilized for the purposes of
     * checking the health (status) of Jicofo.
     */
    private static final Map<String,String> JITSI_MEET_CONFIG = Collections.emptyMap();

    /**
     * The pseudo-random generator used to generate random input for
     * {@link FocusManager} such as room names.
     */
    private static final Random RANDOM = new Random();

    /**
     * Counts how many health checks took too long.
     */
    private long totalSlowHealthChecks = 0;

    private FocusManager focusManager;
    private final HealthChecker healthChecker;

    public JicofoHealthChecker(HealthConfig config, FocusManager focusManager)
    {
        this.focusManager = focusManager;
        this.healthChecker = new HealthChecker(
                config.getInterval(),
                config.getTimeout(),
                config.getMaxCheckDuration(),
                false,
                Duration.ofMinutes(5),
                this::performCheck,
                Clock.systemUTC());

    }

    public void start()
    {
        healthChecker.start();
    }

    public void stop()
    {
        focusManager = null;
        try
        {
            healthChecker.stop();
        }
        catch (Exception e)
        {
            logger.warn("Failed to stop.", e);
        }
    }

    public Unit performCheck()
    {
        Objects.requireNonNull(focusManager, "FocusManager is not set.");

        long start = System.currentTimeMillis();

        check(focusManager);

        long duration = System.currentTimeMillis() - start;

        if (duration > config.getMaxCheckDuration().toMillis())
        {
            logger.error("Health check took too long: " + duration + "ms");
            totalSlowHealthChecks++;
        }

        return Unit.INSTANCE;
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
    {
        // Get the MUC service to perform the check on.
        JicofoServices jicofoServices = JicofoServices.jicofoServicesSingleton;
        if (jicofoServices == null)
        {
            throw new RuntimeException("No JicofoServices available");
        }

        BridgeSelector bridgeSelector = jicofoServices.getBridgeSelector();
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
                XmppConfig.client.getConferenceMucJid()
            );
        }
        while (focusManager.getConference(roomName) != null);

        // Create a conference with the generated room name.
        try
        {
            if (!focusManager.conferenceRequest(
                    roomName,
                    JITSI_MEET_CONFIG,
                    Level.WARNING /* conference logging level */,
                    false /* don't include in statistics */))
            {
                throw new RuntimeException("Failed to create conference with room name " + roomName);
            }

            pingClientConnection();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to create conference with room name " + roomName + ":" + e.getMessage());
        }
    }

    /**
     * The check focusManager.conferenceRequest uses Smack's collectors for the response which is executed
     * in its Reader thread. This ping test use a sync stanza listener which is executed in a single thread
     * and if something is blocking it healthcheck will fail.
     *
     * @throws Exception if the check fails or some other error occurs
     */
    private static void pingClientConnection()
        throws Exception
    {
        CountDownLatch pingResponseWait = new CountDownLatch(1);
        Ping p = new Ping(JidCreate.bareFrom(XmppConfig.client.getXmppDomain()));

        XmppProvider provider = Objects.requireNonNull(JicofoServices.jicofoServicesSingleton).getXmppServices()
            .getClientConnection();
        XMPPConnection connection = provider.getXmppConnection();
        StanzaListener listener = packet -> pingResponseWait.countDown();
        try
        {
            connection.addSyncStanzaListener(
                listener, stanza -> stanza.getStanzaId() != null && stanza.getStanzaId().equals(p.getStanzaId())
            );
            connection.sendStanza(p);

            // will wait for 5 seconds to receive the ping
            if (!pingResponseWait.await(5, TimeUnit.SECONDS))
            {
                throw new RuntimeException("did not receive ping from the xmpp server");
            }
        }
        finally
        {
            connection.removeSyncStanzaListener(listener);
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
                Localpart.from(config.getRoomNamePrefix()
                    + "-"
                    + Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong()));
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

    @Override
    public Exception getResult()
    {
        return healthChecker.getResult();
    }
}
