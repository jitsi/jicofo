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

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.xmpp.extensions.colibri.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.event.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.*;

import org.jitsi.utils.logging.*;
import org.jivesoftware.smack.packet.*;

import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;
import org.osgi.framework.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

/**
 * Class exposes methods for selecting best videobridge from all currently
 * available.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class BridgeSelector
    implements EventHandler
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(BridgeSelector.class);

    /**
     * The name of the property which controls the
     * {@link BridgeSelectionStrategy} to be used by this
     * {@link BridgeSelector}.
     */
    public static final String BRIDGE_SELECTION_STRATEGY_PNAME
        = "org.jitsi.jicofo.BridgeSelector.BRIDGE_SELECTION_STRATEGY";

    public static final String MAX_PARTICIPANTS_PER_BRIDGE_PNAME
        = "org.jitsi.jicofo.BridgeSelector.MAX_PARTICIPANTS_PER_BRIDGE";

    public static final String MAX_BRIDGE_PACKET_RATE_PNAME
            = "org.jitsi.jicofo.BridgeSelector.MAX_BRIDGE_PACKET_RATE";

    public static final String AVG_PARTICIPANT_PACKET_RATE_PNAME
            = "org.jitsi.jicofo.BridgeSelector.AVG_PARTICIPANT_PACKET_RATE";

    /**
     * Configuration property which specifies the amount of time since bridge
     * instance failure before the selector will give it another try.
     */
    public static final String BRIDGE_FAILURE_RESET_THRESHOLD_PNAME
        = "org.jitsi.focus.BRIDGE_FAILURE_RESET_THRESHOLD";

    /**
     * The name of the property which configured the local region.
     */
    public static final String LOCAL_REGION_PNAME
        = "org.jitsi.jicofo.BridgeSelector.LOCAL_REGION";

    /**
     * Five minutes.
     */
    public static final long DEFAULT_FAILURE_RESET_THRESHOLD = 5L * 60L * 1000L;

    /**
     * Stores reference to <tt>EventHandler</tt> registration, so that it can be
     * unregistered on {@link #dispose()}.
     */
    private ServiceRegistration<EventHandler> handlerRegistration;

    /**
     * The amount of time we will wait after bridge instance failure before it
     * will get another chance.
     */
    private long failureResetThreshold = DEFAULT_FAILURE_RESET_THRESHOLD;

    /**
     * The map of bridge JID to <tt>Bridge</tt>.
     */
    private final Map<Jid, Bridge> bridges = new HashMap<>();

    /**
     * The <tt>EventAdmin</tt> used by this instance to fire/send
     * <tt>BridgeEvent</tt>s.
     */
    private EventAdmin eventAdmin;

    /**
     * The bridge selection strategy.
     */
    private final BridgeSelectionStrategy bridgeSelectionStrategy;

    /**
     * Creates new instance of {@link BridgeSelector}.
     *
     */
    public BridgeSelector()
    {
        bridgeSelectionStrategy
            = Objects.requireNonNull(createBridgeSelectionStrategy());
        logger.info("Using " + bridgeSelectionStrategy.getClass().getName());
    }

    /**
     * Creates a {@link BridgeSelectionStrategy} for this {@link BridgeSelector}.
     * The class that will be instantiated is based on configuration.
     */
    private BridgeSelectionStrategy createBridgeSelectionStrategy()
    {
        BridgeSelectionStrategy strategy = null;

        ConfigurationService config = FocusBundleActivator.getConfigService();
        if (config != null)
        {
            String clazzName
                = config.getString(BRIDGE_SELECTION_STRATEGY_PNAME);
            if (clazzName != null)
            {
                try
                {
                    Class<?> clazz = Class.forName(clazzName);
                    strategy = (BridgeSelectionStrategy)clazz.getConstructor().newInstance();
                }
                catch (ClassNotFoundException | InstantiationException |
                    IllegalAccessException | NoSuchMethodException |
                    InvocationTargetException e)
                {
                }

                if (strategy == null)
                {
                    try
                    {
                        Class<?> clazz =
                            Class.forName(
                                getClass().getPackage().getName() + "." + clazzName);
                        strategy = (BridgeSelectionStrategy)clazz.getConstructor().newInstance();
                    }
                    catch (ClassNotFoundException | InstantiationException |
                        IllegalAccessException | NoSuchMethodException |
                        InvocationTargetException e)
                    {
                        logger.error("Failed to find class for: " + clazzName, e);
                    }
                }
            }
        }

        if (strategy == null)
        {
            strategy = new SingleBridgeSelectionStrategy();
        }


        return strategy;
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
        return addJvbAddress(bridgeJid, null, null);
    }

    /**
     * Adds a brige to this selector and sets it's version. If a bridge with
     * the given JID already exists, it does nothing.
     *
     * @return the {@link Bridge} instance for thee given JID.
     */
    public Bridge addJvbAddress(Jid bridgeJid, Version version)
    {
        return addJvbAddress(bridgeJid, version, null);
    }

    /**
     * Adds a bridge to this selector, or if a bridge with the given JID
     * already exists updates its stats.
     *
     * @param bridgeJid the JID of videobridge to be added to this selector's
     * set of videobridges.
     * @param version the {@link Version} IQ instance which contains the info
     * about JVB version.
     * @param stats the last reported statistics
     * @return the {@link Bridge} instance for thee given JID.
     */
    synchronized public Bridge addJvbAddress(
            Jid bridgeJid, Version version, ColibriStatsExtension stats)
    {
        Bridge bridge = bridges.get(bridgeJid);
        if (bridge != null)
        {
            bridge.setStats(stats);
            return bridge;
        }

        Bridge newBridge = new Bridge(this, bridgeJid, version);
        if (stats != null)
        {
            newBridge.setStats(stats);
        }
        logger.info("Added new videobridge: " + newBridge);
        bridges.put(bridgeJid, newBridge);

        notifyBridgeUp(newBridge);
        return newBridge;
    }

    /**
     * Returns <tt>true</tt> if given JVB XMPP address is already known to this
     * <tt>BridgeSelector</tt>.
     *
     * @param jvbJid the JVB JID to be checked eg. jitsi-videobridge.example.com
     *
     * @return <tt>true</tt> if given JVB XMPP address is already known to this
     * <tt>BridgeSelector</tt>.
     */
    public synchronized boolean isJvbOnTheList(Jid jvbJid)
    {
        return bridges.containsKey(jvbJid);
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
            notifyBridgeOffline(bridge);
        }
    }

    public void healthCheckFailed(Jid bridgeJid)
    {
        Bridge bridge = bridges.get(bridgeJid);

        if (bridge != null)
        {
            bridge.setIsOperational(false);
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
            String participantRegion,
            boolean allowMultiBridge)
    {
        List<Bridge> bridges
            = getPrioritizedBridgesList().stream()
                .filter(Bridge::isOperational)
                .collect(Collectors.toList());
        return bridgeSelectionStrategy.select(
            bridges,
            conference.getBridges(),
            participantRegion,
            allowMultiBridge);
    }

    /**
     * Selects a bridge to be used for a specific {@link JitsiMeetConference}.
     *
     * @param conference the conference for which a bridge is to be selected.
     * @return the selected bridge, represented by its {@link Bridge}.
     */
    public Bridge selectBridge(
            @NotNull JitsiMeetConference conference)
    {
        return selectBridge(conference, null, false);
    }

    /**
     * Returns the list of all known videobridges JIDs ordered by load and
     * *operational* status. Not operational bridges are at the end of the list.
     */
    private List<Bridge> getPrioritizedBridgesList()
    {
        ArrayList<Bridge> bridgeList;
        synchronized (this)
        {
            bridgeList = new ArrayList<>(bridges.values());
        }
        Collections.sort(bridgeList);

        bridgeList.removeIf(bridge -> !bridge.isOperational());

        return bridgeList;
    }

    /**
     * The time since last bridge failure we will wait before it gets another
     * chance.
     *
     * @return failure reset threshold in millis.
     */
    long getFailureResetThreshold()
    {
        return failureResetThreshold;
    }

    /**
     * Sets the amount of time we will wait after bridge failure before it will
     * get another chance.
     *
     * @param failureResetThreshold the amount of time in millis.
     *
     */
    void setFailureResetThreshold(long failureResetThreshold)
    {
        this.failureResetThreshold = failureResetThreshold;
    }

    /**
     * Returns the number of JVBs known to this bridge selector. Not all of them
     * have to be operational.
     */
    synchronized int getKnownBridgesCount()
    {
        return bridges.size();
    }

    /**
     * Lists all operational JVB instance JIDs currently known to this
     * <tt>BridgeSelector</tt> instance.
     *
     * @return a <tt>List</tt> of <tt>String</tt> with bridges JIDs.
     */
    synchronized public List<Jid> listActiveJVBs()
    {
        ArrayList<Jid> listing = new ArrayList<>(bridges.size());
        for (Bridge bridge : bridges.values())
        {
            if (bridge.isOperational())
            {
                listing.add(bridge.getJid());
            }
        }
        return listing;
    }

    private void notifyBridgeUp(Bridge bridge)
    {
        logger.debug("Propagating new bridge added event: " + bridge.getJid());

        eventAdmin.postEvent(BridgeEvent.createBridgeUp(bridge.getJid()));
    }

    private void notifyBridgeDown(Bridge bridge)
    {
        logger.debug("Propagating bridge went down event: " + bridge.getJid());

        eventAdmin.postEvent(BridgeEvent.createBridgeDown(bridge.getJid()));
    }

    private void notifyBridgeOffline(Bridge bridge)
    {
        logger.debug("Propagating bridge went offline event: " + bridge.getJid());

        eventAdmin.postEvent(BridgeEvent.createBridgeOffline(bridge.getJid()));
    }

    /**
     * Handles {@link BridgeEvent#VIDEOSTREAMS_CHANGED}.
     * @param event <tt>BridgeEvent</tt>
     */
    @Override
    synchronized public void handleEvent(Event event)
    {
        String topic = event.getTopic();
        Bridge bridge;
        BridgeEvent bridgeEvent;
        Jid bridgeJid;

        if (!BridgeEvent.isBridgeEvent(event))
        {
            logger.warn("Received non-bridge event: " + event);
            return;
        }

        bridgeEvent = (BridgeEvent) event;
        bridgeJid = bridgeEvent.getBridgeJid();

        bridge = bridges.get(bridgeEvent.getBridgeJid());
        if (bridge == null)
        {
            logger.warn("Unable to handle bridge event for: " + bridgeJid);
            return;
        }

        switch (topic)
        {
        case BridgeEvent.VIDEO_CHANNELS_CHANGED:
            bridge.onVideoChannelsChanged(bridgeEvent.getVideoChannelCount());
            break;
        }
    }


    /**
     * Initializes this instance by loading the config and obtaining required
     * service references.
     */
    public void init()
    {
        ConfigurationService config = FocusBundleActivator.getConfigService();

        setFailureResetThreshold(
                config.getLong(
                        BRIDGE_FAILURE_RESET_THRESHOLD_PNAME,
                        DEFAULT_FAILURE_RESET_THRESHOLD));
        logger.info(
            "Bridge failure reset threshold: " + getFailureResetThreshold());

        bridgeSelectionStrategy.setLocalRegion(
                config.getString(LOCAL_REGION_PNAME, null));
        logger.info("Local region: " + bridgeSelectionStrategy.getLocalRegion());

        int maxParticipantsPerBridge = config.getInt(MAX_PARTICIPANTS_PER_BRIDGE_PNAME, -1);
        if (maxParticipantsPerBridge > 0)
        {
            bridgeSelectionStrategy.setMaxParticipantsPerBridge(maxParticipantsPerBridge);
        }

        int maxBridgePacketRate = config.getInt(MAX_BRIDGE_PACKET_RATE_PNAME, -1);
        if (maxBridgePacketRate > 0)
        {
            Bridge.setMaxTotalPacketRatePps(maxBridgePacketRate);
        }

        int avgParticipantPacketRate = config.getInt(AVG_PARTICIPANT_PACKET_RATE_PNAME, -1);
        if (avgParticipantPacketRate > 0)
        {
            Bridge.setAvgParticipantPacketRatePps(avgParticipantPacketRate);
        }



        this.eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin == null)
        {
            throw new IllegalStateException("EventAdmin service not found");
        }

        this.handlerRegistration = EventUtil.registerEventHandler(
            FocusBundleActivator.bundleContext,
            new String[] {
                BridgeEvent.VIDEO_CHANNELS_CHANGED
            },
            this);
    }

    /**
     * Finds the version of the videobridge identified by given
     * <tt>bridgeJid</tt>.
     *
     * @param bridgeJid the XMPP address of the videobridge for which we want to
     *        obtain the version.
     *
     * @return JVB version or <tt>null</tt> if unknown.
     */
    synchronized public String getBridgeVersion(Jid bridgeJid)
    {
        Bridge bridge = bridges.get(bridgeJid);

        return bridge != null ? bridge.getVersion() : null;
    }

    /**
     * Unregisters any event listeners.
     */
    public void dispose()
    {
        if (handlerRegistration != null)
        {
            handlerRegistration.unregister();
            handlerRegistration = null;
        }
    }

    /**
     * @return the {@link Bridge} for the bridge with a particular XMPP
     * JID.
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

    public int getOperationalBridgeCount()
    {
        return (int) bridges.values().stream().filter(Bridge::isOperational).count();
    }

    public JSONObject getStats()
    {
        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we'll merge stats from different "child" objects here.
        JSONObject stats = bridgeSelectionStrategy.getStats();
        stats.put("bridge_count", getBridgeCount());
        stats.put("operational_bridge_count", getOperationalBridgeCount());

        return stats;
    }
}
