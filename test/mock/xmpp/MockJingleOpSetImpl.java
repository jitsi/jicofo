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
package mock.xmpp;

import mock.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.packet.*;

/**
 *
 */
public class MockJingleOpSetImpl
    extends AbstractOperationSetJingle
    implements XmppPacketReceiver.PacketListener
{
    private final static Logger logger
        = Logger.getLogger(MockJingleOpSetImpl.class);

    private final MockProtocolProvider protocolProvider;

    private XmppPacketReceiver receiver;

    public MockJingleOpSetImpl(MockProtocolProvider protocolProvider)
    {
        this.protocolProvider = protocolProvider;
    }

    @Override
    protected String getOurJID()
    {
        return protocolProvider.getOurJID();
    }

    @Override
    protected XmppConnection getConnection()
    {
        return protocolProvider.getMockXmppConnection();
    }

    public void start()
    {
        this.receiver
            = new XmppPacketReceiver(
                    getOurJID(),
                    (MockXmppConnection) getConnection(),
                    this);

        receiver.start();
    }

    public void stop()
    {
        receiver.stop();
    }

    @Override
    public void onPacket(Packet p)
    {
        if (p instanceof JingleIQ)
        {
            processJingleIQ((JingleIQ) p);
        }
        else
        {
            logger.error("Jingle Op set discarded: " + p.toXML());
        }
    }
}
