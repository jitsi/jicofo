/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
