/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smackx.disco.packet.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.stream.*;

/**
 *
 * @author Pawel Domas
 */
public class MockXmppProvider
    extends AbstractXmppProvider
{
    private final MockXmppConnection connection;

    /**
     * The features returned for any JID. We use default client features, but disable SCTP.
     */
    private final List<String> features
            = DiscoveryUtil.getDefaultParticipantFeatureSet().stream()
                .filter(f -> !f.equals(DiscoveryUtil.FEATURE_SCTP)).collect(Collectors.toList());

    private final AbstractOperationSetJingle jingleOpSet;

    private final MockMultiUserChatOpSet mucApi;

    @NotNull public XmppConnectionConfig config;

    public MockXmppProvider(@NotNull XmppConnectionConfig config)
    {
        this.config = config;
        connection = new MockXmppConnection(getOurJID());
        mucApi = new MockMultiUserChatOpSet(this);
        this.jingleOpSet = new MockOperationSetJingle(this);
    }

    @Override
    public String toString()
    {
        return "MockXmppProvider " + config;
    }

    @Override
    public void start()
    {
        if (jingleOpSet != null)
        {
            connection.registerIQRequestHandler(jingleOpSet);
        }

        setRegistered(true);
    }

    @Override
    public void shutdown()
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


    @Override
    public MockXmppConnection getXmppConnection()
    {
        return connection;
    }

    @Override
    public OperationSetJingle getJingleApi()
    {
        return jingleOpSet;
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

    @NotNull
    @Override
    public ChatRoom createRoom(@NotNull EntityBareJid name) throws RoomExistsException
    {
        return mucApi.createChatRoom(name);
    }

    @NotNull
    @Override
    public ChatRoom findOrCreateRoom(@NotNull EntityBareJid name) throws RoomExistsException
    {
        return mucApi.findRoom(name);
    }

    @NotNull
    @Override
    public List<String> discoverFeatures(@NotNull EntityFullJid jid)
    {
        return features;
    }

    @Nullable
    @Override
    public DiscoverInfo discoverInfo(@NotNull Jid jid)
    {
        return null;
    }

    @NotNull
    @Override
    public JSONObject getStats()
    {
        return new JSONObject();
    }
}
