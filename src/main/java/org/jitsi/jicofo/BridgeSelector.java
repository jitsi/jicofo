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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Class exposes methods for selecting best videobridge from all currently
 * available. Videobridge state is tracked through PubSub notifications and
 * based on feedback from Jitsi Meet conference focus.
 *
 * @author Pawel Domas
 */
public class BridgeSelector
    implements SubscriptionListener
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(BridgeSelector.class);

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
     * Five minutes.
     */
    public static final long DEFAULT_FAILURE_RESET_THRESHOLD = 5L * 60L * 1000L;

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
     * The map of bridge JID to <tt>BridgeState</tt>.
     */
    private Map<String, BridgeState> bridges
        = new HashMap<String, BridgeState>();

    /**
     * Pre-configured JVB used as last chance option even if no bridge has been
     * auto-detected on startup.
     */
    private String preConfiguredBridge;

    /**
     * The map of Pub-Sub nodes to videobridge JIDs.
     */
    private Map<String, String> pubSubToBridge = new HashMap<String, String>();

    /**
     * Creates new instance of {@link BridgeSelector}.
     *
     * @param subscriptionOpSet the operations set that will be used by this
     *                          instance to subscribe to pub-sub notifications.
     */
    public BridgeSelector(OperationSetSubscription subscriptionOpSet)
    {
        this.subscriptionOpSet = subscriptionOpSet;

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

                String bridge = bridgeAndNode[0];
                String pubSubNode = bridgeAndNode[1];
                pubSubToBridge.put(pubSubNode, bridge);

                logger.info("Pub-sub mapping: " + pubSubNode + " -> " + bridge);
            }
        }

        setFailureResetThreshold(
            config.getLong( BRIDGE_FAILURE_RESET_THRESHOLD_PNAME,
                            DEFAULT_FAILURE_RESET_THRESHOLD));

        logger.info(
            "Bridge failure reset threshold: " + getFailureResetThreshold());
    }

    /**
     * Adds next Jitsi Videobridge XMPP address to be observed by this selected
     * and taken into account in best bridge selection process.
     *
     * @param bridgeJid the JID of videobridge to be added to this selector's
     *                  set of videobridges.
     */
    synchronized public void addJvbAddress(String bridgeJid)
    {
        if (isJvbOnTheList(bridgeJid))
        {
            return;
        }

        logger.info("Added videobridge: " + bridgeJid);

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

        bridges.put(bridgeJid, new BridgeState(bridgeJid));
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
    synchronized boolean isJvbOnTheList(String jvbJid)
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
    synchronized public void removeJvbAddress(String bridgeJid)
    {
        logger.info("Removing JVB: " + bridgeJid);

        bridges.remove(bridgeJid);

        String pubSubNode = findNodeForBridge(bridgeJid);
        if (pubSubNode != null)
        {
            logger.info(
                "Removing PubSub subscription to "
                    + pubSubNode + " for " + bridgeJid);

            subscriptionOpSet.unSubscribe(pubSubNode, this);
        }
    }

    /**
     * Returns least loaded and *operational* videobridge. By operational it
     * means that it was not reported by any of conference focuses to fail while
     * allocating channels.
     *
     * @return the JID of least loaded videobridge or <tt>null</tt> if there are
     *         no operational bridges currently available.
     */
    synchronized public String selectVideobridge()
    {
        List<BridgeState> bridges = getPrioritizedBridgesList();
        if (bridges.size() == 0)
            return null;

        return bridges.get(0).isOperational() ? bridges.get(0).jid : null;
    }

    /**
     * Returns the list of all known videobridges JIDs ordered by load and
     * *operational* status. Not operational bridges are at the end of the list.
     */
    private List<BridgeState> getPrioritizedBridgesList()
    {
        ArrayList<BridgeState> bridgeList
            = new ArrayList<BridgeState>(bridges.values());

        Collections.sort(bridgeList);

        Iterator<BridgeState> bridgesIter = bridgeList.iterator();

        while (bridgesIter.hasNext())
        {
            BridgeState bridge = bridgesIter.next();
            if (!bridge.isOperational())
                bridgesIter.remove();
        }

        return bridgeList;
    }

    /**
     * Updates given *operational* status of the videobridge identified by given
     * <tt>bridgeJid</tt> address.
     *
     * @param bridgeJid the XMPP address of the bridge.
     * @param isWorking <tt>true</tt> if bridge successfully allocated
     *                  the channels which means it is in *operational* state.
     */
    synchronized public void updateBridgeOperationalStatus(String bridgeJid,
                                              boolean isWorking)
    {
        BridgeState bridge = bridges.get(bridgeJid);
        if (bridge != null)
        {
            bridge.setIsOperational(isWorking);
        }
        else
        {
            logger.warn("No bridge registered for jid: " + bridgeJid);
        }
    }

    /**
     * Returns videobridge JID for given pub-sub node, but only if it has been
     * added using {@link #addJvbAddress(String)} method.
     *
     * @param pubSubNode the pub-sub node name.
     *
     * @return videobridge JID for given pub-sub node.
     */
    synchronized public String getBridgeForPubSubNode(String pubSubNode)
    {
        BridgeState bridge = findBridgeForNode(pubSubNode);
        return bridge != null ? bridge.jid : null;
    }

    /**
     * Finds <tt>BridgeState</tt> for given pub-sub node.
     *
     * @param pubSubNode the name of pub-sub node to match with the bridge.
     *
     * @return <tt>BridgeState</tt> for given pub-sub node name.
     */
    private BridgeState findBridgeForNode(String pubSubNode)
    {
        String bridgeJid = pubSubToBridge.get(pubSubNode);
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
     *                  pub-sub node name.
     *
     * @return name of pub-sub node mapped for given videobridge JID.
     */
    private String findNodeForBridge(String bridgeJid)
    {
        for (Map.Entry<String, String> psNodeToBridge
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
    void onSharedNodeUpdate(String itemId, PacketExtension payload)
    {
        onSubscriptionUpdate(null, itemId, payload);
    }

    /**
     * Pub-sub notification processing logic.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void onSubscriptionUpdate(String          node,
                                                  String          itemId,
                                                  PacketExtension payload)
    {
        if (!(payload instanceof ColibriStatsExtension))
        {
            logger.error(
                "Unexpected pub-sub notification payload: "
                    + payload.getClass().getName());
            return;
        }

        BridgeState bridgeState = null;
        if (node != null)
        {
            bridgeState = findBridgeForNode(node);
        }

        if (bridgeState == null)
        {
            // Try to figure out bridge by itemId
            bridgeState = bridges.get(itemId);
            if (bridgeState == null)
            {
                logger.warn(
                    "No bridge registered or " +
                        "missing mapping for node: " + node);
                return;
            }
        }

        ColibriStatsExtension stats = (ColibriStatsExtension) payload;
        for (PacketExtension child : stats.getChildExtensions())
        {
            if (!(child instanceof ColibriStatsExtension.Stat))
            {
                continue;
            }

            ColibriStatsExtension.Stat stat
                = (ColibriStatsExtension.Stat) child;
            if ("conferences".equals(stat.getName()))
            {
                Integer val = getStatisticIntValue(stat);
                if(val != null)
                    bridgeState.setConferenceCount(val);
            }
            else if ("videochannels".equals(stat.getName()))
            {
                Integer val = getStatisticIntValue(stat);
                if(val != null)
                    bridgeState.setVideoChannelCount(val);
            }
            else if ("videostreams".equals(stat.getName()))
            {
                Integer val = getStatisticIntValue(stat);
                if(val != null)
                    bridgeState.setVideoStreamCount(val);
            }
        }
    }

    /**
     * Extracts the statistic integer value from <tt>currentStats</tt> if
     * available and in correct format.
     * @param currentStats the current stats
     */
    private static Integer getStatisticIntValue(
        ColibriStatsExtension.Stat currentStats)
    {
        Object statValue = currentStats.getValue();
        if (statValue == null)
        {
            return null;
        }
        String stringStatValue = String.valueOf(statValue);
        try
        {
            return new Integer(stringStatValue);
        }
        catch(NumberFormatException e)
        {
            logger.error("Error parsing stat item: " + currentStats.toXML());
        }

        return null;
    }

    /**
     * Returns the JID of pre-configured Jitsi Videobridge instance.
     */
    synchronized public String getPreConfiguredBridge()
    {
        return preConfiguredBridge;
    }

    /**
     * Sets the JID of pre-configured JVB instance which will be used when all
     * auto-detected bridges are down.
     * @param preConfiguredBridge XMPP address of pre-configured JVB component.
     */
    synchronized public void setPreConfiguredBridge(String preConfiguredBridge)
    {
        logger.info("Configuring default bridge: " + preConfiguredBridge);

        addJvbAddress(preConfiguredBridge);

        this.preConfiguredBridge = preConfiguredBridge;
    }

    /**
     * The time since last bridge failure we will wait before it gets another
     * chance.
     *
     * @return failure reset threshold in millis.
     */
    public long getFailureResetThreshold()
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
    public void setFailureResetThreshold(long failureResetThreshold)
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
    public int getKnownBridgesCount()
    {
        return bridges.size();
    }

    /**
     * Class holds videobridge state and implements {@link java.lang.Comparable}
     * interface to find least loaded bridge.
     */
    class BridgeState
        implements Comparable<BridgeState>
    {
        /**
         * Videobridge XMPP address.
         */
        private final String jid;

        /**
         * If not set we consider it highly occupied,
         * because no stats we have been fetched so far.
         */
        private int conferenceCount = Integer.MAX_VALUE;

        /**
         * If not set we consider it highly occupied,
         * because no stats we have been fetched so far.
         */
        private int videoChannelCount = Integer.MAX_VALUE;

        /**
         * If not set we consider it highly occupied,
         * because no stats we have been fetched so far.
         */
        private int videoStreamCount = Integer.MAX_VALUE;

        /**
         * Stores *operational* status which means it has been successfully used
         * by the focus to allocate the channels. It is reset to false when
         * focus fails to allocate channels, but it gets another chance when all
         * currently working bridges go down and might eventually get elevated
         * back to *operational* state.
         */
        private boolean isOperational = true /* we assume it is operational */;

        /**
         * The time when this instance has failed.
         */
        private long failureTimestamp;

        BridgeState(String bridgeJid)
        {
            if (StringUtils.isNullOrEmpty(bridgeJid))
                throw new NullPointerException("bridgeJid");

            this.jid = bridgeJid;
        }

        public void setConferenceCount(int conferenceCount)
        {
            if (this.conferenceCount != conferenceCount)
            {
                logger.info(
                    "Conference count for: " + jid + ": " + conferenceCount);
            }
            this.conferenceCount = conferenceCount;
        }

        public int getConferenceCount()
        {
            return this.conferenceCount;
        }

        /**
         * Return the number of channels used.
         * @return the number of channels used.
         */
        public int getVideoChannelCount()
        {
            return videoChannelCount;
        }

        /**
         * Sets the number of channels used.
         * @param channelCount the number of channels used.
         */
        public void setVideoChannelCount(int channelCount)
        {
            this.videoChannelCount = channelCount;
        }

        /**
         * Returns the number of streams used.
         * @return the number of streams used.
         */
        public int getVideoStreamCount()
        {
            return videoStreamCount;
        }

        /**
         * Sets the stream count currently used.
         * @param streamCount the stream count currently used.
         */
        public void setVideoStreamCount(int streamCount)
        {
            if (this.videoStreamCount != streamCount)
            {
                logger.info(
                    "Video stream count for: " + jid + ": " + streamCount);
            }
            this.videoStreamCount = streamCount;
        }

        public void setIsOperational(boolean isOperational)
        {
            this.isOperational = isOperational;

            if (!isOperational)
            {
                // Remember when the bridge has failed
                failureTimestamp = System.currentTimeMillis();
            }
        }

        public boolean isOperational()
        {
            // Check if we should give this bridge another try
            verifyFailureThreshold();

            return isOperational;
        }

        /**
         * Verifies if it has been long enough since last bridge failure to give
         * it another try(reset isOperational flag).
         */
        private void verifyFailureThreshold()
        {
            if (isOperational)
            {
                return;
            }

            if (System.currentTimeMillis() - failureTimestamp
                    > getFailureResetThreshold())
            {
                logger.info("Resetting operational status for " + jid);
                isOperational = true;
            }
        }

        /**
         * The least value is returned the least the bridge is loaded.
         *
         * {@inheritDoc}
         */
        @Override
        public int compareTo(BridgeState o)
        {
            boolean meOperational = isOperational();
            boolean otherOperational = o.isOperational();

            if (meOperational && !otherOperational)
                return -1;
            else if (!meOperational && otherOperational)
                return 1;

            return videoStreamCount - o.videoStreamCount;
        }
    }
}
