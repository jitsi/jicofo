package mock.jvb;

import mock.xmpp.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;
import org.jitsi.videobridge.*;
import org.jivesoftware.smack.packet.*;
import org.osgi.framework.*;

/**
 *
 * @author Pawel Domas
 */
public class MockVideobridge
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(MockVideobridge.class);

    private final MockXmppConnection connection;

    private final String bridgeJid;

    private Thread thread;

    private boolean run = true;

    private Videobridge bridge;

    public MockVideobridge(BundleContext bc,
                           MockXmppConnection connection,
                           String bridgeJid)
    {
        this.connection = connection;

        VideobridgeBundleActivator activator
            = new VideobridgeBundleActivator();
        try
        {
            activator.start(bc);
        }
        catch (Exception e)
        {
            logger.error(e, e);
        }

        bridge = ServiceUtils.getService(bc, Videobridge.class);

        this.bridgeJid = bridgeJid;
    }

    public void start()
    {
        this.thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    jvbLoop();
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
        });

        thread.start();
    }

    private void jvbLoop()
        throws Exception
    {
        while (run)
        {
            Packet p = connection.readNextPacket(bridgeJid, 500);
            if (p instanceof ColibriConferenceIQ)
            {
                logger.info("JVB rcv: " + p.toXML());

                IQ response
                    = bridge.handleColibriConferenceIQ(
                            (ColibriConferenceIQ) p,
                            Videobridge.OPTION_ALLOW_ANY_FOCUS);

                if (response != null)
                {
                    response.setTo(p.getFrom());
                    response.setFrom(bridgeJid);
                    if (IQ.Type.RESULT.equals(response.getType()))
                    {
                        response.setPacketID(p.getPacketID());
                    }
                    connection.sendPacket(response);

                    logger.info("JVB sent: " + response.toXML());
                }
                else
                {
                    logger.warn("The bridge sent no response for "
                                    + p.toString());
                }
            }
            else if (p != null)
            {
                logger.error("Discarded " + p.toXML());
            }
        }
    }

    public int getChannelsCount()
    {
        int count = 0;
        for (Conference conference : bridge.getConferences())
        {
            for (Content content: conference.getContents())
            {
                count += content.getChannelCount();
            }
        }
        return count;
    }

    public String getBridgeJid()
    {
        return bridgeJid;
    }
}
