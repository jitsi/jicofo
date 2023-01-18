/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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
package org.jitsi.jicofo.xmpp

import org.jitsi.impl.protocol.xmpp.ChatRoom
import org.jitsi.impl.protocol.xmpp.ChatRoomImpl
import org.jitsi.impl.protocol.xmpp.RegistrationListener
import org.jitsi.impl.protocol.xmpp.log.PacketDebugger
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.TaskPools.Companion.ioPool
import org.jitsi.jicofo.xmpp.XmppProvider.RoomExistsException
import org.jitsi.retry.RetryStrategy
import org.jitsi.retry.SimpleRetryTask
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.TrustAllHostnameVerifier
import org.jitsi.xmpp.TrustAllX509TrustManager
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SASLAuthentication
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.caps.EntityCapsManager
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.disco.packet.DiscoverInfo
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Wraps a Smack [XMPPConnection]. */
class XmppProvider(val config: XmppConnectionConfig, parentLogger: Logger) {
    private val logger: Logger = createChildLogger(parentLogger).apply {
        addContext("xmpp_connection", config.name)
    }

    /** We need a retry strategy for the first connect attempt. Later those are handled by Smack internally. */
    private val connectRetry = RetryStrategy(TaskPools.scheduledPool)

    /** A list of all listeners registered with this instance. */
    private val registrationListeners = mutableListOf<RegistrationListener>()

    /** The Smack [XMPPConnection] used by this instance. */
    val xmppConnection = createXmppConnection(config, logger)

    private val started = AtomicBoolean(false)
    var registered = false
        private set(value) {
            if (value != field) {
                field = value
                fireRegistrationStateChanged(value)
            }
        }

    private val muc = Muc(this)

    /** Listens to re-connection status updates. */
    private val reconnectionListener = object : ReconnectionListener {
        override fun reconnectingIn(seconds: Int) {
            logger.info("XMPP reconnecting in: $seconds seconds.")
        }

        override fun reconnectionFailed(e: Exception) {
            logger.error("XMPP reconnection failed: ${e.message}", e)
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun authenticated(connection: XMPPConnection?, resumed: Boolean) { registered = true }

        override fun connectionClosed() {
            logger.info("XMPP connection closed")
            registered = false
        }

        override fun connectionClosedOnError(e: Exception) {
            logger.error("XMPP connection closed on error: ${e.message}", e)
            registered = false
        }
    }

    override fun toString(): String = "XmppProvider[$config]"

    fun start() {
        if (!started.compareAndSet(false, true)) {
            logger.info("Already started.")
        } else {
            connectRetry.runRetryingTask(
                SimpleRetryTask(0, 5000L, true) {
                    this.doConnect()
                }
            )
        }
    }

    fun shutdown() {
        if (!started.compareAndSet(true, false)) {
            logger.info("Already stopped or not started.")
        } else {
            synchronized(this) {
                connectRetry.cancel()
                xmppConnection.disconnect()
                logger.info("Disconnected.")
                xmppConnection.removeConnectionListener(connectionListener)
            }
            registered = false
        }
    }

    /**
     * Registers the specified listener with this provider so that it would receive notifications on changes of its
     * state.
     */
    fun addRegistrationListener(listener: RegistrationListener) = synchronized(registrationListeners) {
        if (!registrationListeners.contains(listener)) {
            registrationListeners.add(listener)
        }
    }

    /** Removes the specified listener. */
    fun removeRegistrationListener(listener: RegistrationListener) = synchronized(registrationListeners) {
        registrationListeners.remove(listener)
    }

    /**
     * Method tries to establish the connection to XMPP server and return
     * <tt>false</tt> in case we have failed want to retry connection attempt.
     * <tt>true</tt> is returned when we either connect successfully or when we
     * detect that there is no chance to get connected any any future retries
     * should be canceled.
     */
    private fun doConnect(): Boolean {
        if (!started.get()) {
            return false
        }
        synchronized(this) {
            return try {
                xmppConnection.connect()
                logger.info("Connected, JID=" + xmppConnection.user)

                // XXX Is there a reason we add listeners *after* we call connect()?
                xmppConnection.addConnectionListener(connectionListener)
                ReconnectionManager.getInstanceFor(xmppConnection)
                    .addReconnectionListener(reconnectionListener)
                if (config.password != null) {
                    val login = config.username.toString()
                    val pass = config.password
                    val resource = config.resource
                    xmppConnection.login(login, pass, resource)
                }
                false
            } catch (e: java.lang.Exception) {
                logger.error("Failed to connect/login: " + e.message, e)
                // If the connect part succeeded, but login failed we don't want to
                // rely on Smack's built-in retries, as it will be handled by
                // the RetryStrategy
                xmppConnection.removeConnectionListener(connectionListener)
                val reconnectionManager =
                    ReconnectionManager.getInstanceFor(xmppConnection)
                reconnectionManager?.removeReconnectionListener(reconnectionListener)
                if (xmppConnection.isConnected) {
                    xmppConnection.disconnect()
                }
                true
            }
        }
    }

    private fun fireRegistrationStateChanged(registered: Boolean) {
        val listeners: List<RegistrationListener> = synchronized(registrationListeners) {
            registrationListeners.toList()
        }
        listeners.forEach {
            ioPool.submit {
                try {
                    it.registrationChanged(registered)
                } catch (throwable: Throwable) {
                    logger.error("An error occurred while executing registrationStateChanged() on $it", throwable)
                }
            }
        }

        if (registered) {
            xmppConnection.replyTimeout = config.replyTimeout.toMillis()
            logger.info("Set replyTimeout=${config.replyTimeout}")
        }
    }

    @Throws(RoomExistsException::class)
    fun createRoom(name: EntityBareJid): ChatRoom = muc.createChatRoom(name)
    fun findOrCreateRoom(name: EntityBareJid): ChatRoom = muc.findOrCreateRoom(name)

    fun discoverFeatures(jid: EntityFullJid): Set<Features> {
        if (!xmppConnection.isConnected) {
            logger.error("Can not discover features, not connected.")
            return Features.defaultFeatures
        }
        val discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppConnection)
        if (discoveryManager == null) {
            logger.error("Can not discover features, no ServiceDiscoveryManager")
            return Features.defaultFeatures
        }

        val start = System.currentTimeMillis()
        val participantFeatures: List<String> = try {
            discoveryManager.discoverInfo(jid)?.features?.map { it.`var` }?.toList() ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to discover features for $jid: ${e.message}, assuming default feature set.", e)
            return Features.defaultFeatures
        }

        logger.info("Discovered features for $jid in ${System.currentTimeMillis() - start} ms.")
        return participantFeatures.mapNotNull { Features.parseString(it) }.toSet()
    }

    fun discoverInfo(jid: Jid): DiscoverInfo? {
        val discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppConnection)
        if (discoveryManager == null) {
            logger.info("Can not discover info, no ServiceDiscoveryManager")
            return null
        }
        return try {
            discoveryManager.discoverInfo(jid)
        } catch (e: Exception) {
            when (e) {
                is XMPPException.XMPPErrorException, is SmackException.NotConnectedException,
                is SmackException.NoResponseException, is InterruptedException ->
                    logger.warn("Failed to discover info", e)
                else -> throw e
            }
            null
        }
    }

    companion object {
        init {
            EntityCapsManager.setDefaultEntityNode("http://jitsi.org/jicofo")
            ReconnectionManager.setEnabledPerDefault(true)
            // Smack uses SASL Mechanisms ANONYMOUS and PLAIN, but tries to authenticate with GSSAPI when it's offered
            // by the server. Disable GSSAPI.
            SASLAuthentication.unregisterSASLMechanism("org.jivesoftware.smack.sasl.javax.SASLGSSAPIMechanism")
            XMPPTCPConnection.setUseStreamManagementResumptionDefault(false)
            XMPPTCPConnection.setUseStreamManagementDefault(false)
        }
    }

    class RoomExistsException(message: String) : Exception(message)
}

/** Create the Smack [AbstractXMPPConnection] based on the specified config. */
private fun createXmppConnection(config: XmppConnectionConfig, logger: Logger): AbstractXMPPConnection {
    val connConfig = XMPPTCPConnectionConfiguration.builder().apply {
        setHost(config.hostname)
        setPort(config.port)
        setXmppDomain(config.domain)
        if (PacketDebugger.isEnabled()) {
            // If XMPP debug logging is enabled, insert our debugger.
            setDebuggerFactory { PacketDebugger(it, config.name) }
        }
        if (!config.useTls) {
            setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
        } else {
            /* TODO - make the required except on localhost. */
            setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
        }
        if (config.password == null) {
            performSaslAnonymousAuthentication()
        }
        if (config.disableCertificateVerification) {
            logger.warn("Disabling TLS certificate verification!")
            setCustomX509TrustManager(TrustAllX509TrustManager())
            setHostnameVerifier(TrustAllHostnameVerifier())
        }
    }

    val connection = XMPPTCPConnection(connConfig.build())

    // This can be removed once all clients are updated reading this from the presence conference property
    ServiceDiscoveryManager.getInstanceFor(connection).addFeature("https://jitsi.org/meet/jicofo/terminate-restart")
    EntityCapsManager.getInstanceFor(connection).enableEntityCaps()
    return connection
}

private class Muc(val xmppProvider: XmppProvider) {
    /** The active chat rooms mapped by their name. */
    private val rooms: MutableMap<String, ChatRoomImpl> = HashMap()

    @Throws(RoomExistsException::class)
    fun createChatRoom(roomJid: EntityBareJid): ChatRoom {
        val roomName = roomJid.toString().lowercase(Locale.getDefault())
        synchronized(rooms) {
            if (rooms.containsKey(roomName)) {
                throw RoomExistsException("Room '$roomName' exists")
            }
            return ChatRoomImpl(xmppProvider, roomJid) { chatRoom: ChatRoomImpl -> removeRoom(chatRoom) }.also {
                rooms[roomName] = it
            }
        }
    }

    fun findOrCreateRoom(roomJid: EntityBareJid): ChatRoom {
        val roomName = roomJid.toString().lowercase(Locale.getDefault())
        synchronized(rooms) {
            try {
                return rooms[roomName] ?: createChatRoom(roomJid)
            } catch (e: RoomExistsException) {
                throw RuntimeException("Unexpected RoomExistsException.")
            }
        }
    }

    fun removeRoom(chatRoom: ChatRoomImpl) = synchronized(rooms) {
        rooms.remove(chatRoom.roomJid.toString().lowercase(Locale.getDefault()))
    }
}
