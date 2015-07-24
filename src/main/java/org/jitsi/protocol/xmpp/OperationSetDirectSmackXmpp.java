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

/**
 * The operation set that allows to deal with {@link XmppConnection} directly.
 *
 * @author Pawel Domas
 */
public interface OperationSetDirectSmackXmpp
    extends OperationSet
{
    /**
     * Returns <tt>XmppConnection</tt> object for the XMPP connection of the
     * <tt>ProtocolProviderService</tt>.
     */
    XmppConnection getXmppConnection();

    /**
     * Adds packet listener and a filter that limits the packets reaching
     * listener object.
     *
     * @param listener the <tt>PacketListener</tt> that will be notified about
     *                 XMPP packets received.
     * @param filter the <tt>PacketFilter</tt> that filters out packets reaching
     *               <tt>listener</tt> object.
     */
    void addPacketHandler(PacketListener listener, PacketFilter filter);

    /**
     * Removes packet listener and the filter applied to it, so that it will no
     * longer be notified about incoming XMPP packets.
     *
     * @param listener the <tt>PacketListener</tt> instance to be removed from
     *                 listeners set.
     */
    void removePacketHandler(PacketListener listener);
}
