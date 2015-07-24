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
 * The interface for Smack XMPP connection.
 *
 * @author Pawel Domas
 */
public interface XmppConnection
{
    /**
     * Sends given XMPP packet through this connection.
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
     */
    Packet sendPacketAndGetReply(Packet packet);
}
