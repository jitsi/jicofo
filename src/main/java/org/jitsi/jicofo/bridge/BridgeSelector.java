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

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.stream.*;

/**
 * Class exposes methods for selecting best videobridge from all currently
 * available. Videobridge state is tracked through PubSub notifications and
 * based on feedback from Jitsi Meet conference focus.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class BridgeSelector
    implements SubscriptionListener,
               EventHandler
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

    /**
     * Property used to configure mapping of videobridge JIDs to PubSub nodes.
     * Single mapping is defined by writing videobridge JID followed by ':' and
     * pub-sub node name. If multiple mapping are to be appended then ';' must
     * be used to separate each mapping.
     *
     * org.jitsi.focus.BRIDGE_PUBSUB_MAPPING
     * =jvb1.server.net:pubsub1;jvb2.server.net:pubsub2;jvb3.server.net:pubsub3
     *
     * PubSub service node is discovered automatically for now and the first one
     * that offer PubSub feature is selected. Then this selector class
     * subscribes for all mapped PubSub nodes on that service for notifications.
     *
     * FIXME: we do not unsubscribe from pubsub notifications on shutdown
     */
    public static final String BRIDGE_TO_PUBSUB_PNAME
        = "org.jitsi.focus.BRIDGE_PUBSUB_MAPPING";

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
     * Operation set used to subscribe to PubSub nodes notifications.
     */
    private final OperationSetSubscription subscriptionOpSet;

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
     * The map of Pub-Sub nodes to videobridge JIDs.
     */
    private final Map<String, Jid> pubSubToBridge = new HashMap<>();

    /**
     * The bridge selection strategy.
     */
    private final BridgeSelectionStrategy bridgeSelectionStrategy;

    /**
     * Creates new instance of {@link BridgeSelector}.
     *
     * @param subscriptionOpSet the operations set that will be used by this
     *                          instance to subscribe to pub-sub notifications.
     */
    public BridgeSelector(OperationSetSubscription subscriptionOpSet)
    {
        this.subscriptionOpSet
            = Objects.requireNonNull(subscriptionOpSet, "subscriptionOpSet");

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
                    Class clazz = Class.forName(clazzName);
                    strategy = (BridgeSelectionStrategy)clazz.newInstance();
                }
                catch (ClassNotFoundException | InstantiationException
                        | IllegalAccessException e)
                {
                }

                if (strategy == null)
                {
                    try
                    {
                        Class clazz =
                            Class.forName(
                                getClass().getPackage().getName() + "." + clazzName);
                        strategy = (BridgeSelectionStrategy)clazz.newInstance();
                    }
                    catch (ClassNotFoundException | InstantiationException
                            | IllegalAccessException e)
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
     * Adds next Jitsi Videobridge XMPP address to be observed by this selected
     * and taken into account in best bridge selection process. If a bridge
     * with the given JID already exists, it is returned and a new instance is
     * not created.
     *
     * @param bridgeJid the JID of videobridge to be added to this selector's
     * set of videobridges.
     * @return the {@link Bridge} for the bridge with the provided JID.
     */
    public Bridge addJvbAddress(Jid bridgeJid)
    {
        return addJvbAddress(bridgeJid, null);
    }

    /**
     * Adds next Jitsi Videobridge XMPP address to be observed by this selected
     * and taken into account in best bridge selection process. If a bridge
     * with the given JID already exists, it is returned and a new instance is
     * not created.
     *
     * @param bridgeJid the JID of videobridge to be added to this selector's
     * set of videobridges.
     * @param version the {@link Version} IQ instance which contains the info
     * about JVB version.
     * @return the {@link Bridge} for the bridge with the provided JID.
     */
    synchronized public Bridge addJvbAddress(
            Jid bridgeJid, Version version)
    {
        if (isJvbOnTheList(bridgeJid))
        {
            return bridges.get(bridgeJid);
        }

        logger.info("Added videobridge: " + bridgeJid + " v: " + version);

        String pubSubNode = findNodeForBridge(bridgeJid);
        if (pubSubNode != null)
        {
            logger.info(
                "Subscribing to pub-sub notifications to "
                    + pubSubNode + " for " + bridgeJid);
            subscriptionOpSet.subscribe(pubSubNode, this);
        }
        else
        {
            logger.warn("No pub-sub node mapped for " + bridgeJid);
        }

        Bridge newBridge = new Bridge(this, bridgeJid, version);

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

        String pubSubNode = findNodeForBridge(bridgeJid);
        if (pubSubNode != null)
        {
            logger.info(
                "Removing PubSub subscription to "
                    + pubSubNode + " for " + bridgeJid);

            subscriptionOpSet.unSubscribe(pubSubNode, this);
        }

        if (bridge != null)
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
     * Returns videobridge JID for given pub-sub node.
     *
     * @param pubSubNode the pub-sub node name.
     *
     * @return videobridge JID for given pub-sub node or <tt>null</tt> if no
     *         mapping found.
     */
    synchronized Jid getBridgeForPubSubNode(String pubSubNode)
    {
        Bridge bridge = findBridgeForNode(pubSubNode);
        return bridge != null ? bridge.getJid() : null;
    }

    /**
     * Finds <tt>Bridge</tt> for given pub-sub node.
     *
     * @param pubSubNode the name of pub-sub node to match with the bridge.
     *
     * @return <tt>Bridge</tt> for given pub-sub node name.
     */
    private synchronized Bridge findBridgeForNode(String pubSubNode)
    {
        Jid bridgeJid = pubSubToBridge.get(pubSubNode);
        if (bridgeJid != null)
        {
            return bridges.get(bridgeJid);
        }
        return null;
    }

    /**
     * Finds pub-sub node name for given videobridge JID.
     *
     * @param bridgeJid the JID of videobridge to be matched with
     * pub-sub node name.
     *
     * @return name of pub-sub node mapped for given videobridge JID.
     */
    private synchronized String findNodeForBridge(Jid bridgeJid)
    {
        for (Map.Entry<String, Jid> psNodeToBridge
            : pubSubToBridge.entrySet())
        {
            if (psNodeToBridge.getValue().equals(bridgeJid))
            {
                return psNodeToBridge.getKey();
            }
        }
        return null;
    }

    /**
     * Method called by {@link org.jitsi.jicofo.ComponentsDiscovery
     * .ThroughPubSubDiscovery} whenever we receive stats update on shared
     * PubSub node used to discover bridges.
     * @param itemId stats item ID. Should be the JID of JVB instance.
     * @param payload JVB stats payload.
     */
    public void onSharedNodeUpdate(String itemId, ExtensionElement payload)
    {
        onSubscriptionUpdate(null, itemId, payload);
    }

    /**
     * Pub-sub notification processing logic.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void onSubscriptionUpdate(String           node,
                                                  String           itemId,
                                                  ExtensionElement payload)
    {
        if (!(payload instanceof ColibriStatsExtension))
        {
            logger.error(
                "Unexpected pub-sub notification payload: "
                    + payload.getClass().getName());
            return;
        }

        Bridge bridge = null;
        if (node != null)
        {
            bridge = findBridgeForNode(node);
        }

        if (bridge == null)
        {
            Jid bridgeId;
            try
            {
                bridgeId = JidCreate.from(itemId);
            }
            catch (XmppStringprepException e)
            {
                logger.warn(
                        "Received PubSub update for unknown bridge: "
                                + itemId + " node: "
                                + (node == null ? "'shared'" : node));
                return;
            }

            // Try to figure out bridge by itemId
            bridge = bridges.get(bridgeId);
            if (bridge == null)
            {
                logger.warn(
                        "Received PubSub update for unknown bridge: "
                            + itemId + " node: "
                            + (node == null ? "'shared'" : node));
                return;
            }
        }

        bridge.setStats((ColibriStatsExtension) payload);
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
     * @throws IllegalArgumentException if given threshold value is equal or
     *         less than zero.
     */
    void setFailureResetThreshold(long failureResetThreshold)
    {
        if (failureResetThreshold <= 0)
        {
            throw new IllegalArgumentException(
                "Bridge failure reset threshold" +
                    " must be greater than 0, given value: " +
                    failureResetThreshold);
        }
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
        case BridgeEvent.VIDEOSTREAMS_CHANGED:
            bridge.onVideoStreamsChanged(bridgeEvent.getVideoStreamCount());
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

        String mappingPropertyValue = config.getString(BRIDGE_TO_PUBSUB_PNAME);

        if (!StringUtils.isNullOrEmpty(mappingPropertyValue))
        {
            String[] pairs = mappingPropertyValue.split(";");
            for (String pair : pairs)
            {
                String[] bridgeAndNode = pair.split(":");
                if (bridgeAndNode.length != 2)
                {
                    logger.error("Invalid mapping element: " + pair);
                    continue;
                }

                Jid bridge = null;
                try
                {
                    bridge = JidCreate.from(bridgeAndNode[0]);
                }
                catch (XmppStringprepException e)
                {
                    logger.error("Invalid mapping element: " + pair);
                }

                String pubSubNode = bridgeAndNode[1];
                pubSubToBridge.put(pubSubNode, bridge);

                logger.info("Pub-sub mapping: " + pubSubNode + " -> " + bridge);
            }
        }

        setFailureResetThreshold(
                config.getLong(
                        BRIDGE_FAILURE_RESET_THRESHOLD_PNAME,
                        DEFAULT_FAILURE_RESET_THRESHOLD));
        logger.info(
            "Bridge failure reset threshold: " + getFailureResetThreshold());

        bridgeSelectionStrategy.setLocalRegion(
                config.getString(LOCAL_REGION_PNAME, null));
        logger.info("Local region: " + bridgeSelectionStrategy.getLocalRegion());

        this.eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin == null)
        {
            throw new IllegalStateException("EventAdmin service not found");
        }

        this.handlerRegistration = EventUtil.registerEventHandler(
            FocusBundleActivator.bundleContext,
            new String[] {
                BridgeEvent.VIDEOSTREAMS_CHANGED
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

}
