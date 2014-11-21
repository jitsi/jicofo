package mock.xmpp;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 *
 */
public class MockXmppConnection
    implements XmppConnection
{
    final LinkedList<Packet> packetQueue = new LinkedList<Packet>();

    private final Map<PacketListener, PacketHandler> handlers
        = new HashMap<PacketListener, PacketHandler>();

    @Override
    public void sendPacket(Packet packet)
    {
        synchronized (packetQueue)
        {
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
        Packet myPacket = null;
        long start = System.currentTimeMillis();

        while (myPacket == null
                && (System.currentTimeMillis() - start) < timeout)
        {
            synchronized (packetQueue)
            {
                if (packetQueue.isEmpty())
                {
                    try
                    {
                        packetQueue.wait(timeout);
                    } catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }

                for (Packet p : packetQueue)
                {
                    if (myJid != null && myJid.equals(p.getTo()))
                    {
                        myPacket = p;
                    }
                    else if (!StringUtils.isNullOrEmpty(packetId)
                             && p instanceof IQ)
                    {
                        IQ iq = (IQ) p;
                        if (IQ.Type.RESULT.equals(iq.getType())
                            && packetId.equals(p.getPacketID()))
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

        private final MockXmppConnection connection;

        private Thread receiver;

        private boolean run = true;

        private final List<Packet> packets = new ArrayList<Packet>();

        public PacketReceiver(String jid, MockXmppConnection connection)
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
