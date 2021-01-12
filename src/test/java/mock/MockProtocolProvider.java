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
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.concurrent.*;

/**
 *
 * @author Pawel Domas
 */
public class MockProtocolProvider
    extends AbstractXmppProvider
{
    private MockXmppConnection connection;

    private AbstractOperationSetJingle jingleOpSet;

    public XmppConnectionConfig config;

    private MockColibriOpSet colibriApi;

    public MockProtocolProvider(XmppConnectionConfig config)
    {
        this.config = config;
        colibriApi = new MockColibriOpSet(this);
        includeMultiUserChatOpSet();
        includeJitsiMeetTools();
        includeJingleOpSet();
        includeSimpleCapsOpSet();
    }

    @Override
    public String toString()
    {
        return "MockProtocolProvider " + config;
    }

    @Override
    public void register(ScheduledExecutorService executorService)
    {
        if (jingleOpSet != null)
        {
            connection.registerIQRequestHandler(jingleOpSet);
        }

        setRegistered(true);
    }

    @Override
    public void unregister()
    {
        if (jingleOpSet != null)
        {
            connection.unregisterIQRequestHandler(jingleOpSet);
        }

        setRegistered(false);
    }

    @Override
    public XmppConnectionConfig getConfig()
    {
        return config;
    }

    public void includeMultiUserChatOpSet()
    {
        addOperationSet(OperationSetMultiUserChat.class, new MockMultiUserChatOpSet(this));
    }

    public void includeJingleOpSet()
    {
        this.jingleOpSet = new MockOperationSetJingle(this);

        addOperationSet(OperationSetJingle.class, jingleOpSet);
    }

    public void includeSimpleCapsOpSet()
    {
        addOperationSet(OperationSetSimpleCaps.class, new MockSetSimpleCapsOpSet(config.getDomain()));
    }

    public void includeJitsiMeetTools()
    {
        addOperationSet(OperationSetJitsiMeetTools.class, new MockJitsiMeetTools(this));
    }

    @Override
    public XmppConnection getXmppConnection()
    {
        if (this.connection == null)
        {
            this.connection = new MockXmppConnection(getOurJID());
        }
        return connection;
    }

    @Override
    public MockColibriOpSet getColibriApi()
    {
        return colibriApi;
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
        return (MockMultiUserChatOpSet) getOperationSet(OperationSetMultiUserChat.class);
    }

    public MockSetSimpleCapsOpSet getMockCapsOpSet()
    {
        return (MockSetSimpleCapsOpSet) getOperationSet(OperationSetSimpleCaps.class);
    }
}
