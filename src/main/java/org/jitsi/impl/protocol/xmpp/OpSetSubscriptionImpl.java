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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.util.*;

import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;

import org.jitsi.retry.*;
import org.jitsi.service.configuration.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.listener.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * XMPP Pub-sub node implementation of {@link OperationSetSubscription}.
 *
 * @author Pawel Domas
 */
public class OpSetSubscriptionImpl
    implements OperationSetSubscription,
               ItemEventListener
{
    /**
     * The logger used by this instance.
     */
    private final static Logger logger
        = Logger.getLogger(OpSetSubscriptionImpl.class);

    /**
     * The configuration property used to specify address for pub-sub node.
     */
    public static final String PUBSUB_ADDRESS_PNAME
        = "org.jitsi.focus.pubsub.ADDRESS";

    /**
     * The name of configuration property which specifies re-try interval for
     * PubSub subscribe operation.
     */
    public static final String PUBUSB_RETRY_INT_PNAME
        = "org.jitsi.focus.pubsub.RETRY_INTERVAL";

    /**
     * Smack PubSub manager.
     */
    private PubSubManager manager;

    /**
     * PubSub address used.
     */
    private final String pubSubAddress;

    /**
     * Our JID used for PubSub registration.
     */
    private String ourJid;

    /**
     * The map of Subscriptions
     */
    private Map<String, Subscription> subscriptionsMap
        = new HashMap<String, Subscription>();

    /**
     * Parent XMPP provider used to handle the protocol.
     */
    private final XmppProtocolProvider parentProvider;

    /**
     * How often do we retry PubSub subscribe operation(in ms).
     */
    private Long retryInterval;

    /**
     * Creates new instance of {@link OpSetSubscriptionImpl}.
     *
     * @param parentProvider the XMPP provider instance.
     */
    public OpSetSubscriptionImpl(XmppProtocolProvider parentProvider)
    {
        this.parentProvider = parentProvider;

        this.pubSubAddress
            = FocusBundleActivator.getConfigService()
                    .getString(PUBSUB_ADDRESS_PNAME);
    }

    /**
     * Returns retry interval for PuSub subscribe operation in ms.
     */
    private Long getRetryInterval()
    {
        if (retryInterval == null)
        {
            ConfigurationService config
                = ServiceUtils.getService(
                        FocusBundleActivator.bundleContext,
                        ConfigurationService.class);

            retryInterval = config.getLong(PUBUSB_RETRY_INT_PNAME, 15000L);
        }
        return retryInterval;
    }

    /**
     * Lazy initializer for our JID field(it's not available before provider
     * gets connected).
     */
    private String getOurJid()
    {
        if (ourJid == null)
        {
            ourJid = parentProvider.getOurJid();
        }
        return ourJid;
    }

    /**
     * Lazy initializer for PubSub manager.
     */
    private PubSubManager getManager()
    {
        if (manager == null)
        {
            manager
                = new PubSubManager(
                        parentProvider.getConnection(),
                        pubSubAddress);
        }
        return manager;
    }

    /**
     * Checks if given <tt>jid</tt> is registered for PubSub updates on given
     * <tt>node</tt>.
     */
    private boolean isSubscribed(String jid, Node node)
        throws XMPPException
    {
        // FIXME: consider using local flag rather than getting the list
        // of subscriptions
        for (org.jivesoftware.smackx.pubsub.Subscription subscription
                    : node.getSubscriptions())
        {
            if (subscription.getJid().equals(jid))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void subscribe(String               node,
                                       SubscriptionListener listener)
    {
        Subscription subscription = subscriptionsMap.get(node);
        if (subscription == null)
        {
            subscription = new Subscription(node, listener);
            subscriptionsMap.put(node, subscription);
            subscription.subscribe();
        }
        else
        {
            subscription.addListener(listener);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void unSubscribe(String               node,
                                         SubscriptionListener listener)
    {
        Subscription subscription = subscriptionsMap.get(node);
        if (subscription != null)
        {
            if (!subscription.removeListener(listener))
            {
                subscription.unSubscribe();
                subscriptionsMap.remove(node);
            }
        }
        else
        {
            logger.warn("No PubSub subscription for " + node);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PayloadItem> getItems(String nodeName)
    {
        try
        {
            Node pubSubNode = getManager().getNode(nodeName);
            if (pubSubNode instanceof LeafNode)
            {
                LeafNode leafPubSubNode = (LeafNode) pubSubNode;
                return leafPubSubNode.getItems();
            }
        }
        catch (XMPPException e)
        {
            logger.error(
                "Failed to fetch PubSub items of: " + nodeName +
                ", reason: " + e);
        }
        return null;
    }

    /**
     * Fired by Smack on PubSub update.
     *
     * {@inheritDoc}
     */
    @Override
    public void handlePublishedItems(ItemPublishEvent event)
    {
        String nodeId = event.getNodeId();

        if (logger.isDebugEnabled())
            logger.debug("PubSub update for node: " + nodeId);

        Subscription subscription = subscriptionsMap.get(nodeId);

        if (subscription != null)
        {
            for(Object item : event.getItems())
            {
                if(!(item instanceof PayloadItem))
                    continue;

                PayloadItem payloadItem = (PayloadItem) item;
                subscription.notifyListeners(payloadItem);
            }
        }
    }

    /**
     * Class holds info about PubSub subscription and implement subscribe
     * re-try logic.
     */
    class Subscription
    {
        /**
         * The address of PubSub Node.
         */
        private final String node;

        /**
         * Subscription listeners.
         */
        private final List<SubscriptionListener> listeners
            = new CopyOnWriteArrayList<SubscriptionListener>();

        /**
         * Retry strategy for subscribe operation.
         */
        private final RetryStrategy retryStrategy;

        /**
         * Creates new subscription instance.
         * @param node the address of PubSub node.
         * @param listener subscription listener which will be notified about
         *                 PubSub updates.
         */
        public Subscription(String node, SubscriptionListener listener)
        {
            Assert.notNull(node, "node");
            Assert.notNull(listener, "listener");

            this.node = node;
            this.listeners.add(listener);
            this.retryStrategy
                = new RetryStrategy(
                        XmppProtocolActivator.bundleContext);
        }

        /**
         * Registers <tt>SubscriptionListener</tt> which will be notified about
         * published PubSub items.
         * @param listener the <tt>SubscriptionListener</tt> to be registered
         */
        void addListener(SubscriptionListener listener)
        {
            listeners.add(listener);
        }

        /**
         * Removes <tt>SubscriptionListener</tt>.
         * @param listener the instance of <tt>SubscriptionListener</tt> to be
         *                 unregistered from PubSub notifications.
         * @return <tt>false</tt> if there are no more listeners registered to
         *         this <tt>Subscription</tt>
         */
        boolean removeListener(SubscriptionListener listener)
        {
            listeners.remove(listener);

            return listeners.size() > 0;
        }

        /**
         * Notifies all <tt>SubscriptionListener</tt>s about published
         * <tt>PayloadItem</tt>.
         * @param payloadItem new <tt>PayloadItem</tt> published to the PubSub
         *                    node observed by this subscription.
         */
        void notifyListeners(PayloadItem payloadItem)
        {
            for (SubscriptionListener l : listeners)
            {
                l.onSubscriptionUpdate(
                    node, payloadItem.getId(), payloadItem.getPayload());
            }
        }

        /**
         * Tries to subscribe to PubSub node notifications and returns
         * <tt>true</> on success.
         *
         * @throws Exception if application specific error occurs
         */
        synchronized private boolean doSubscribe()
            throws Exception
        {
            if (retryStrategy.isCancelled())
                return false;

            logger.info("Subscribing to " + node + " node at " + pubSubAddress);

            PubSubManager manager = getManager();

            try
            {
                Node pubSubNode = manager.getNode(node);
                if (!isSubscribed(getOurJid(), pubSubNode))
                {
                    // FIXME: Is it possible that we will be subscribed after
                    // our connection dies? If yes, we won't add listener here
                    // and won't receive notifications.
                    pubSubNode.addItemEventListener(OpSetSubscriptionImpl.this);
                    pubSubNode.subscribe(parentProvider.getOurJid());
                }
                return false;
            }
            catch (XMPPException e)
            {
                logger.error(
                    "Failed to subscribe to " + node +
                    " at "+ pubSubAddress + " error: " + e);

                return true;
            }
        }

        /**
         * Subscribes to PubSub node notifications.
         */
        synchronized void subscribe()
        {
            retryStrategy.runRetryingTask(
                new SimpleRetryTask(
                        0, getRetryInterval(), true, getRetryCallable()));
        }

        /**
         * Cancels PubSub subscription.
         */
        synchronized void unSubscribe()
        {
            retryStrategy.cancel();

            if (!parentProvider.isRegistered())
            {
                logger.warn(
                    "No connection - skipped PubSub unsubscribe for: " + node);
                return;
            }

            PubSubManager manager = getManager();

            try
            {
                Node pubSubNode = manager.getNode(node);
                String ourJid = getOurJid();

                if (isSubscribed(ourJid, pubSubNode))
                {
                    pubSubNode.unsubscribe(ourJid);
                }
            }
            catch (XMPPException e)
            {
                logger.error(
                    "An error occurred while trying to unsubscribe from" +
                    " PubSub: " + node + " at " + pubSubAddress +
                    ", reason: " + e);
            }
        }

        private Callable<Boolean> getRetryCallable()
        {
            return new Callable<Boolean>()
            {
                @Override
                public Boolean call()
                    throws Exception
                {
                    return doSubscribe();
                }
            };
        }
    }
}
