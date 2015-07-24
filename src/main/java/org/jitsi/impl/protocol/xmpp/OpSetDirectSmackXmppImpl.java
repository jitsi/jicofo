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
import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;

/**
 * Straightforward implementation of {@link OperationSetDirectSmackXmpp}
 * for {@link org.jitsi.impl.protocol.xmpp.XmppProtocolProvider}.
 *
 * @author Pawel Domas
 */
public class OpSetDirectSmackXmppImpl
    implements OperationSetDirectSmackXmpp
{
    /**
     *  The logger used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(OpSetDirectSmackXmppImpl.class);

    /**
     * Parent protocol provider service.
     */
    private final XmppProtocolProvider xmppProvider;

    /**
     * Creates new instance of <tt>OpSetDirectSmackXmppImpl</tt>.
     *
     * @param xmppProvider parent {@link XmppProtocolProvider}.
     */
    public OpSetDirectSmackXmppImpl(XmppProtocolProvider xmppProvider)
    {
        this.xmppProvider = xmppProvider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XmppConnection getXmppConnection()
    {
        return xmppProvider.getConnectionAdapter();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addPacketHandler(PacketListener listener, PacketFilter filter)
    {
        XMPPConnection connection = xmppProvider.getConnection();
        if (connection != null)
        {
            connection.addPacketListener(listener, filter);
        }
        else
        {
            logger.error("Failed to add packet handler: "
                             + listener + " - no valid connection object");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removePacketHandler(PacketListener listener)
    {
        XMPPConnection connection = xmppProvider.getConnection();
        if (connection != null)
        {
            xmppProvider.getConnection().removePacketListener(listener);
        }
        else
        {
            logger.error("Failed to remove packet handler: "
                             + listener + " - no valid connection object");
        }
    }
}
