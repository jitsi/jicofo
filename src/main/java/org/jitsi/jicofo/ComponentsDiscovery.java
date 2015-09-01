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
import net.java.sip.communicator.util.Logger;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;

import java.util.*;

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
     * Map of component features.
     */
    private Map<String, List<String>> itemMap
        = new HashMap<String, List<String>>();

    /**
     * Timer which runs re-discovery task.
     */
    private Timer rediscoveryTimer;

    /**
     * XMPP xmppDomain for which we're discovering service info.
     */
    private String xmppDomain;

    /**
     * The protocol service handler that provides XMPP service.
     */
    private ProtocolProviderHandler protocolProviderHandler;

    /**
     * Capabilities operation set used to discover services info.
     */
    private OperationSetSimpleCaps capsOpSet;

    /**
     * Creates new instance of <tt>ComponentsDiscovery</tt>.
     *
     * @param meetServices {@link JitsiMeetServices} instance which will be
     *                     notified about XMPP components available on XMPP
     *                     domain.
     */
    public ComponentsDiscovery(JitsiMeetServices meetServices)
    {
        if (meetServices == null)
            throw new NullPointerException("meetServices");

        this.meetServices = meetServices;
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
    public void start(String                  xmppDomain,
                      ProtocolProviderHandler protocolProviderHandler)
    {
        if (this.protocolProviderHandler != null)
        {
            throw new IllegalStateException("Already started");
        }
        else if (xmppDomain == null)
        {
            throw new NullPointerException("xmppDomain");
        }
        else if (protocolProviderHandler == null)
        {
            throw new NullPointerException("protocolProviderHandler");
        }

        this.xmppDomain = xmppDomain;
        this.protocolProviderHandler = protocolProviderHandler;

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
        long interval = FocusBundleActivator.getConfigService()
            .getLong(REDISCOVERY_INTERVAL_PNAME, DEFAULT_REDISCOVERY_INT);

        if (interval <= 0)
        {
            logger.info("Service rediscovery disabled");
            return;
        }

        if (rediscoveryTimer != null)
        {
            logger.warn(
                "Attempt to schedule rediscovery when it's already done");
            return;
        }

        logger.info("Services re-discovery interval: " + interval);

        rediscoveryTimer = new Timer();

        rediscoveryTimer.schedule(new RediscoveryTask(), interval, interval);
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
        Set<String> nodes = capsOpSet.getItems(xmppDomain);
        if (nodes == null)
        {
            logger.error("Failed to discover services on " + xmppDomain);
            return;
        }

        List<String> onlineNodes = new ArrayList<String>();
        for (String node : nodes)
        {
            List<String> features = capsOpSet.getFeatures(node);

            if (features == null)
            {
                // Component unavailable
                continue;
            }

            // Node is considered online when we get it's feature list
            onlineNodes.add(node);

            if (!itemMap.containsKey(node))
            {
                logger.info("New component discovered: " + node);

                itemMap.put(node, features);

                meetServices.newNodeDiscovered(node, features);
            }
            else if (itemMap.containsKey(node))
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
        List<String> offlineNodes = new ArrayList<String>(itemMap.keySet());

        offlineNodes.removeAll(onlineNodes);
        itemMap.keySet().removeAll(offlineNodes);

        if (offlineNodes.size() > 0)
        {
            // There are disconnected nodes
            for (String offlineNode : offlineNodes)
            {
                logger.info("Component went offline: " + offlineNode);

                meetServices.nodeNoLongerAvailable(offlineNode);
            }
        }
    }

    private void firstTimeDiscovery()
    {
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
