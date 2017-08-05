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

import org.jitsi.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 *
 */
public class MockXmppConnectionImpl
    implements MockXmppConnection
{
    private final static Logger logger
        = Logger.getLogger(MockXmppConnectionImpl.class);

    final LinkedList<Stanza> packetQueue = new LinkedList<>();

    private final Map<StanzaListener, PacketHandler> handlers = new HashMap<>();

    @Override
    public void sendPacket(final Stanza packet)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                doSendPacket(packet);
            }
        },"Packet " + packet.getStanzaId() + " sender").start();
    }

    private void doSendPacket(Stanza packet)
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

                try
                {
                    handler.listener.processStanza(packet);
                }
                catch (SmackException.NotConnectedException
                        | InterruptedException e)
                {
                    // ignore, but log - same as the real implementation
                    logger.error("Could not notify listener", e);
                }
            }
        }
    }

    @Override
    public IQ sendPacketAndGetReply(IQ packet)
    {
        Jid myJid = packet.getFrom();
        String packetId = packet.getStanzaId();

        sendPacket(packet);

        return (IQ)readNextPacket(myJid, packetId, 10000);
    }

    public Stanza readNextPacket(Jid myJid, long timeout)
    {
        return readNextPacket(myJid, null, timeout);
    }

    public Stanza readNextPacket(Jid myJid, String packetId, long timeout)
    {
        if (myJid == null)
        {
            throw new IllegalArgumentException("myJid");
        }

        logger.debug(
            "Read packet request for JID: " + myJid
                + " packet: " + packetId + " tout: "
                + timeout + " t: " + Thread.currentThread());

        Stanza myPacket = null;
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

                for (Stanza p : packetQueue)
                {
                    if (myJid.equals(p.getTo()))
                    {
                        if (!StringUtils.isNullOrEmpty(packetId)
                            && p instanceof IQ)
                        {
                            IQ iq = (IQ) p;
                            IQ.Type iqType = iq.getType();
                            if ((IQ.Type.result.equals(iqType) ||
                                 IQ.Type.error.equals(iqType))

                                && packetId.equals(p.getStanzaId()))
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
            logger.debug("Routing packet: " + myPacket.getStanzaId()
                + " to " + myJid + " t: " + Thread.currentThread());
        }
        else
        {
            logger.debug("Reporting timeout for: " + packetId
                + " to: " + myJid + " t: " + Thread.currentThread());
        }

        return myPacket;
    }

    public void addPacketHandler(StanzaListener listener, StanzaFilter filter)
    {
        synchronized (handlers)
        {
            handlers.put(listener, new PacketHandler(listener, filter));
        }
    }

    public void removePacketHandler(StanzaListener listener)
    {
        synchronized (handlers)
        {
            handlers.remove(listener);
        }
    }

    class PacketHandler
    {
        StanzaFilter filter;
        StanzaListener listener;

        public PacketHandler(StanzaListener listener, StanzaFilter filter)
        {
            this.listener = listener;
            this.filter = filter;
        }
    }

    class PacketReceiver
    {
        private final Jid jid;

        private final MockXmppConnectionImpl connection;

        private Thread receiver;

        private boolean run = true;

        private final List<Stanza> packets = new ArrayList<>();

        public PacketReceiver(Jid jid, MockXmppConnectionImpl connection)
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
                        Stanza p = connection.readNextPacket(jid, 500);
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

        public Stanza getPacket(int idx)
        {
            synchronized (packets)
            {
                return packets.get(idx);
            }
        }
    }
}
