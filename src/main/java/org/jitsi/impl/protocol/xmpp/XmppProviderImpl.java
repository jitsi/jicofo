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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.log.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.retry.*;

import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.sasl.javax.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smackx.caps.*;
import org.jivesoftware.smackx.disco.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * @author Pawel Domas
 */
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

    /**
     * Jingle operation set.
     */
    private final OperationSetJingleImpl jingleOpSet;
    private final JibriIqHandler jibriIqHandler;

    private final Muc muc = new Muc();

    /**
     * The XMPP connection used by this instance.
     */
    @NotNull private final AbstractXMPPConnection connection;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * We need a retry strategy for the first connect attempt. Later those are
     * handled by Smack internally.
     */
    @NotNull private final RetryStrategy connectRetry;

    /**
     * Listens to connection status updates.
     */
    @NotNull private final XmppConnectionListener connListener = new XmppConnectionListener();

    /**
     * Listens to re-connection status updates.
     */
    @NotNull private final XmppReConnectionListener reConnListener = new XmppReConnectionListener();

    @NotNull private final XmppConnectionConfig config;

    /**
     * Creates new instance of {@link XmppProviderImpl} with the given configuration.
     */
    public XmppProviderImpl(
            @NotNull XmppConnectionConfig config,
            @NotNull Logger parentLogger)
    {
        this.config = config;
        this.logger = parentLogger.createChildLogger(XmppProviderImpl.class.getName());
        logger.addContext("xmpp_connection", config.getName());

        EntityCapsManager.setDefaultEntityNode("http://jitsi.org/jicofo");

        jingleOpSet = new OperationSetJingleImpl(this);

        connection = createXmppConnection();
        connectRetry = new RetryStrategy(TaskPools.getScheduledPool());
        jibriIqHandler = new JibriIqHandler();
        connection.registerIQRequestHandler(jibriIqHandler);
    }


    @Override
    public String toString()
    {
        return "XmppProviderImpl " + config;
    }

    @Override
    public void start()
    {
        if (!started.compareAndSet(false, true))
        {
            logger.info("Already started.");
            return;
        }

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

        if (!config.getUseTls())
        {
            connConfig.setSecurityMode(ConnectionConfiguration.SecurityMode.disabled);
        }

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
    private boolean doConnect()
    {
        if (!started.get())
        {
            return false;
        }

        synchronized (this)
        {
            try
            {
                connection.connect();
                logger.info("Connected, JID= " + connection.getUser());

                // XXX Is there a reason we add listeners *after* we call connect()?
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
                return false;
            }
            catch (Exception e)
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
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop()
    {
        if (!started.compareAndSet(true, false))
        {
            logger.info("Already stopped or not started.");
            return;
        }

        synchronized (this)
        {
            connectRetry.cancel();

            connection.disconnect();
            logger.info("Disconnected.");

            connection.unregisterIQRequestHandler(jingleOpSet);
            connection.unregisterIQRequestHandler(jibriIqHandler);
            connection.removeConnectionListener(connListener);
        }

        setRegistered(false);
    }

    @Override
    public @NotNull XmppConnectionConfig getConfig()
    {
        return config;
    }

    @Override
    public AbstractXMPPConnection getXmppConnection()
    {
        return connection;
    }

    @Override
    public @NotNull OperationSetJingle getJingleApi()
    {
        return jingleOpSet;
    }

    /**
     * Generates a {@link JSONObject} with statistics for this {@link XmppProviderImpl}.
     * @return JSON stats
     */
    @SuppressWarnings("unchecked")
    @Override
    public @NotNull JSONObject getStats()
    {
        JSONObject stats = new JSONObject();

        PacketDebugger debugger = PacketDebugger.forConnection(connection);
        if (debugger != null)
        {
            stats.put("total_sent", debugger.getTotalPacketsSent());
            stats.put("total_recv", debugger.getTotalPacketsRecv());
        }

        return stats;
    }

    @Override
    public @NotNull ChatRoom createRoom(@NotNull EntityBareJid name) throws RoomExistsException
    {
        return muc.createChatRoom(name);
    }

    @Override
    public @NotNull ChatRoom findOrCreateRoom(@NotNull EntityBareJid name)
    {
        return muc.findOrCreateRoom(name);
    }

    @Override
    protected void fireRegistrationStateChanged(boolean registered)
    {
        super.fireRegistrationStateChanged(registered);

        if (registered)
        {
            XMPPConnection xmppConnection = getXmppConnection();
            xmppConnection.setReplyTimeout(config.getReplyTimeout().toMillis());
            logger.info("Set replyTimeout=" + config.getReplyTimeout());
        }

    }

    @NotNull
    @Override
    public List<String> discoverFeatures(@NotNull EntityFullJid jid)
    {
        return DiscoveryUtil.discoverParticipantFeatures(this, jid);
    }

    @Override
    public void addJibriIqHandler(@NotNull JibriSessionIqHandler jibriIqHandler)
    {
        this.jibriIqHandler.addJibri(jibriIqHandler);
    }

    @Override
    public void removeJibriIqHandler(@NotNull JibriSessionIqHandler jibriIqHandler)
    {
        this.jibriIqHandler.removeJibri(jibriIqHandler);
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

    private class Muc
    {
        /**
         * The map of active chat rooms mapped by their names.
         */
        private final Map<String, ChatRoomImpl> rooms = new HashMap<>();

        private ChatRoom createChatRoom(EntityBareJid roomJid)
                throws RoomExistsException
        {
            String roomName = roomJid.toString();

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

        private ChatRoom findOrCreateRoom(EntityBareJid roomJid)
        {
            String roomName = roomJid.toString().toLowerCase();

            synchronized (rooms)
            {
                ChatRoom room = rooms.get(roomName);

                if (room == null)
                {
                    try
                    {
                        room = createChatRoom(roomJid);
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
}
