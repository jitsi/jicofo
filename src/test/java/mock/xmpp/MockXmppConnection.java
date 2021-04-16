/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017-Present 8x8, Inc.
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
package mock.xmpp;

import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.util.*;

public class MockXmppConnection
    extends AbstractXMPPConnection
{
    private final static Logger logger = new LoggerImpl(MockXmppConnection.class.getName());

    private static final Map<Jid, MockXmppConnection> sharedStanzaQueue = Collections.synchronizedMap(new HashMap<>());

    private static DomainBareJid domain;
    static
    {
        try
        {
            domain = JidCreate.domainBareFrom("example.com");
        }
        catch (XmppStringprepException e) {
            domain = null;
        }
    }

    private static class MockXmppConnectionConfiguration
            extends ConnectionConfiguration
    {
        MockXmppConnectionConfiguration(Builder builder)
        {
            super(builder);
        }

        public static final class Builder
                extends ConnectionConfiguration.Builder<Builder,MockXmppConnectionConfiguration>
        {
            @Override
            public MockXmppConnectionConfiguration build()
            {
                return new MockXmppConnectionConfiguration(this);
            }

            protected Builder getThis()
            {
                return this;
            }
        }
    }

    @SuppressWarnings("deprecation")
    public MockXmppConnection(final Jid ourJid)
    {
        super(new MockXmppConnectionConfiguration.Builder()
                .setXmppDomain(domain)
                .setUsernameAndPassword("mock", "mockpass")
                .build());

        user = new AnyJidAsEntityFullJid(ourJid);
        setFromMode(FromMode.UNCHANGED);

        try
        {
            connect();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sendNonza(Nonza element)
    {
    }

    @Override
    public boolean isUsingCompression()
    {
        return false;
    }

    @Override
    protected void loginInternal(String username,
                                 String password,
                                 Resourcepart resource)
    {
        // nothing to do
    }

    @Override
    protected void connectInternal()
    {
        sharedStanzaQueue.put(user, this);
        tlsHandled.reportSuccess();
        saslFeatureReceived.reportSuccess();
    }

    @Override
    protected void sendStanzaInternal(final Stanza packet)
    {
        packet.setFrom(user);
        final MockXmppConnection target = sharedStanzaQueue.get(packet.getTo());
        if (target == null)
        {
            logger.error("Connection for " + packet.getTo() + " not found!");
            return;
        }

        Thread t = new Thread(() -> target.invokeStanzaCollectorsAndNotifyRecvListeners(packet));
        t.start();
    }

    @Override
    protected void shutdown()
    {
        sharedStanzaQueue.remove(user);
        connected = false;
    }

    @Override
    public boolean isSecureConnection()
    {
        return false;
    }
}
