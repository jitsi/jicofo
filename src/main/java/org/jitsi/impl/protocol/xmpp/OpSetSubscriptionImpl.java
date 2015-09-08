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

import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;

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
     * Parent XMPP provider used to hndle the protocol.
     */
    private final XmppProtocolProvider parentProvider;

    /**
     * Executor service.
     */
    private ScheduledExecutorService executor;

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
     * Gets <tt>ScheduledExecutorService</tt> service.
     */
    private ScheduledExecutorService getExecutor()
    {
        if (executor == null)
        {
            executor = ServiceUtils.getService(
                    XmppProtocolActivator.bundleContext,
                    ScheduledExecutorService.class);
        }
        return executor;
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
        if (subscriptionsMap.containsKey(node))
        {
            logger.warn("Already subscribed to PubSub node: " + node);
            return;
        }

        Subscription subscription = new Subscription(node, listener);

        subscriptionsMap.put(node, subscription);

        subscription.subscribe();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void unSubscribe(String node)
    {
        Subscription subscription = subscriptionsMap.remove(node);
        if (subscription != null)
        {
            subscription.unSubscribe();
        }
        else
        {
            logger.warn("No PubSub listener for " + node);
        }
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
                subscription.listener.
                    onSubscriptionUpdate(nodeId, payloadItem.getPayload());
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
         * Subscription listener.
         */
        private final SubscriptionListener listener;

        /**
         * Set to true on un-subscribe to cancel re-tries.
         */
        private boolean cancelled = false;

        /**
         * Creates new subscription instance.
         * @param node the address of PubSub node.
         * @param listener subscription listener which will be notified about
         *                 PubSub updates.
         */
        public Subscription(String node, SubscriptionListener listener)
        {
            if (node == null)
                throw new NullPointerException("node");
            if (listener == null)
                throw new NullPointerException("listener");

            this.node = node;
            this.listener = listener;
        }

        /**
         * Tries to subscribe to PubSub node notifications and returns
         * <tt>true</> on success.
         */
        private boolean trySubscribe()
        {
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
                return true;
            }
            catch (XMPPException e)
            {
                logger.error(e.getMessage(), e);
                return false;
            }
        }

        /**
         * Schedules subscribe task for later execution
         */
        private void retry()
        {
            getExecutor().schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    subscribe();
                }
            }, getRetryInterval(), TimeUnit.MILLISECONDS);
        }

        /**
         * Subscribes to PubSub node notifications.
         */
        synchronized void subscribe()
        {
            if (cancelled)
                return;

            if (!trySubscribe())
            {
                retry();
            }
        }

        /**
         * Cancels PubSub subscription.
         */
        synchronized void unSubscribe()
        {
            cancelled = true;

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
                logger.error(e.getMessage(), e);
            }
        }
    }
}
