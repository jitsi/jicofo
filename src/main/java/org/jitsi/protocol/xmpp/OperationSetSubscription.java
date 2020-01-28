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
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.jicofo.bridge.*;
import org.jivesoftware.smackx.pubsub.*;

import java.util.*;

/**
 * Operation set exposes underlying protocol's subscription for notifications.
 * In case of XMPP this is pub-sub nodes which is currently used by
 * {@link BridgeSelector}.
 *
 * @author Pawel Domas
 */
public interface OperationSetSubscription
    extends OperationSet
{
    /**
     * Subscribes to given <tt>node</tt> for notifications.
     *
     * @param node the of the node to which given listener will be subscribed to
     * @param listener the {@link SubscriptionListener} instance that will be
     *                 notified of updates from the node.
     */
    void subscribe(String node, SubscriptionListener listener);

    /**
     * Cancels subscriptions for given <tt>node</tt>.
     * @param node the node for which subscription will be canceled.
     * @param listener subscription listener to be registered.
     */
    void unSubscribe(String node, SubscriptionListener listener);

    /**
     * Obtains the list of published items persisted on the server.
     * @param node the name of PubSub node for which items list will be fetched.
     * @return the list of items or <tt>null</tt> if an error occurred and we
     *         were unable to obtain the list.
     */
    List<PayloadItem> getItems(String node);
}
