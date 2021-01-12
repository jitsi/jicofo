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
package mock;

import mock.muc.*;
import mock.xmpp.*;
import mock.xmpp.colibri.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

/**
 *
 * @author Pawel Domas
 */
public class MockProtocolProvider
    extends AbstractProtocolProviderService
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(MockProtocolProvider.class);


    private RegistrationState registrationState = RegistrationState.UNREGISTERED;

    private MockXmppConnection connection;

    private AbstractOperationSetJingle jingleOpSet;

    public XmppConnectionConfig config;

    public MockProtocolProvider(XmppConnectionConfig config)
    {
        this.config = config;
        includeMultiUserChatOpSet();
        includeJitsiMeetTools();
        includeColibriOpSet();
        includeJingleOpSet();
        includeSimpleCapsOpSet();
        includeDirectXmppOpSet();
    }

    @Override
    public String toString()
    {
        return "MockProtocolProvider " + config;
    }

    @Override
    public void register(SecurityAuthority authority)
        throws OperationFailedException
    {
        if (jingleOpSet != null)
        {
            connection.registerIQRequestHandler(jingleOpSet);
        }

        setRegistrationState(
            RegistrationState.REGISTERED,
            RegistrationStateChangeEvent.REASON_NOT_SPECIFIED,
            null);
    }

    private void setRegistrationState(RegistrationState newState, int reasonCode, String reason)
    {
        RegistrationState oldState = getRegistrationState();

        this.registrationState = newState;

        fireRegistrationStateChanged(
            oldState, newState, reasonCode, reason);
    }

    @Override
    public void unregister()
        throws OperationFailedException
    {
        if (jingleOpSet != null)
        {
            connection.unregisterIQRequestHandler(jingleOpSet);
        }

        setRegistrationState(
            RegistrationState.UNREGISTERED,
            RegistrationStateChangeEvent.REASON_NOT_SPECIFIED,
            null);
    }

    @Override
    public RegistrationState getRegistrationState()
    {
        return registrationState;
    }

    @Override
    public String getProtocolName()
    {
        return "Jabber";
    }

    @Override
    public ProtocolIcon getProtocolIcon()
    {
        return null;
    }

    @Override
    public void shutdown()
    {
        try
        {
            unregister();
        }
        catch (OperationFailedException e)
        {
            logger.error(e, e);
        }
    }

    @Override
    public AccountID getAccountID()
    {
        return null;
    }

    @Override
    public boolean isSignalingTransportSecure()
    {
        return false;
    }

    @Override
    public TransportProtocol getTransportProtocol()
    {
        return null;
    }

    public void includeMultiUserChatOpSet()
    {
        addSupportedOperationSet(
            OperationSetMultiUserChat.class,
            new MockMultiUserChatOpSet(this));
    }

    public void includeColibriOpSet()
    {
        addSupportedOperationSet(
            OperationSetColibriConference.class,
            new MockColibriOpSet(this));
    }

    public void includeJingleOpSet()
    {
        this.jingleOpSet = new MockOperationSetJingle(this);

        addSupportedOperationSet(
            OperationSetJingle.class,
            jingleOpSet);
    }

    public void includeSimpleCapsOpSet()
    {
        try
        {
            addSupportedOperationSet(
                OperationSetSimpleCaps.class,
                new MockSetSimpleCapsOpSet(JidCreate.from(config.getDomain())));
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void includeDirectXmppOpSet()
    {
        addSupportedOperationSet(
            OperationSetDirectSmackXmpp.class,
            new MockSmackXmppOpSet(this));
    }

    public void includeJitsiMeetTools()
    {
        addSupportedOperationSet(
            OperationSetJitsiMeetTools.class,
            new MockJitsiMeetTools(this));
    }

    public XmppConnection getXmppConnection()
    {
        if (this.connection == null)
        {
            this.connection = new MockXmppConnection(getOurJID());
        }
        return connection;
    }

    public EntityFullJid getOurJID()
    {
        try
        {
            return JidCreate.entityFullFrom(
                    "mock-" + config.getUsername() + "@" + config.getDomain() + "/" + config.getUsername());
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }
    }

    public MockMultiUserChatOpSet getMockChatOpSet()
    {
        return (MockMultiUserChatOpSet)
            getOperationSet(OperationSetMultiUserChat.class);
    }

    public MockSetSimpleCapsOpSet getMockCapsOpSet()
    {
        return (MockSetSimpleCapsOpSet)
            getOperationSet(OperationSetSimpleCaps.class);
    }

    public MockColibriOpSet getMockColibriOpSet()
    {
        return (MockColibriOpSet)
            getOperationSet(OperationSetColibriConference.class);
    }
}
