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

import org.jivesoftware.smack.packet.*;

/**
 * Class used to listen for subscription updates through
 * {@link org.jitsi.protocol.xmpp.OperationSetSubscription}.
 *
 * @author Pawel Domas
 */
public interface SubscriptionListener
{
    /**
     * Callback called when update is received on some subscription node.
     *
     * @param node the source node of the event.
     * @param itemId the ID of PubSub item for which this even was generated.
     * @param payload the payload of notification.
     */
    void onSubscriptionUpdate(String          node,
                              String          itemId,
                              PacketExtension payload);
}
