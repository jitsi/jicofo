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

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.util.*;

import static org.jivesoftware.smack.SmackException.*;

public class MockExtendedXmppConnection
    extends AbstractXMPPConnection
    implements ExtendedXmppConnection
{
    private final static Logger logger = new LoggerImpl(MockExtendedXmppConnection.class.getName());

    private static final Map<Jid, MockExtendedXmppConnection> sharedStanzaQueue = Collections.synchronizedMap(new HashMap<>());

    private static class MockXmppConnectionConfiguration
            extends ConnectionConfiguration
    {
        MockXmppConnectionConfiguration(Builder builder)
        {
            super(builder);
        }

        public static final class Builder
                extends
                ConnectionConfiguration.Builder<Builder,
                        MockXmppConnectionConfiguration>
        {
            @Override
            public MockXmppConnectionConfiguration build()
            {
                return new MockXmppConnectionConfiguration(this);
            }

            @Override
            public Builder setXmppDomain(String xmppServiceDomain)
            {
                try
                {
                    return super.setXmppDomain(xmppServiceDomain);
                }
                catch (XmppStringprepException e)
                {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected Builder getThis() {
                return this;
            }
        }
    }

    @SuppressWarnings("deprecation")
    public MockExtendedXmppConnection(final Jid ourJid)
    {
        super(new MockXmppConnectionConfiguration.Builder()
                .setXmppDomain("example.com")
                .setUsernameAndPassword("mock", "mockpass")
                .build());
        if (ourJid instanceof EntityFullJid)
        {
            user = (EntityFullJid) ourJid;
        }
        else
        {
            user = new AnyJidAsEntityFullJid(ourJid);
        }

        try
        {
            setFromMode(FromMode.UNCHANGED);
            // FIXME smack4: wait for Smack#163
            setReplyToUnknownIq(false);
            connect();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AbstractXMPPConnection getSmackXMPPConnection()
    {
        return this;
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
        final MockExtendedXmppConnection target = sharedStanzaQueue.get(packet.getTo());
        if (target == null)
        {
            System.out.println("Connection for " + packet.getTo() + " not found!");
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

    @Override
    public void tryToSendStanza(Stanza packet)
    {
        try
        {
            super.sendStanza(packet);
        }
        catch (NotConnectedException e)
        {
            logger.error("No connection - unable to send packet: "
                    + packet.toXML(), e);
        }
        catch (InterruptedException e)
        {
            logger.error("Failed to send packet: "
                    + packet.toXML().toString(), e);
        }
    }

    @Override
    public IQ sendPacketAndGetReply(IQ packet)
            throws SmackException.NotConnectedException
    {
        Objects.requireNonNull(packet, "packet");

        packet.setFrom(user);

        try
        {
            StanzaCollector packetCollector = createStanzaCollectorAndSend(packet);
            try
            {
                //FIXME: retry allocation on timeout
                return packetCollector.nextResult();
            } finally
            {
                packetCollector.cancel();
            }
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException("Interrupted", e);
        }
    }

    @Override
    public IQRequestHandler registerIQRequestHandler(IQRequestHandler iqRequestHandler)
    {
        IQRequestHandler previous = super.registerIQRequestHandler(iqRequestHandler);
        if (previous != null && previous != iqRequestHandler)
        {
            throw new RuntimeException("A IQ handler for "
                    + iqRequestHandler.getElement()
                    + " was already registered! Old: "
                    + previous.toString()
                    + ", New: " + iqRequestHandler.toString());
        }

        return previous;
    }
}
