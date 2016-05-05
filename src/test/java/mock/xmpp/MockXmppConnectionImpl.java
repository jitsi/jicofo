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

import org.jitsi.assertions.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 *
 */
public class MockXmppConnectionImpl
    implements MockXmppConnection
{
    private final static Logger logger
        = Logger.getLogger(MockXmppConnectionImpl.class);

    final LinkedList<Packet> packetQueue = new LinkedList<Packet>();

    private final Map<PacketListener, PacketHandler> handlers
        = new HashMap<PacketListener, PacketHandler>();

    @Override
    public void sendPacket(final Packet packet)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                doSendPacket(packet);
            }
        },"Packet " + packet.getPacketID() + " sender").start();
    }

    private void doSendPacket(Packet packet)
    {
        synchronized (packetQueue)
        {
            logger.debug(
                "Putting on queue: " + packet.toXML()
                    + " t: " + Thread.currentThread());

            packetQueue.add(packet);

            packetQueue.notifyAll();
        }

        ArrayList<PacketHandler> copy;
        synchronized (handlers)
        {
            copy = new ArrayList<PacketHandler>(handlers.values());
        }

        for (PacketHandler handler : copy)
        {
            if (handler.filter.accept(packet))
            {
                logger.debug(
                    "Notifying handler " + handler
                        + " about: " + packet.toXML());

                handler.listener.processPacket(packet);
            }
        }
    }

    @Override
    public Packet sendPacketAndGetReply(Packet packet)
    {
        String myJid = packet.getFrom();
        String packetId = packet.getPacketID();

        sendPacket(packet);

        return readNextPacket(myJid, packetId, 10000);
    }

    public Packet readNextPacket(String myJid, long timeout)
    {
        return readNextPacket(myJid, null, timeout);
    }

    public Packet readNextPacket(String myJid, String packetId, long timeout)
    {
        Assert.notNullNorEmpty(myJid, "JID: " + myJid);

        logger.debug(
            "Read packet request for JID: " + myJid
                + " packet: " + packetId + " tout: "
                + timeout + " t: " + Thread.currentThread());

        Packet myPacket = null;
        long start = System.currentTimeMillis();
        long end = start + timeout;

        while (myPacket == null
                && System.currentTimeMillis() < end)
        {
            synchronized (packetQueue)
            {
                if (packetQueue.isEmpty())
                {
                    try
                    {
                        long wait = end - System.currentTimeMillis();
                        if (wait <= 0)
                        {
                            break;
                        }
                        else
                        {
                            packetQueue.wait(wait);
                        }
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }

                for (Packet p : packetQueue)
                {
                    if (myJid.equals(p.getTo()))
                    {
                        if (!StringUtils.isNullOrEmpty(packetId)
                            && p instanceof IQ)
                        {
                            IQ iq = (IQ) p;
                            IQ.Type iqType = iq.getType();
                            if ((IQ.Type.RESULT.equals(iqType) ||
                                 IQ.Type.ERROR.equals(iqType))

                                && packetId.equals(p.getPacketID()))
                            {
                                myPacket = p;
                            }
                        }
                        else if (StringUtils.isNullOrEmpty(packetId))
                        {
                            myPacket = p;
                        }
                    }
                }
                if (myPacket != null)
                {
                    packetQueue.remove(myPacket);

                }
            }
        }

        if (myPacket != null)
        {
            logger.debug("Routing packet: " + myPacket.getPacketID()
                + " to " + myJid + " t: " + Thread.currentThread());
        }
        else
        {
            logger.debug("Reporting timeout for: " + packetId
                + " to: " + myJid + " t: " + Thread.currentThread());
        }

        return myPacket;
    }

    public void addPacketHandler(PacketListener listener, PacketFilter filter)
    {
        synchronized (handlers)
        {
            handlers.put(listener, new PacketHandler(listener, filter));
        }
    }

    public void removePacketHandler(PacketListener listener)
    {
        synchronized (handlers)
        {
            handlers.remove(listener);
        }
    }

    class PacketHandler
    {
        PacketFilter filter;
        PacketListener listener;

        public PacketHandler(PacketListener listener, PacketFilter filter)
        {
            this.listener = listener;
            this.filter = filter;
        }
    }

    class PacketReceiver
    {
        private final String jid;

        private final MockXmppConnectionImpl connection;

        private Thread receiver;

        private boolean run = true;

        private final List<Packet> packets = new ArrayList<Packet>();

        public PacketReceiver(String jid, MockXmppConnectionImpl connection)
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
}
