package mock.xmpp;

import mock.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;

/**
 *
 */
public class MockSmackXmppOpSet
    implements OperationSetDirectSmackXmpp
{
    private final MockProtocolProvider protocolProvider;

    public MockSmackXmppOpSet(MockProtocolProvider protocolProvider)
    {
        this.protocolProvider = protocolProvider;
    }

    @Override
    public XmppConnection getXmppConnection()
    {
        return protocolProvider.getMockXmppConnection();
    }

    @Override
    public void addPacketHandler(PacketListener listener, PacketFilter filter)
    {
        protocolProvider.getMockXmppConnection()
            .addPacketHandler(listener, filter);
    }

    @Override
    public void removePacketHandler(PacketListener listener)
    {
        protocolProvider.getMockXmppConnection()
            .removePacketHandler(listener);
    }
}
