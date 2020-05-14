/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017 Atlassian Pty Ltd
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

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.io.*;
import java.util.*;

import static org.jivesoftware.smack.SmackException.*;

public class MockXmppConnection
    extends AbstractXMPPConnection
    implements XmppConnection
{
    private final static Logger logger
            = Logger.getLogger(MockXmppConnection.class);

    private static final Map<Jid, MockXmppConnection> sharedStanzaQueue
            = Collections.synchronizedMap(
                    new HashMap<Jid, MockXmppConnection>());

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
    public MockXmppConnection(final Jid ourJid)
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
    public void sendNonza(Nonza element)
            throws NotConnectedException, InterruptedException
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
            throws XMPPException, SmackException,
            IOException, InterruptedException
    {
        // nothing to do
    }

    @Override
    protected void connectInternal()
            throws SmackException, IOException,
            XMPPException, InterruptedException
    {
        sharedStanzaQueue.put(user, this);
        tlsHandled.reportSuccess();
        saslFeatureReceived.reportSuccess();
    }

    @Override
    protected void sendStanzaInternal(final Stanza packet)
            throws NotConnectedException, InterruptedException
    {
        packet.setFrom(user);
        final MockXmppConnection target = sharedStanzaQueue.get(packet.getTo());
        if (target == null)
        {
            System.out.println("Connection for " + packet.getTo() + " not found!");
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run()
            {
                target.invokeStanzaCollectorsAndNotifyRecvListeners(packet);
            }
        });
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
    public void sendStanza(Stanza packet)
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
            throws OperationFailedException
    {
        Objects.requireNonNull(packet, "packet");

        try
        {
            packet.setFrom(user);
            StanzaCollector packetCollector
                    = createStanzaCollectorAndSend(packet);
            try
            {
                //FIXME: retry allocation on timeout
                return packetCollector.nextResult();
            }
            finally
            {
                packetCollector.cancel();
            }
        }
        catch (InterruptedException
               /* |XMPPException.XMPPErrorException
                | NoResponseException*/ e)
        {
            throw new OperationFailedException(
                    "No response or failed otherwise: " + packet.toXML(),
                    OperationFailedException.GENERAL_ERROR,
                    e);
        }
        catch (NotConnectedException e)
        {
            throw new OperationFailedException(
                    "No connection - unable to send packet: " + packet.toXML(),
                    OperationFailedException.PROVIDER_NOT_REGISTERED,
                    e);
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
