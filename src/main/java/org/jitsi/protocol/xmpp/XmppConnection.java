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

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

/**
 * The interface for Smack XMPP connection.
 *
 * @author Pawel Domas
 */
public interface XmppConnection
{
    /**
     * Sends given XMPP packet through this connection.
     * XXX The method will silently fail to send the packet if the XMPP
     * connection is broken(not connected). Use this method only if such
     * behaviour is desired, otherwise {@link #sendPacketAndGetReply(Packet)}
     * should be used instead.
     *
     * @param packet the packet to be sent.
     */
    void sendPacket(Packet packet);

    /**
     * Sends the packet and wait for reply in blocking mode.
     *
     * @param packet the packet to be sent.
     *
     * @return the response packet received within the time limit
     *         or <tt>null</tt> if no response was collected.
     *
     * @throws OperationFailedException with
     * {@link OperationFailedException#PROVIDER_NOT_REGISTERED} error code if
     * the packet could not be sent, because the XMPP connection is broken.
     */
    Packet sendPacketAndGetReply(Packet packet)
        throws OperationFailedException;

    /**
     * Adds packet listener and a filter that limits the packets reaching
     * listener object.
     *
     * @param listener the <tt>PacketListener</tt> that will be notified about
     * XMPP packets received.
     * @param filter the <tt>PacketFilter</tt> that filters out packets reaching
     * <tt>listener</tt> object.
     */
    void addPacketHandler(PacketListener listener, PacketFilter filter);

    /**
     * Removes packet listener and the filter applied to it, so that it will no
     * longer be notified about incoming XMPP packets.
     *
     * @param listener the <tt>PacketListener</tt> instance to be removed from
     * listeners set.
     */
    void removePacketHandler(PacketListener listener);
}
