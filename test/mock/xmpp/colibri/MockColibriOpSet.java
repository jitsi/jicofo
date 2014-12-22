/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock.xmpp.colibri;

import mock.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.impl.protocol.xmpp.colibri.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.*;
import org.jitsi.protocol.xmpp.colibri.*;

import java.util.*;

/**
 *
 * @author Pawel Domas
 */
public class MockColibriOpSet
    implements OperationSetColibriConference
{
    private final MockProtocolProvider protocolProvider;

    private OperationSetColibriConferenceImpl colibriImpl;

    public MockColibriOpSet(MockProtocolProvider protocolProvider)
    {
        this.protocolProvider = protocolProvider;

        colibriImpl = new OperationSetColibriConferenceImpl();

        colibriImpl.initialize(protocolProvider.getMockXmppConnection());
    }

    @Override
    public ColibriConference createNewConference()
    {
        return new ColibriConferenceImpl(
            protocolProvider.getMockXmppConnection());
    }
}
