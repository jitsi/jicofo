package mock.xmpp;

import mock.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.packet.*;

/**
 *
 */
public class MockJingleOpSetImpl
    extends AbstractOperationSetJingle
    implements XmppPacketReceiver.PacketListener
{
    private final static Logger logger
        = Logger.getLogger(MockJingleOpSetImpl.class);

    private final MockProtocolProvider protocolProvider;

    private XmppPacketReceiver receiver;

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

    public void start()
    {
        this.receiver
            = new XmppPacketReceiver(
                    getOurJID(),
                    (MockXmppConnection) getConnection(),
                    this);

        receiver.start();
    }

    public void stop()
    {
        receiver.stop();
    }

    @Override
    public void onPacket(Packet p)
    {
        if (p instanceof JingleIQ)
        {
            processJingleIQ((JingleIQ) p);
        }
        else
        {
            logger.error("Jingle Op set discarded: " + p.toXML());
        }
    }
}
