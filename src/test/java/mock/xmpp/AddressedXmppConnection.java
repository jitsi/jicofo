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


import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.Jid;

/**
 * Addressed version of {@link MockXmppConnectionImpl} that will fill the 'from'
 * field for us.
 */
public class AddressedXmppConnection
    implements MockXmppConnection
{
    private final Jid ourJid;

    private final MockXmppConnectionImpl connection;

    public AddressedXmppConnection(Jid ourJid,
                                   MockXmppConnectionImpl connection)
    {
        this.ourJid = ourJid;
        this.connection = connection;
    }

    private void fromCheck(Stanza ourRequest)
    {
        if (ourRequest.getFrom() == null)
        {
            ourRequest.setFrom(ourJid);
        }
    }

    @Override
    public void sendPacket(Stanza packet)
    {
        fromCheck(packet);

        connection.sendPacket(packet);
    }

    @Override
    public IQ sendPacketAndGetReply(IQ packet)
    {
        fromCheck(packet);

        return connection.sendPacketAndGetReply(packet);
    }

    @Override
    public void addPacketHandler(StanzaListener listener, StanzaFilter filter)
    {
        connection.addPacketHandler(listener, filter);
    }

    @Override
    public void removePacketHandler(StanzaListener listener)
    {
        connection.removePacketHandler(listener);
    }
}
