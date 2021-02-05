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
package org.jitsi.impl.protocol.xmpp;

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.log.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.retry.*;

import org.jitsi.utils.logging2.*;
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
import java.lang.*;
import java.lang.SuppressWarnings;
import java.util.*;
import java.util.concurrent.*;

import static org.jivesoftware.smack.SmackException.*;

/**
 * TODO: fix inconsistent synchronization.
 *
 * @author Pawel Domas
 */
@SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
public class XmppProviderImpl
    extends AbstractXmppProvider
{
    static
    {
        XMPPTCPConnection.setUseStreamManagementResumptionDefault(false);
        XMPPTCPConnection.setUseStreamManagementDefault(false);
        SmackConfiguration.setDebuggerFactory(PacketDebugger::new);
    }

    private final Logger logger;

    private final List<XmppConnectionInitializedListener> xmppConnectionListeners = new ArrayList<>();

    /**
     * Jingle operation set.
     */
    private final OperationSetJingleImpl jingleOpSet;
    private final OperationSetJibri jibriApi;

    private final Muc muc = new Muc();

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
    private final XmppConnectionListener connListener = new XmppConnectionListener();

    /**
     * Listens to re-connection status updates.
     */
    private final XmppReConnectionListener reConnListener = new XmppReConnectionListener();

    /**
     * Smack connection adapter to {@link XmppConnection} used by this instance.
     */
    private XmppConnectionAdapter connectionAdapter;

    @NotNull private final XmppConnectionConfig config;

    /**
     * Creates new instance of {@link XmppProviderImpl} with the given configuration.
     */
    public XmppProviderImpl(
            @NotNull XmppConnectionConfig config,
            @NotNull ScheduledExecutorService executor,
            @NotNull Logger parentLogger)
    {
        this.config = config;
        this.logger = parentLogger.createChildLogger(XmppProviderImpl.class.getName());
        logger.addContext("xmpp_connection", config.getName());

        EntityCapsManager.setDefaultEntityNode("http://jitsi.org/jicofo");

        jingleOpSet = new OperationSetJingleImpl(this);
        jibriApi = new OperationSetJibri(this);

        this.connection = createXmppConnection();
        connectRetry = new RetryStrategy(executor);
    }


    @Override
    public String toString()
    {
        return "XmppProviderImpl " + config;
    }

    @Override
    public void start()
    {
        connectRetry.runRetryingTask(new SimpleRetryTask(0, 5000L, true, this::doConnect));
    }

    /**
     * Create the Smack {@link AbstractXMPPConnection} based on the specicied config.
     * @return
     */
    private AbstractXMPPConnection createXmppConnection()
    {
        XMPPTCPConnectionConfiguration.Builder connConfig
                = XMPPTCPConnectionConfiguration.builder()
                .setHost(config.getHostname())
                .setPort(config.getPort())
                .setXmppDomain(config.getDomain());

        // Required for PacketDebugger and XMPP stats to work
        connConfig.setDebuggerEnabled(true);

        ReconnectionManager.setEnabledPerDefault(true);

        // focus uses SASL Mechanisms ANONYMOUS and PLAIN, but tries
        // authenticate with GSSAPI when it's offered by the server.
        // Disable GSSAPI.
        SASLAuthentication.unregisterSASLMechanism(SASLGSSAPIMechanism.class.getName());

        if (config.getPassword() == null)
        {
            connConfig.performSaslAnonymousAuthentication();
        }

        if (config.getDisableCertificateVerification())
        {
            logger.warn("Disabling TLS certificate verification!");
            connConfig.setCustomX509TrustManager(new TrustAllX509TrustManager());
            connConfig.setHostnameVerifier(new TrustAllHostnameVerifier());
        }

        AbstractXMPPConnection connection = new XMPPTCPConnection(connConfig.build());

        // This can be removed once all clients are updated reading this from the presence conference property
        ServiceDiscoveryManager serviceDiscoveryManager = ServiceDiscoveryManager.getInstanceFor(connection);
        serviceDiscoveryManager.addFeature("https://jitsi.org/meet/jicofo/terminate-restart");

        EntityCapsManager capsManager = EntityCapsManager.getInstanceFor(connection);
        capsManager.enableEntityCaps();

        return connection;
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
        {
            return false;
        }

        try
        {
            connection.connect();

            connection.addConnectionListener(connListener);

            ReconnectionManager.getInstanceFor(connection).addReconnectionListener(reConnListener);

            if (config.getPassword() != null)
            {
                String login = config.getUsername().toString();
                String pass = config.getPassword();
                Resourcepart resource = config.getUsername();
                connection.login(login, pass, resource);
            }

            connection.registerIQRequestHandler(jingleOpSet);

            logger.info("XMPP provider connected (JID: " + connection.getUser() + ")");

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

            ReconnectionManager reconnectionManager = ReconnectionManager.getInstanceFor(connection);
            if (reconnectionManager != null)
            {
                reconnectionManager.removeReconnectionListener(reConnListener);
            }

            if (connection.isConnected())
            {
                connection.disconnect();
            }
            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void stop()
    {
        if (connection == null)
        {
            return;
        }

        if (connectRetry != null)
        {
            connectRetry.cancel();
            connectRetry = null;
        }

        connection.disconnect();

        connection.unregisterIQRequestHandler(jingleOpSet);
        connection.removeConnectionListener(connListener);

        connection = null;

        logger.info(this + " Disconnected ");

        setRegistered(false);
    }

    @Override
    public @NotNull XmppConnectionConfig getConfig()
    {
        return config;
    }

    /**
     * Returns implementation of {@link org.jitsi.protocol.xmpp.XmppConnection}.
     *
     * @return implementation of {@link org.jitsi.protocol.xmpp.XmppConnection}
     */
    @Override
    public XMPPConnection getXmppConnectionRaw()
    {
        return connection;
    }

    @Override
    public @NotNull OperationSetJingle getJingleApi()
    {
        return jingleOpSet;
    }

    @Override
    public OperationSetJibri getJibriApi()
    {
        return jibriApi;
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

    @Override
    public XmppConnection getXmppConnection()
    {
        if (connectionAdapter == null && connection != null)
        {
            connectionAdapter = new XmppConnectionAdapter(connection);
        }
        return connectionAdapter;
    }

    /**
     * Generates a {@link JSONObject} with statistics for this {@link XmppProviderImpl}.
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

    @Override
    public @NotNull ChatRoom createRoom(@NotNull String name) throws RoomExistsException, XmppStringprepException
    {
        return muc.createChatRoom(name);
    }

    @Override
    public @NotNull ChatRoom findOrCreateRoom(@NotNull String name) throws XmppStringprepException
    {
        return muc.findOrCreateRoom(name);
    }

    @Override
    protected void fireRegistrationStateChanged(boolean registered)
    {
        super.fireRegistrationStateChanged(registered);

        if (registered)
        {
            XmppConnection xmppConnection = getXmppConnection();
            if (xmppConnection != null)
            {
                xmppConnection.setReplyTimeout(config.getReplyTimeout().toMillis());
                xmppConnectionListeners.forEach(it -> it.xmppConnectionInitialized(xmppConnection));
                logger.info("Set replyTimeout=" + config.getReplyTimeout());
            }
            else
            {
                logger.error("Unable to set Smack replyTimeout, no XmppConnection.");
            }
        }

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
            setRegistered(true);
        }

        @Override
        public void connectionClosed()
        {
            logger.info("XMPP connection closed");

            //shutdownConnection();

            //notifyConnFailed(null);

            setRegistered(false);
        }

        @Override
        public void connectionClosedOnError(Exception e)
        {
            logger.error("XMPP connection closed on error: " + e.getMessage());

            //shutdownConnection();

            //notifyConnFailed(e);

            setRegistered(false);
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
    private class XmppReConnectionListener
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
    private class XmppConnectionAdapter
        implements XmppConnection
    {
        private final XMPPConnection connection;

        XmppConnectionAdapter(XMPPConnection connection)
        {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        public XMPPConnection getConnection()
        {
            return connection;
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
                logger.error("No connection - unable to send packet: " + packet.toXML(), e);
            }
            catch (InterruptedException e)
            {
                logger.error("Failed to send packet: " + packet.toXML().toString(), e);
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
                StanzaCollector packetCollector = connection.createStanzaCollectorAndSend(packet);
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
            connection.sendIqWithResponseCallback(iq, stanzaListener, exceptionCallback, timeout);
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

        @Override
        public void setReplyTimeout(long replyTimeoutMs)
        {
            connection.setReplyTimeout(replyTimeoutMs);
        }
    }

    private class Muc
    {
        /**
         * The map of active chat rooms mapped by their names.
         */
        private final Map<String, ChatRoomImpl> rooms = new HashMap<>();

        private ChatRoom createChatRoom(String roomName)
                throws XmppStringprepException, RoomExistsException
        {
            EntityBareJid roomJid = JidCreate.entityBareFrom(roomName);

            synchronized (rooms)
            {
                if (rooms.containsKey(roomName))
                {
                    throw new RoomExistsException("Room '" + roomName + "' exists");
                }

                ChatRoomImpl newRoom = new ChatRoomImpl(XmppProviderImpl.this, roomJid, this::removeRoom);

                rooms.put(newRoom.getName(), newRoom);

                return newRoom;
            }
        }

        private ChatRoom findOrCreateRoom(String roomName)
                throws XmppStringprepException
        {
            roomName = roomName.toLowerCase();

            synchronized (rooms)
            {
                ChatRoom room = rooms.get(roomName);

                if (room == null)
                {
                    try
                    {
                        room = createChatRoom(roomName);
                    }
                    catch (RoomExistsException e)
                    {
                        throw new RuntimeException("Unexpected RoomExistsException.");
                    }
                }
                return room;
            }
        }

        public void removeRoom(ChatRoomImpl chatRoom)
        {
            synchronized (rooms)
            {
                rooms.remove(chatRoom.getName());
            }
        }
    }

    @Override
    public void addXmppConnectionListener(XmppConnectionInitializedListener listener)
    {
        xmppConnectionListeners.add(listener);

        XmppConnection connection = getXmppConnection();
        if (connection != null)
        {
            listener.xmppConnectionInitialized(connection);
        }
    }
}
