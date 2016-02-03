package mock.xmpp;

import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;

/**
 *
 */
public interface MockXmppConnection
    extends XmppConnection
{
    void addPacketHandler(PacketListener listener, PacketFilter filter);

    void removePacketHandler(PacketListener listener);
}
