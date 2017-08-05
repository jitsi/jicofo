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
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 *
 */
public class XmppPeer
    implements StanzaListener
{
    private final Jid jid;

    private final MockXmppConnection connection;

    private final List<Stanza> packets = new ArrayList<>();

    public XmppPeer(String jid, MockXmppConnection connection)
    {
        this(jidCreate(jid), connection);
    }

    private static Jid jidCreate(String jid)
    {
        try
        {
            return JidCreate.from(jid);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }
    }

    public XmppPeer(Jid jid, MockXmppConnection connection)
    {
        this.jid = jid;
        this.connection = connection;
    }

    public void start()
    {
        this.connection.addPacketHandler(
            this,
            new StanzaFilter()
            {
                @Override
                public boolean accept(Stanza packet)
                {
                    return jid.equals(packet.getTo());
                }
            });
    }

    public Stanza waitForPacket(long timeout)
    {
        synchronized (packets)
        {
            if (getPacketCount() == 0)
            {
                try
                {
                    packets.wait(timeout);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }

            return packets.size() > 0 ? packets.get(0) : null;
        }
    }

    public void stop()
    {
        synchronized (packets)
        {
            connection.removePacketHandler(this);

            packets.notifyAll();
        }
    }

    public int getPacketCount()
    {
        synchronized (packets)
        {
            return packets.size();
        }
    }

    public Stanza getPacket(int idx)
    {
        synchronized (packets)
        {
            return packets.get(idx);
        }
    }

    @Override
    public void processStanza(Stanza packet)
    {
        synchronized (packets)
        {
            packets.add(packet);

            packets.notifyAll();
        }
    }
}
