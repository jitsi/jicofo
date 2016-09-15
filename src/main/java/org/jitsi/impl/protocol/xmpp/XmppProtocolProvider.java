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

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.service.protocol.jabber.*;
import net.java.sip.communicator.util.*;

import org.jitsi.impl.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.retry.*;
import org.jitsi.util.Logger;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * XMPP protocol provider service used by Jitsi Meet focus to create anonymous
 * accounts. Implemented with Smack.
 *
 * @author Pawel Domas
 */
public class XmppProtocolProvider
    extends AbstractProtocolProviderService
{
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
    private XMPPConnection connection;

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
     * Colibri operation set.
     */
    private final OperationSetColibriConferenceImpl colibriTools
        = new OperationSetColibriConferenceImpl();

    /**
     * Smack connection adapter to {@link XmppConnection} used by this instance.
     */
    private XmppConnectionAdapter connectionAdapter;

    /**
     * Jitsi service discovery manager.
     */
    private ScServiceDiscoveryManager discoInfoManager;

    /**
     * Creates new instance of {@link XmppProtocolProvider} for given AccountID.
     *
     * @param accountID the <tt>JabberAccountID</tt> that will be used by new
     *                  instance.
     */
    public XmppProtocolProvider(AccountID accountID)
    {
        this.jabberAccountID = (JabberAccountID) accountID;

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
            OperationSetSubscription.class,
            new OpSetSubscriptionImpl(this));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void register(SecurityAuthority securityAuthority)
        throws OperationFailedException
    {
        String serviceName
            = org.jivesoftware.smack.util.StringUtils.parseServer(
                    getAccountID().getUserID());

        String serverAddressUserSetting
            = jabberAccountID.getServerAddress();

        int serverPort
            = getAccountID().getAccountPropertyInt(
                    ProtocolProviderFactory.SERVER_PORT, 5222);

        ConnectionConfiguration connConfig
            = new ConnectionConfiguration(
                    serverAddressUserSetting, serverPort, serviceName);

        connection = new XMPPConnection(connConfig);

        if (logger.isTraceEnabled())
        {
            enableDebugPacketsLogging();
        }

        ScheduledExecutorService executorService
            = ServiceUtils.getService(
                    XmppProtocolActivator.bundleContext,
                    ScheduledExecutorService.class);

        connectRetry = new RetryStrategy(executorService);

        // FIXME we could make retry interval configurable, but we do not have
        // control over retries executed by smack after first connect, so...
        connectRetry.runRetryingTask(
            new SimpleRetryTask(0, 5000L, true, getConnectCallable()));
    }

    private Callable<Boolean> getConnectCallable()
    {
        return new Callable<Boolean>()
        {
            @Override
            public Boolean call()
                throws Exception
            {
                return doConnect();
            }
        };
    }

    /**
     * Method tries to establish the connection to XMPP server and return
     * <tt>false</tt> in case we have failed want to retry connection attempt.
     * <tt>true</tt> is returned when we either connect successfully or when we
     * detect that there is no chance to get connected any any future retries
     * should be cancelled.
     */
    synchronized private boolean doConnect()
    {
        if (connection == null)
            return false;

        try
        {
            connection.connect();

            connection.addConnectionListener(connListener);

            if (jabberAccountID.isAnonymousAuthUsed())
            {
                connection.loginAnonymously();
            }
            else
            {
                String login = jabberAccountID.getAuthorizationName();
                String pass = jabberAccountID.getPassword();
                String resource = jabberAccountID.getResource();
                connection.login(login, pass, resource);
            }

            colibriTools.initialize(getConnectionAdapter());

            jingleOpSet.initialize();

            discoInfoManager = new ScServiceDiscoveryManager(
                XmppProtocolProvider.this, connection,
                new String[]{}, new String[]{}, false);

            notifyConnected();

            logger.info("XMPP provider " + jabberAccountID +
                        " connected (JID: " + connection.getUser() + ")");

            return false;
        }
        catch (XMPPException e)
        {
            logger.error("Failed to connect: " + e.getMessage(), e);
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

    private void enableDebugPacketsLogging()
    {
        // FIXME: consider using packet logging service
        DebugLogger outLogger = new DebugLogger("--> ");

        connection.addPacketSendingListener(outLogger, outLogger);

        DebugLogger inLogger = new DebugLogger("<-- ");

        connection.addPacketListener(inLogger, inLogger);
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
    public String getOurJid()
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
        if (connectionAdapter == null)
        {
            connectionAdapter = new XmppConnectionAdapter(connection);
        }
        return connectionAdapter;
    }

    /**
     * FIXME: move to operation set together with ScServiceDiscoveryManager
     */
    public boolean checkFeatureSupport(String contactAddress, String[] features)
    {
        try
        {
            //FIXME: fix logging levels
            logger.debug("Discovering info for: " + contactAddress);

            DiscoverInfo info = discoInfoManager.discoverInfo(contactAddress);

            logger.debug("HAVE Discovering info for: " + contactAddress);

            logger.debug("Features");
            Iterator<DiscoverInfo.Feature> featuresList = info.getFeatures();
            while (featuresList.hasNext())
            {
                DiscoverInfo.Feature f = featuresList.next();
                logger.debug(f.toXML());
            }

            logger.debug("Identities");
            Iterator<DiscoverInfo.Identity> identities = info.getIdentities();
            while (identities.hasNext())
            {
                DiscoverInfo.Identity identity = identities.next();
                logger.debug(identity.toXML());
            }
        }
        catch (XMPPException e)
        {
            logger.error("Error discovering features: " + e.getMessage());
        }

        for (String feature : features)
        {
            if (!discoInfoManager.supportsFeature(contactAddress, feature))
            {
                return false;
            }
        }
        return true;
    }

    public boolean checkFeatureSupport(String node, String subnode,
                                       String[] features)
    {
        for (String feature : features)
        {
            if (!discoInfoManager.supportsFeature(node, feature))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * FIXME: move to operation set together with ScServiceDiscoveryManager
     */
    public Set<String> discoverItems(String node)
        throws XMPPException
    {
        DiscoverItems itemsDisco = discoInfoManager.discoverItems(node);

        if (logger.isDebugEnabled())
            logger.debug("HAVE Discovered items for: " + node);

        Set<String> result = new HashSet<>();

        Iterator<DiscoverItems.Item> items = itemsDisco.getItems();
        while (items.hasNext())
        {
            DiscoverItems.Item item = items.next();

            if (logger.isDebugEnabled())
                logger.debug(item.toXML());

            result.add(item.getEntityID());
        }

        return result;
    }

    public List<String> getEntityFeatures(String node)
    {
        try
        {
            DiscoverInfo info = discoInfoManager.discoverInfo(node);
            Iterator<DiscoverInfo.Feature> features =  info.getFeatures();
            List<String> featureList = new ArrayList<>();

            while (features.hasNext())
            {
                featureList.add(features.next().getVar());
            }
            
            return featureList;
        }
        catch (XMPPException e)
        {
            logger.debug("Error getting feature list: " + e.getMessage());
            return null;
        }
    }

    class XmppConnectionListener
        implements ConnectionListener
    {
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

        @Override
        public void reconnectingIn(int i)
        {
            logger.info("XMPP reconnecting in: " + i);
        }

        @Override
        public void reconnectionSuccessful()
        {
            logger.info("XMPP reconnection successful");

            notifyConnected();
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
            this.connection = connection;
        }

        @Override
        public void sendPacket(Packet packet)
        {
            if (connection.isConnected())
                connection.sendPacket(packet);
            else
                logger.warn(
                    "No connection - unable to send packet: " + packet.toXML());
        }

        @Override
        public Packet sendPacketAndGetReply(Packet packet)
        {
            PacketCollector packetCollector
                = connection.createPacketCollector(
                        new PacketIDFilter(packet.getPacketID()));

            connection.sendPacket(packet);

            //FIXME: retry allocation on timeout
            Packet response
                = packetCollector.nextResult(
                        SmackConfiguration.getPacketReplyTimeout());

            packetCollector.cancel();

            return response;
        }
    }

    private static class DebugLogger
        implements PacketFilter, PacketListener
    {
        private final String prefix;

        DebugLogger(String prefix)
        {
            this.prefix = prefix;
        }

        @Override
        public boolean accept(Packet packet)
        {
            return true;
        }

        @Override
        public void processPacket(Packet packet)
        {
            logger.trace(prefix + packet.toXML());
        }
    }
}
