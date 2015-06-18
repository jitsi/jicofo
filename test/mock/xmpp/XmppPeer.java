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

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 *
 */
public class XmppPeer
{
    private final String jid;

    private final MockXmppConnection connection;

    private Thread receiver;

    private boolean run = true;

    private final List<Packet> packets = new ArrayList<Packet>();

    public XmppPeer(String jid, MockXmppConnection connection)
    {
        this.jid = jid;
        this.connection = connection;
    }

    public void start()
    {
        receiver = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (run)
                {
                    Packet p = connection.readNextPacket(jid, 500);
                    if (p != null)
                    {
                        synchronized (packets)
                        {
                            packets.add(p);

                            packets.notifyAll();
                        }
                    }
                }
            }
        });
        receiver.start();
    }

    public Packet waitForPacket(long timeout)
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
        run = false;

        receiver.interrupt();

        try
        {
            receiver.join();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    public int getPacketCount()
    {
        synchronized (packets)
        {
            return packets.size();
        }
    }

    public Packet getPacket(int idx)
    {
        synchronized (packets)
        {
            return packets.get(idx);
        }
    }
}
