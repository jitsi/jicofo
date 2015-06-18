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
package mock.xmpp.pubsub;

import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Mock implementation of {@link OperationSetSubscription}.
 *
 * @author Pawel Domas
 */
public class MockSubscriptionOpSetImpl
    implements OperationSetSubscription
{
    // FIXME: supports single subscriber per node
    private Map<String, SubscriptionListener> listenerMap
        = new HashMap<String, SubscriptionListener>();

    @Override
    public void subscribe(String node, SubscriptionListener listener)
    {
        listenerMap.put(node, listener);
    }

    public void fireSubscriptionNotification(String node,
                                             PacketExtension payload)
    {
        SubscriptionListener l = listenerMap.get(node);
        if (l != null)
        {
            l.onSubscriptionUpdate(node, payload);
        }
    }

    @Override
    public void unSubscribe(String node)
    {
        listenerMap.remove(node);
    }
}
