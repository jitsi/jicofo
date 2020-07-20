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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.service.protocol.jabber.*;

import org.jitsi.impl.protocol.xmpp.colibri.*;
import org.jitsi.impl.protocol.xmpp.log.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.retry.*;
import org.jitsi.service.configuration.*;

import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.sasl.javax.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smackx.caps.*;
import org.jivesoftware.smackx.disco.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.jivesoftware.smack.SmackException.*;

/**
 * XMPP protocol provider service used by Jitsi Meet focus to create anonymous
 * accounts. Implemented with Smack.
 *
 * @author Pawel Domas
 */
public class XmppProtocolProvider
    extends AbstractProtocolProviderService
{
    static
    {
        XMPPTCPConnection.setUseStreamManagementResumptionDefault(false);
        XMPPTCPConnection.setUseStreamManagementDefault(false);
        SmackConfiguration.setDebuggerFactory(PacketDebugger::new);
    }

    /**
     * The logger used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(XmppProtocolProvider.class);

    /**
     * Active account.
     */
    private final JabberAccountID jabberAccountID;

    /**
     * Jingle operation set.
     */
    private final OperationSetJingleImpl jingleOpSet;

    /**
     * Current registration state.
     */
    private RegistrationState registrationState
        = RegistrationState.UNREGISTERED;

    /**
     * The XMPP connection used by this instance.
     */
    private AbstractXMPPConnection connection;

    /**
     * We need a retry strategy for the first connect attempt. Later those are
     * handled by Smack internally.
     */
    private RetryStrategy connectRetry;

    /**
     * Listens to connection status updates.
     */
    private final XmppConnectionListener connListener
        = new XmppConnectionListener();

    /**
     * Listens to re-connection status updates.
     */
    private final XmppReConnectionListener reConnListener
        = new XmppReConnectionListener();

    /**
     * Colibri operation set.
     */
    private final OperationSetColibriConferenceImpl colibriTools
        = new OperationSetColibriConferenceImpl();

    /**
     * Smack connection adapter to {@link XmppConnection} used by this instance.
     */
    private XmppConnectionAdapter connectionAdapter;

    /**
     * Creates new instance of {@link XmppProtocolProvider} for given AccountID.
     *
     * @param accountID the <tt>JabberAccountID</tt> that will be used by new
     *                  instance.
     */
    public XmppProtocolProvider(AccountID accountID)
    {
        this.jabberAccountID = (JabberAccountID) accountID;

        EntityCapsManager.setDefaultEntityNode("http://jitsi.org/jicofo");

        addSupportedOperationSet(
            OperationSetColibriConference.class, colibriTools);

        this.jingleOpSet = new OperationSetJingleImpl(this);
        addSupportedOperationSet(OperationSetJingle.class, jingleOpSet);

        addSupportedOperationSet(
            OperationSetMultiUserChat.class,
            new OperationSetMultiUserChatImpl(this));

        addSupportedOperationSet(
            OperationSetJitsiMeetTools.class,
            new OperationSetMeetToolsImpl());

        addSupportedOperationSet(
            OperationSetSimpleCaps.class,
            new OpSetSimpleCapsImpl(this));

        addSupportedOperationSet(
            OperationSetDirectSmackXmpp.class,
            new OpSetDirectSmackXmppImpl(this));

        addSupportedOperationSet(
            OperationSetJibri.class,
            new OperationSetJibri(this));
    }

    /**
     * Initializes Jicofo's feature list.
     */
    private void initializeFeaturesList() {
        ServiceDiscoveryManager serviceDiscoveryManager = ServiceDiscoveryManager.getInstanceFor(connection);
        serviceDiscoveryManager.addFeature("https://jitsi.org/meet/jicofo/terminate-restart");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void register(SecurityAuthority securityAuthority)
        throws OperationFailedException
    {
        DomainBareJid serviceName;
        try
        {
            serviceName = JidCreate.domainBareFrom(
                        getAccountID().getUserID());
        }
        catch (XmppStringprepException e)
        {
            throw new OperationFailedException(
                    "Invalid UserID",
                    OperationFailedException.ILLEGAL_ARGUMENT,
                    e);
        }

        String serverAddressUserSetting
            = jabberAccountID.getServerAddress();

        int serverPort
            = getAccountID().getAccountPropertyInt(
                    ProtocolProviderFactory.SERVER_PORT, 5222);

        XMPPTCPConnectionConfiguration.Builder connConfig
            = XMPPTCPConnectionConfiguration.builder()
                .setHost(serverAddressUserSetting)
                .setPort(serverPort)
                .setXmppDomain(serviceName);

        // Required for PacketDebugger and XMPP stats to work
        connConfig.setDebuggerEnabled(true);

        ReconnectionManager.setEnabledPerDefault(true);

        // focus uses SASL Mechanisms ANONYMOUS and PLAIN, but tries
        // authenticate with GSSAPI when it's offered by the server.
        // Disable GSSAPI.
        SASLAuthentication.unregisterSASLMechanism(
            SASLGSSAPIMechanism.class.getName());

        if (jabberAccountID.isAnonymousAuthUsed())
        {
            connConfig.performSaslAnonymousAuthentication();
        }

        ConfigurationService config = FocusBundleActivator.getConfigService();
        if (config.getBoolean(FocusManager.ALWAYS_TRUST_PNAME,false))
        {
            logger.warn("The always_trust config option is enabled. All" +
                        " XMPP server provided certificates are accepted.");
            connConfig.setCustomX509TrustManager(new TrustAllX509TrustManager());
            connConfig.setHostnameVerifier(new TrustAllHostnameVerifier());
        }

        connection = new XMPPTCPConnection(connConfig.build());

        this.initializeFeaturesList();

        ScheduledExecutorService executorService
            = ServiceUtils2.getService(
                    XmppProtocolActivator.bundleContext,
                    ScheduledExecutorService.class);

        connectRetry = new RetryStrategy(executorService);

        EntityCapsManager capsManager
            = EntityCapsManager.getInstanceFor(connection);

        capsManager.enableEntityCaps();

        // FIXME we could make retry interval configurable, but we do not have
        // control over retries executed by smack after first connect, so...
        connectRetry.runRetryingTask(
            new SimpleRetryTask(0, 5000L, true, this::doConnect));
    }

    /**
     * Method tries to establish the connection to XMPP server and return
     * <tt>false</tt> in case we have failed want to retry connection attempt.
     * <tt>true</tt> is returned when we either connect successfully or when we
     * detect that there is no chance to get connected any any future retries
     * should be canceled.
     */
    synchronized private boolean doConnect()
    {
        if (connection == null)
            return false;

        try
        {
            connection.connect();

            connection.addConnectionListener(connListener);

            ReconnectionManager
                .getInstanceFor(connection)
                .addReconnectionListener(reConnListener);

            if (!jabberAccountID.isAnonymousAuthUsed())
            {
                String login = jabberAccountID.getAuthorizationName();
                String pass = jabberAccountID.getPassword();
                Resourcepart resource
                        = Resourcepart.from(jabberAccountID.getResource());
                connection.login(login, pass, resource);
            }

            colibriTools.initialize(getConnectionAdapter());

            connection.registerIQRequestHandler(jingleOpSet);

            logger.info("XMPP provider " + jabberAccountID +
                        " connected (JID: " + connection.getUser() + ")");

            return false;
        }
        catch (XMPPException
                | InterruptedException
                | SmackException
                | IOException e)
        {
            logger.error("Failed to connect/login: " + e.getMessage(), e);
            // If the connect part succeeded, but login failed we don't want to
            // rely on Smack's built-in retries, as it will be handled by
            // the RetryStrategy
            connection.removeConnectionListener(connListener);

            ReconnectionManager reconnectionManager
                = ReconnectionManager.getInstanceFor(connection);
            if (reconnectionManager != null)
                reconnectionManager.removeReconnectionListener(reConnListener);

            if (connection.isConnected())
            {
                connection.disconnect();
            }
            return true;
        }
    }

    private void notifyConnected()
    {
        if (!RegistrationState.REGISTERED.equals(registrationState))
        {
            RegistrationState oldState = registrationState;
            registrationState = RegistrationState.REGISTERED;

            fireRegistrationStateChanged(
                oldState,
                RegistrationState.REGISTERED,
                RegistrationStateChangeEvent.REASON_NOT_SPECIFIED,
                null);
        }
    }

    private void notifyDisconnected()
    {
        if (!RegistrationState.UNREGISTERED.equals(registrationState))
        {
            RegistrationState oldState = registrationState;
            registrationState = RegistrationState.UNREGISTERED;

            fireRegistrationStateChanged(
                oldState,
                RegistrationState.UNREGISTERED,
                RegistrationStateChangeEvent.REASON_NOT_SPECIFIED,
                null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void unregister()
        throws OperationFailedException
    {
        if (connection == null)
            return;

        if (connectRetry != null)
        {
            connectRetry.cancel();
            connectRetry = null;
        }

        connection.disconnect();

        connection.unregisterIQRequestHandler(jingleOpSet);
        connection.removeConnectionListener(connListener);

        connection = null;

        logger.info("XMPP provider " + jabberAccountID + " disconnected");

        notifyDisconnected();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RegistrationState getRegistrationState()
    {
        return registrationState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProtocolName()
    {
        return ProtocolNames.JABBER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProtocolIcon getProtocolIcon()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown()
    {
        if (connection != null)
        {
            try
            {
                unregister();
            }
            catch (OperationFailedException e)
            {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AccountID getAccountID()
    {
        return jabberAccountID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSignalingTransportSecure()
    {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransportProtocol getTransportProtocol()
    {
        return TransportProtocol.UNKNOWN;
    }

    /**
     * Returns implementation of {@link org.jitsi.protocol.xmpp.XmppConnection}.
     *
     * @return implementation of {@link org.jitsi.protocol.xmpp.XmppConnection}
     */
    public XMPPConnection getConnection()
    {
        return connection;
    }

    /**
     * Returns our JID if we're connected or <tt>null</tt> otherwise.
     *
     * @return our JID if we're connected or <tt>null</tt> otherwise
     */
    public EntityFullJid getOurJid()
    {
        return connection != null ? connection.getUser() : null;
    }

    /**
     * Lazy initializer for {@link #connectionAdapter}.
     *
     * @return {@link XmppConnection} provided by this instance.
     */
    XmppConnection getConnectionAdapter()
    {
        if (connectionAdapter == null && connection != null)
        {
            connectionAdapter = new XmppConnectionAdapter(connection);
        }
        return connectionAdapter;
    }

    /**
     * Generates a {@link JSONObject} with statistics  for this protocol
     * provider instance.
     * @return JSON stats
     */
    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        JSONObject stats = new JSONObject();

        if (connection == null)
        {
            return stats;
        }

        PacketDebugger debugger = PacketDebugger.forConnection(connection);

        if (debugger == null)
        {
            return stats;
        }

        stats.put("total_sent", debugger.getTotalPacketsSent());
        stats.put("total_recv", debugger.getTotalPacketsRecv());

        return stats;
    }

    class XmppConnectionListener
        implements ConnectionListener
    {
        @Override
        public void connected(XMPPConnection connection)
        {
        }

        @Override
        public void authenticated(XMPPConnection connection, boolean resumed)
        {
            notifyConnected();
        }

        @Override
        public void connectionClosed()
        {
            logger.info("XMPP connection closed");

            //shutdownConnection();

            //notifyConnFailed(null);

            notifyDisconnected();
        }

        @Override
        public void connectionClosedOnError(Exception e)
        {
            logger.error("XMPP connection closed on error: " + e.getMessage());

            //shutdownConnection();

            //notifyConnFailed(e);

            notifyDisconnected();
        }

        /**
         * Deprecated and will be removed in smack 4.3
         */
        @Override
        @SuppressWarnings("deprecation")
        public void reconnectionSuccessful()
        {}

        /**
         * Deprecated and will be removed in smack 4.3
         * @param e
         */
        @Override
        @SuppressWarnings("deprecation")
        public void reconnectionFailed(Exception e)
        {}

        /**
         * Deprecated and will be removed in smack 4.3
         * @param i
         */
        @Override
        @SuppressWarnings("deprecation")
        public void reconnectingIn(int i)
        {}
    }

    /**
     * Listener that just logs that we are currently reconnecting or we
     * failed to reconnect.
     */
    static class XmppReConnectionListener
        implements ReconnectionListener
    {
        @Override
        public void reconnectingIn(int i)
        {
            logger.info("XMPP reconnecting in: " + i);
        }

        @Override
        public void reconnectionFailed(Exception e)
        {
            logger.error("XMPP reconnection failed: " + e.getMessage());
        }
    }

    /**
     * Implements {@link XmppConnection}.
     */
    private static class XmppConnectionAdapter
        implements XmppConnection
    {
        private final XMPPConnection connection;

        XmppConnectionAdapter(XMPPConnection connection)
        {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        @Override
        public EntityFullJid getUser()
        {
            return this.connection.getUser();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void sendStanza(Stanza packet)
        {
            Objects.requireNonNull(packet, "packet");
            try
            {
                connection.sendStanza(packet);
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

        /**
         * {@inheritDoc}
         */
        @Override
        public IQ sendPacketAndGetReply(IQ packet)
            throws OperationFailedException
        {
            Objects.requireNonNull(packet, "packet");

            try
            {
                StanzaCollector packetCollector
                        = connection.createStanzaCollectorAndSend(packet);
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
                    /*| XMPPErrorException
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
        public void sendIqWithResponseCallback(
                IQ iq,
                StanzaListener stanzaListener,
                ExceptionCallback exceptionCallback,
                long timeout)
            throws NotConnectedException, InterruptedException
        {
            connection.sendIqWithResponseCallback(
                iq, stanzaListener, exceptionCallback, timeout);
        }

        @Override
        public IQRequestHandler registerIQRequestHandler(
            IQRequestHandler handler)
        {
            return connection.registerIQRequestHandler(handler);
        }

        @Override
        public IQRequestHandler unregisterIQRequestHandler(
            IQRequestHandler handler)
        {
            return connection.unregisterIQRequestHandler(handler);
        }
    }
}
