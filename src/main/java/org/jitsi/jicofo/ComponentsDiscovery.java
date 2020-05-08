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

import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;

import org.jxmpp.jid.*;

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
    private static final long DEFAULT_REDISCOVERY_INT = -1;

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
     * @param protocolProviderHandler protocol provider handler that provides
     *                                XMPP connection
     * @throws java.lang.IllegalStateException if started already.
     */
    public void start(DomainBareJid           xmppDomain,
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
    }

    private void cancelRediscovery()
    {
        if (rediscoveryTimer != null)
        {
            rediscoveryTimer.cancel();
            rediscoveryTimer = null;
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

                String verStr
                    = version != null
                        ? version.getNameVersionOsString() : "null";
                logger.info(
                    "New component discovered: " + node + ", " + verStr);

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
}
