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
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

/**
 * The interface for Smack XMPP connection.
 *
 * @author Pawel Domas
 */
public interface XmppConnection
{
    EntityFullJid getUser();

    /**
     * Sends given XMPP packet through this connection.
     * XXX The method will silently fail to send the packet if the XMPP
     * connection is broken (not connected). Use this method only if such
     * behaviour is desired, otherwise {@link #sendPacketAndGetReply(IQ)}
     * should be used instead.
     *
     * @param packet the packet to be sent.
     */
    void sendStanza(Stanza packet);

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
    IQ sendPacketAndGetReply(IQ packet)
        throws OperationFailedException;

    IQRequestHandler registerIQRequestHandler(IQRequestHandler handler);

    IQRequestHandler unregisterIQRequestHandler(IQRequestHandler handler);

    /**
     * See {@link XMPPConnection#sendIqWithResponseCallback(
     *              IQ, StanzaListener, ExceptionCallback, long)}
     */
    void sendIqWithResponseCallback(
            IQ iq,
            StanzaListener stanzaListener,
            ExceptionCallback exceptionCallback,
            long timeout)
        throws SmackException.NotConnectedException, InterruptedException;
}
