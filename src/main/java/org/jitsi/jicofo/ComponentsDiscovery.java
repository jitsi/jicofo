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

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.event.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.SubscriptionListener;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.pubsub.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class observes components available on XMPP domain, classifies them as JVB,
 * SIP gateway or Jirecon and notifies {@link JitsiMeetServices} whenever new
 * instance becomes available or goes offline.
 *
 * @author Pawel Domas
 */
public class ComponentsDiscovery
    implements RegistrationStateChangeListener
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(ComponentsDiscovery.class);

    /**
     * Re-discovers every 30 seconds.
     */
    private static final long DEFAULT_REDISCOVERY_INT = 30L * 1000L;

    /**
     * The name of configuration property which specifies how often XMPP
     * components re-discovery will be performed. Time interval in millis.
     */
    private final static String REDISCOVERY_INTERVAL_PNAME
        = "org.jitsi.jicofo.SERVICE_REDISCOVERY_INTERVAL";

    /**
     * {@link JitsiMeetServices} which is notified about new components
     * discovered or when one of currently running goes offline.
     */
    private final JitsiMeetServices meetServices;

    /**
     * Maps a node (XMPP address) to the list of its features.
     */
    private Map<Jid, List<String>> itemMap = new ConcurrentHashMap<>();

    /**
     * Timer which runs re-discovery task.
     */
    private Timer rediscoveryTimer;

    /**
     * XMPP xmppDomain for which we're discovering service info.
     */
    private DomainBareJid xmppDomain;

    /**
     * The protocol service handler that provides XMPP service.
     */
    private ProtocolProviderHandler protocolProviderHandler;

    /**
     * Capabilities operation set used to discover services info.
     */
    private OperationSetSimpleCaps capsOpSet;

    /**
     * Detects video bridges based on stats published to PubSub node.
     */
    private ThroughPubSubDiscovery pubSubBridgeDiscovery;

    /**
     * The name of PubSub node where videobridges are publishing their stats.
     */
    private String statsPubSubNode;

    /**
     * The XMPP connection instance used to send requests by this
     * <tt>ComponentsDiscovery</tt> instance.
     */
    private XmppConnection connection;

    /**
     * Creates new instance of <tt>ComponentsDiscovery</tt>.
     *
     * @param meetServices {@link JitsiMeetServices} instance which will be
     *                     notified about XMPP components available on XMPP
     *                     domain.
     */
    public ComponentsDiscovery(JitsiMeetServices meetServices)
    {
        this.meetServices
            = Objects.requireNonNull(meetServices, "meetServices");
    }

    /**
     * Starts this instance.
     *
     * @param xmppDomain server address/main service XMPP xmppDomain that hosts
     *                      the conference system.
     * @param statsPubSubNode the name of PubSub node where video bridges are
     *        publishing their stats. It will be used to discover videobridges
     *        automatically based on item's IDs(each bridges used it's JID as
     *        item ID).
     * @param protocolProviderHandler protocol provider handler that provides
     *                                XMPP connection
     * @throws java.lang.IllegalStateException if started already.
     */
    public void start(DomainBareJid           xmppDomain,
                      String                  statsPubSubNode,
                      ProtocolProviderHandler protocolProviderHandler)
    {
        if (this.protocolProviderHandler != null)
        {
            throw new IllegalStateException("Already started");
        }

        this.xmppDomain = Objects.requireNonNull(xmppDomain, "xmppDomain");
        this.protocolProviderHandler
            = Objects.requireNonNull(
                    protocolProviderHandler, "protocolProviderHandler");

        this.statsPubSubNode = statsPubSubNode;

        this.capsOpSet
            = protocolProviderHandler.getOperationSet(
                    OperationSetSimpleCaps.class);

        if (protocolProviderHandler.isRegistered())
        {
            firstTimeDiscovery();
        }

        // Listen to protocol provider status updates
        protocolProviderHandler.addRegistrationListener(this);
    }

    private void scheduleRediscovery()
    {
        long interval
            = FocusBundleActivator.getConfigService()
                    .getLong(
                        REDISCOVERY_INTERVAL_PNAME, DEFAULT_REDISCOVERY_INT);

        if (interval > 0)
        {
            if (rediscoveryTimer != null)
            {
                logger.warn(
                    "Attempt to schedule rediscovery when it's already done");
                return;
            }

            logger.info("Services re-discovery interval: " + interval);

            rediscoveryTimer = new Timer();

            rediscoveryTimer.schedule(
                new RediscoveryTask(), interval, interval);
        }
        else
        {
            logger.info("Service rediscovery disabled");
        }

        if (pubSubBridgeDiscovery == null
                && !StringUtils.isNullOrEmpty(statsPubSubNode))
        {
            OperationSetSubscription subOpSet
                = protocolProviderHandler.getOperationSet(
                       OperationSetSubscription.class);

            pubSubBridgeDiscovery
                = new ThroughPubSubDiscovery(
                        subOpSet, capsOpSet,
                        FocusBundleActivator.getSharedThreadPool());

            pubSubBridgeDiscovery.start(
                    FocusBundleActivator.bundleContext);
        }
    }

    private void cancelRediscovery()
    {
        if (rediscoveryTimer != null)
        {
            rediscoveryTimer.cancel();
            rediscoveryTimer = null;
        }

        if (pubSubBridgeDiscovery != null)
        {
            pubSubBridgeDiscovery.stop();
            pubSubBridgeDiscovery = null;
        }
    }

    /**
     * Initializes this instance and discovers Jitsi Meet services.
     */
    public void discoverServices()
    {
        Set<Jid> nodes = capsOpSet.getItems(xmppDomain);
        if (nodes == null)
        {
            logger.error("Failed to discover services on " + xmppDomain);
            return;
        }

        List<Jid> onlineNodes = new ArrayList<>();
        for (Jid node : nodes)
        {
            List<String> features = capsOpSet.getFeatures(node);

            if (features == null)
            {
                // Component unavailable
                continue;
            }

            // Node is considered online when we get its feature list
            onlineNodes.add(node);

            if (!itemMap.containsKey(node))
            {
                itemMap.put(node, features);

                // Try discovering version
                Version version
                    = DiscoveryUtil.discoverVersion(connection, node, features);

                logger.info(
                        "New component discovered: " + node + ", " + version);

                meetServices.newNodeDiscovered(node, features, version);
            }
            else
            {
                // Check if there are changes in feature list
                if (!DiscoveryUtil.areTheSame(itemMap.get(node), features))
                {
                    // FIXME: we do not care for feature list change yet, as
                    // components should have constant addresses configured,
                    // but want to detect eventual problems here

                    logger.error("Feature list changed for: " + node);

                    //meetServices.nodeFeaturesChanged(item, features);
                }
            }
        }

        // Find disconnected nodes
        List<Jid> offlineNodes = new ArrayList<>(itemMap.keySet());

        offlineNodes.removeAll(onlineNodes);
        itemMap.keySet().removeAll(offlineNodes);

        if (offlineNodes.size() > 0)
        {
            // There are disconnected nodes
            for (Jid offlineNode : offlineNodes)
            {
                logger.info("Component went offline: " + offlineNode);

                meetServices.nodeNoLongerAvailable(offlineNode);
            }
        }
    }

    private void firstTimeDiscovery()
    {
        this.connection
            = Objects.requireNonNull(
                    protocolProviderHandler.getXmppConnection(), "connection");

        discoverServices();

        scheduleRediscovery();
    }

    /**
     * Stops this instance and disposes XMPP connection.
     */
    public void stop()
    {
        cancelRediscovery();

        if (protocolProviderHandler != null)
        {
            protocolProviderHandler.removeRegistrationListener(this);
        }
    }

    private void setAllNodesOffline()
    {
        for (Jid node : itemMap.keySet())
        {
            logger.info("Connection lost - component offline: " + node);

            meetServices.nodeNoLongerAvailable(node);
        }

        itemMap.clear();
    }

    @Override
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        if (RegistrationState.REGISTERED.equals(evt.getNewState()))
        {
            firstTimeDiscovery();
        }
        else if (RegistrationState.UNREGISTERED.equals(evt.getNewState())
            || RegistrationState.CONNECTION_FAILED.equals(evt.getNewState()))
        {
            cancelRediscovery();

            setAllNodesOffline();
        }
    }

    class RediscoveryTask extends TimerTask
    {

        @Override
        public void run()
        {
            if (!protocolProviderHandler.isRegistered())
            {
                logger.warn(
                    "No XMPP connection - skipping service re-discovery.");
                return;
            }

            discoverServices();
        }
    }

    /**
     * Each bridge is supposed to use it's JID as item ID and this class
     * discovers videobridges by listening to PubSub stats notifications.
     */
    class ThroughPubSubDiscovery
        implements SubscriptionListener,
                   EventHandler
    {
        /**
         * The name of configuration property used to configure max bridge stats
         * age.
         */
        public static final String MAX_STATS_REPORT_AGE_PNAME
            = "org.jitsi.jicofo.MAX_STATS_REPORT_AGE";

        /**
         * 15 seconds by default. If bridge does not publish stats for longer
         * then it is considered offline.
         */
        private long MAX_STATS_REPORT_AGE = 15000L;

        /**
         * PubSub operation set.
         */
        private final OperationSetSubscription subOpSet;

        /**
         * Capabilities operation set used to check bridge features.
         */
        private final OperationSetSimpleCaps capsOpSet;

        /**
         * Maps bridge JID to last received stats timestamp. Used to expire
         * bridge which do not send stats for too long.
         * TODO: move to BridgeSelector?
         */
        private final Map<Jid, Long> bridgesMap = new HashMap<>();

        /**
         * <tt>ScheduledExecutorService</tt> used to run cyclic task of bridge
         * timestamp validation.
         */
        private final ScheduledExecutorService executor;

        /**
         * Cyclic task which validates timestamps and expires bridges.
         */
        private ScheduledFuture<?> expireTask;

        /**
         * <tt>EventHandler</tt> registration.
         */
        private ServiceRegistration<EventHandler> evtHandlerReg;

        /**
         * Creates new Instance of <tt>ThroughPubSubDiscovery</tt>.
         * @param subscriptionOpSet subscription operation set instance.
         * @param capsOpSet capabilities operation set instance.
         * @param executor scheduled executor service which will be used to run
         *                 cyclic task.
         */
        public ThroughPubSubDiscovery(OperationSetSubscription subscriptionOpSet,
                                      OperationSetSimpleCaps capsOpSet,
                                      ScheduledExecutorService executor)
        {
            this.subOpSet = subscriptionOpSet;
            this.capsOpSet = capsOpSet;
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        /**
         * Starts <tt>ThroughPubSubDiscovery</tt>.
         */
        synchronized void start(BundleContext osgiCtx)
        {
            logger.info(
                "Bridges will be discovered through" +
                    " PubSub stats on node: " + statsPubSubNode);

            this.MAX_STATS_REPORT_AGE
                = FocusBundleActivator.getConfigService()
                    .getLong(MAX_STATS_REPORT_AGE_PNAME, MAX_STATS_REPORT_AGE);

            if (MAX_STATS_REPORT_AGE <= 0)
                throw new IllegalArgumentException(
                        "MAX STATS AGE: " + MAX_STATS_REPORT_AGE);

            logger.info("Max stats age: " + MAX_STATS_REPORT_AGE +" ms");

            // Fetch all items already discovered
            List<PayloadItem> items = subOpSet.getItems(statsPubSubNode);
            if (items != null)
            {
                for (PayloadItem item : items)
                {
                    // Potential bridge JID may be carried in item ID
                    try
                    {
                        Jid bridgeId = JidCreate.from(item.getId());
                        verifyJvbJid(bridgeId);
                    }
                    catch (XmppStringprepException e)
                    {
                        e.printStackTrace();
                    }
                }
            }

            subOpSet.subscribe(statsPubSubNode, this);

            this.expireTask = executor.scheduleAtFixedRate(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            validateBridges();
                        }
                        catch (Throwable e)
                        {
                            logger.error(e, e);
                        }
                    }
                },
                MAX_STATS_REPORT_AGE / 2,
                MAX_STATS_REPORT_AGE / 2,
                TimeUnit.MILLISECONDS);

            evtHandlerReg
                = EventUtil.registerEventHandler(
                        osgiCtx,
                        new String[] { BridgeEvent.BRIDGE_DOWN },
                        this);
        }

        /**
         * Stops <tt>ThroughPubSubDiscovery</tt>.
         */
        synchronized void stop()
        {
            if (evtHandlerReg != null)
            {
                evtHandlerReg.unregister();
                evtHandlerReg = null;
            }

            subOpSet.unSubscribe(statsPubSubNode, this);

            Iterator<Map.Entry<Jid, Long>> bridges
                = bridgesMap.entrySet().iterator();
            while (bridges.hasNext())
            {
                Map.Entry<Jid, Long> bridge = bridges.next();

                bridges.remove();

                bridgeWentOffline(bridge.getKey());
            }

            bridgesMap.clear();

            if (expireTask != null)
            {
                expireTask.cancel(true);
            }
        }

        /**
         * Validates bridges timestamps and expires them.
         */
        synchronized void validateBridges()
        {
            Iterator<Map.Entry<Jid, Long>> bridges
                = bridgesMap.entrySet().iterator();

            while (bridges.hasNext())
            {
                Map.Entry<Jid, Long> bridge = bridges.next();
                if (System.currentTimeMillis() - bridge.getValue()
                    > MAX_STATS_REPORT_AGE)
                {
                    Jid bridgeJid = bridge.getKey();

                    logger.info(
                        "No stats seen from " + bridgeJid + " for too long");

                    bridges.remove();

                    bridgeWentOffline(bridge.getKey());
                }
            }
        }

        @Override
        synchronized public void handleEvent(Event event)
        {
            if (!(event instanceof BridgeEvent))
            {
                return;
            }

            // We need to remove JVB mapping if the bridge went "down" for
            // external reasons, so that we will re-discover it correctly if it
            // starts sending stats before we timeout it in 'validateBridges'.
            BridgeEvent bridgeEvent = (BridgeEvent) event;
            if (BridgeEvent.BRIDGE_DOWN.equals(bridgeEvent.getTopic()))
            {
                Jid bridgeJid = bridgeEvent.getBridgeJid();
                if (bridgesMap.remove(bridgeJid) != null)
                {
                    logger.info("Cleared info about: " + bridgeJid);
                }
            }
        }

        /**
         * Verifies if given JID belongs to Jitsi videobridge XMPP component.
         * @param bridgeJid the JID to be verified.
         */
        private void verifyJvbJid(Jid bridgeJid)
        {
            // Refresh bridge timestamp if it was discovered previously
            if (bridgesMap.containsKey(bridgeJid))
            {
                // This indicate that the bridge is alive
                refreshBridgeTimestamp(bridgeJid);
                return;
            }
            // Is it JVB ?
            if (!bridgeJid.toString().contains("."))
                return;

            List<String> jidFeatures = capsOpSet.getFeatures(bridgeJid);
            if (jidFeatures == null)
            {
                logger.warn(
                        "Failed to discover features for: " + bridgeJid);
                return;
            }

            if (JitsiMeetServices.isJitsiVideobridge(jidFeatures))
            {
                logger.info("Bridge discovered from PubSub: " + bridgeJid);

                refreshBridgeTimestamp(bridgeJid);

                Version jvbVersion
                    = DiscoveryUtil.discoverVersion(
                            connection, bridgeJid, jidFeatures);

                meetServices.newBridgeDiscovered(bridgeJid, jvbVersion);
            }
        }
        private void refreshBridgeTimestamp(Jid bridgeJid)
        {
            bridgesMap.put(bridgeJid, System.currentTimeMillis());
        }

        private void bridgeWentOffline(Jid bridgeJid)
        {
            meetServices.nodeNoLongerAvailable(bridgeJid);
        }

        @Override
        public void onSubscriptionUpdate(
            String node, String itemId, ExtensionElement payload)
        {
            Jid bridgeId;
            try
            {
                bridgeId = JidCreate.from(itemId);
            }
            catch (XmppStringprepException e)
            {
                logger.error("Invalid itemId", e);
                return;
            }

            synchronized (this)
            {
                // JVB unavailable ?
                if ("service-unavailable".equals(payload.getElementName()))
                {
                    logger.info(
                            "Service unavailable through PubSub for " + bridgeId);

                    if (bridgesMap.remove(bridgeId) != null)
                    {
                        bridgeWentOffline(bridgeId);
                    }
                    return;
                }

                // Potential bridge JID may be carried in item ID
                verifyJvbJid(bridgeId);

                // Trigger PubSub update for the shared node on BridgeSelector
                BridgeSelector selector = meetServices.getBridgeSelector();
                if (selector != null)
                {
                    selector.onSharedNodeUpdate(itemId, payload);
                }
            }
        }
    }

}
