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

/**
 *
 */
public class XmppPacketReceiver
{
    private final String jid;

    private final MockXmppConnection connection;

    private final PacketListener listener;

    private Thread receiver;

    private boolean run = true;

    public XmppPacketReceiver(String jid, MockXmppConnection connection,
                          PacketListener listener)
    {
        this.jid = jid;
        this.connection = connection;
        this.listener = listener;
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
                        listener.onPacket(p);
                    }
                }
            }
        });
        receiver.start();
    }

    public void stop()
    {
        run = false;

        try
        {
            receiver.join();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    public interface PacketListener
    {
        void onPacket(Packet p);
    }
}
