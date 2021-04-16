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

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.event.*;
import org.jitsi.xmpp.extensions.colibri.*;

import org.jitsi.utils.logging2.*;

import org.json.simple.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import static org.glassfish.jersey.internal.guava.Predicates.not;

/**
 * Class exposes methods for selecting best videobridge from all currently
 * available.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class BridgeSelector
    implements JvbDoctor.HealthCheckListener
{
    /**
     * The logger.
     */
    private final static Logger logger = new LoggerImpl(BridgeSelector.class.getName());

    /**
     * TODO: Refactor to use a common executor.
     */
    private final static ExecutorService eventEmitterExecutor
        = Executors.newSingleThreadExecutor(new CustomizableThreadFactory("BridgeSelector-AsyncEventEmitter", false));

    /**
     * The map of bridge JID to <tt>Bridge</tt>.
     */
    private final Map<Jid, Bridge> bridges = new HashMap<>();

    private final AsyncEventEmitter<EventHandler> eventEmitter = new AsyncEventEmitter<>(eventEmitterExecutor);

    /**
     * The bridge selection strategy.
     */
    private final BridgeSelectionStrategy bridgeSelectionStrategy = BridgeConfig.config.getSelectionStrategy();

    private final JvbDoctor jvbDoctor = new JvbDoctor(this);

    /**
     * Creates new instance of {@link BridgeSelector}.
     *
     */
    public BridgeSelector()
    {
        logger.info("Using " + bridgeSelectionStrategy.getClass().getName());
        jvbDoctor.start(getBridges());
    }

    /**
     * Adds a bridge to this selector. If a bridge with the given JID already
     * exists, it does nothing.
     * @return the {@link Bridge} instance for thee given JID.
     *
     * @param bridgeJid the JID of videobridge.
     */
    public Bridge addJvbAddress(Jid bridgeJid)
    {
        return addJvbAddress(bridgeJid, null);
    }

    /**
     * Adds a bridge to this selector, or if a bridge with the given JID
     * already exists updates its stats.
     *
     * @param bridgeJid the JID of videobridge to be added to this selector's
     * set of videobridges.
     * @param stats the last reported statistics
     * @return the {@link Bridge} instance for thee given JID.
     */
    synchronized public Bridge addJvbAddress(Jid bridgeJid, ColibriStatsExtension stats)
    {
        Bridge bridge = bridges.get(bridgeJid);
        if (bridge != null)
        {
            bridge.setStats(stats);
            return bridge;
        }

        Bridge newBridge = new Bridge(bridgeJid);
        if (stats != null)
        {
            newBridge.setStats(stats);
        }
        logger.info("Added new videobridge: " + newBridge);
        bridges.put(bridgeJid, newBridge);

        notifyBridgeUp(newBridge);
        jvbDoctor.addBridge(newBridge.getJid());
        return newBridge;
    }

    /**
     * Removes Jitsi Videobridge XMPP address from the list videobridge
     * instances available in the system .
     *
     * @param bridgeJid the JID of videobridge to be removed from this selector's
     *                  set of videobridges.
     */
    synchronized public void removeJvbAddress(Jid bridgeJid)
    {
        logger.info("Removing JVB: " + bridgeJid);

        Bridge bridge = bridges.remove(bridgeJid);

        if (bridge != null)
        {
            notifyBridgeDown(bridge);
            jvbDoctor.removeBridge(bridgeJid);
        }
    }

    @Override
    public void healthCheckPassed(Jid bridgeJid)
    {
        Bridge bridge = bridges.get(bridgeJid);
        if (bridge != null)
        {
            bridge.setIsOperational(true);
        }
    }

    @Override
    public void healthCheckFailed(Jid bridgeJid)
    {
        Bridge bridge = bridges.get(bridgeJid);

        if (bridge != null)
        {
            // When a bridge returns a non-healthy status, we mark it as non-operational AND we move all conferences
            // away from it.
            setBridgeNonOperational(bridge, true);
        }
    }

    @Override
    public void healthCheckTimedOut(Jid bridgeJid)
    {
        Bridge bridge = bridges.get(bridgeJid);

        if (bridge != null)
        {
            // We are more lenient when a health check times out as opposed to failing with an error. We mark it as
            // non-operational to prevent new conferences being allocated there, but do not move existing conferences
            // away from it (which is what `notifyBridgeDown` would trigger).
            //
            // The reason for this is to better handle the case of an intermittent network failure between the bridge
            // and jicofo that does not affect the endpoints. In this case a conference will be moved away from the
            // bridge if and when a request for that conference fails or times out. This prevents unnecessary moves when
            // the bridge eventually recovers (the XMPP/MUC disconnect takes much longer than a health check timing
            // out), and prevents a burst of requests due to all conferences being moved together (this is especially
            // bad when multiple bridges experience network problems, and conference from one failing bridge are
            // attempted to be moved to another failing bridge).
            // The other possible case is that the bridge is not responding to jicofo, and is also unavailable to
            // endpoints. In this case we rely on endpoints reporting ICE failures to jicofo, which then trigger a move.
            setBridgeNonOperational(bridge, /* notifyBridgeDown= */ false);
        }
    }

    private void setBridgeNonOperational(@NotNull Bridge bridge, boolean notifyBridgeDown)
    {
        bridge.setIsOperational(false);
        if (notifyBridgeDown)
        {
            notifyBridgeDown(bridge);
        }
    }

    /**
     * Selects a bridge to be used for a specific new {@link Participant} of
     * a specific {@link JitsiMeetConference}.
     *
     * @return the selected bridge, represented by its {@link Bridge}.
     * @param conference the conference for which a bridge is to be selected.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     */
    synchronized public Bridge selectBridge(
            @NotNull JitsiMeetConference conference,
            String participantRegion)
    {
        // the list of all known videobridges JIDs ordered by load and *operational* status.
        ArrayList<Bridge> prioritizedBridges;
        synchronized (this)
        {
            prioritizedBridges = new ArrayList<>(bridges.values());
        }
        Collections.sort(prioritizedBridges);

        List<Bridge> candidateBridges
            = prioritizedBridges.stream()
                .filter(Bridge::isOperational)
                .filter(not(Bridge::isInGracefulShutdown))
                .collect(Collectors.toList());

        // if there's no candidate bridge, we include bridges that are in graceful shutdown mode
        // (the alternative is to crash the user)
        if (candidateBridges.isEmpty())
        {
            candidateBridges
                = prioritizedBridges.stream()
                    .filter(Bridge::isOperational)
                    .collect(Collectors.toList());
        }

        return bridgeSelectionStrategy.select(
            candidateBridges,
            conference.getBridges(),
            participantRegion,
            OctoConfig.config.getEnabled());
    }

    /**
     * Selects a bridge to be used for a specific {@link JitsiMeetConference}.
     *
     * @param conference the conference for which a bridge is to be selected.
     * @return the selected bridge, represented by its {@link Bridge}.
     */
    public Bridge selectBridge(@NotNull JitsiMeetConference conference)
    {
        return selectBridge(conference, null);
    }

    /**
     * Returns the number of JVBs known to this bridge selector. Not all of them
     * have to be operational.
     */
    synchronized int getKnownBridgesCount()
    {
        return bridges.size();
    }

    private void notifyBridgeUp(Bridge bridge)
    {
        logger.debug("Propagating new bridge added event: " + bridge.getJid());

        eventEmitter.fireEventAsync(handler ->
        {
            handler.bridgeAdded(bridge);
            return Unit.INSTANCE;
        });
    }

    private void notifyBridgeDown(Bridge bridge)
    {
        logger.debug("Propagating bridge went down event: " + bridge.getJid());

        eventEmitter.fireEventAsync(handler ->
        {
            handler.bridgeRemoved(bridge);
            return Unit.INSTANCE;
        });
    }

    public void stop()
    {
        jvbDoctor.stop();
    }

    /**
     * @return the {@link Bridge} for the bridge with a particular XMPP JID.
     * @param jid the JID of the bridge.
     */
    public Bridge getBridge(Jid jid)
    {
        synchronized (bridges)
        {
            return bridges.get(jid);
        }
    }

    public int getBridgeCount()
    {
        return bridges.size();
    }

    /**
     * @return a copy of the bridges list.
     */
    public List<Bridge> getBridges()
    {
        return new ArrayList<>(bridges.values());
    }

    public int getInGracefulShutdownBridgeCount()
    {
        return (int) bridges.values().stream().filter(Bridge::isInGracefulShutdown).count();
    }

    public int getOperationalBridgeCount()
    {
        return (int) bridges.values().stream()
            .filter(Bridge::isOperational)
            .filter(not(Bridge::isInGracefulShutdown)).count();
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we'll merge stats from different "child" objects here.
        JSONObject stats = bridgeSelectionStrategy.getStats();
        stats.put("bridge_count", getBridgeCount());
        stats.put("operational_bridge_count", getOperationalBridgeCount());
        stats.put("in_shutdown_bridge_count", getInGracefulShutdownBridgeCount());

        return stats;
    }

    public void addHandler(EventHandler eventHandler)
    {
        eventEmitter.addHandler(eventHandler);
    }
    public void removeHandler(EventHandler eventHandler)
    {
        eventEmitter.removeHandler(eventHandler);
    }

    public interface EventHandler
    {
        void bridgeRemoved(Bridge bridge);
        void bridgeAdded(Bridge bridge);
    }
}
