package mock.xmpp;

import org.jivesoftware.smack.packet.*;

import java.util.*;

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
