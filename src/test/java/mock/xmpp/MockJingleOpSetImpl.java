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
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;


/**
 *
 */
public class MockJingleOpSetImpl
    extends AbstractOperationSetJingle
    implements PacketListener, PacketFilter
{
    private final MockProtocolProvider protocolProvider;

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

    public synchronized void start()
    {
        protocolProvider.getMockXmppConnection().addPacketHandler(this, this);
    }

    public synchronized void stop()
    {
        protocolProvider.getMockXmppConnection().removePacketHandler(this);
    }

    @Override
    public void processPacket(Packet packet)
    {
        processJingleIQ((JingleIQ) packet);
    }

    @Override
    public boolean accept(Packet packet)
    {
        return packet instanceof JingleIQ && packet.getTo().equals(getOurJID());
    }
}
