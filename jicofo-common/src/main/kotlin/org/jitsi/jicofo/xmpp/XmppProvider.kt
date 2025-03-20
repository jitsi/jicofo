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

import org.jitsi.impl.protocol.xmpp.log.PacketDebugger
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.metrics.GlobalMetrics
import org.jitsi.jicofo.xmpp.XmppProvider.RoomExistsException
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomImpl
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.caps.EntityCapsManager
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import javax.net.ssl.X509TrustManager

/** Wraps a Smack [XMPPConnection]. */
class XmppProvider(val config: XmppConnectionConfig, parentLogger: Logger) {
    private val logger: Logger = createChildLogger(parentLogger).apply {
        addContext("xmpp_connection", config.name)
    }

    /** A task which will keep trying to connect to the XMPP server at a fixed 1-second delay. Smack's
     *  ReconnectionManager only kicks in once a connection suceeds. */
    private var connectTask: ScheduledFuture<*>? = null
    private val connectSyncRoot = Any()

    /** A list of all listeners registered with this instance. */
    private val listeners = CopyOnWriteArraySet<Listener>()

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

    var components: Set<Component> = emptySet()
        private set(value) {
            if (value != field) {
                field = value
                logger.warn("Discovered components: $field")
                fireComponentsChanged(value)
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

            if (xmppConnection.isConnected) {
                xmppConnection.disconnect()

                // In some after cases with Stream Management Smack's ReconnectionManager gives up. Make sure we keep
                // trying.
                scheduleConnectTask()
            }
        }
    }

    /** Start/restart the connect task. */
    private fun scheduleConnectTask() = synchronized(connectSyncRoot) {
        val delay = 1L
        connectTask?.cancel(true)
        connectTask = TaskPools.scheduledPool.scheduleAtFixedRate({
            try {
                if (doConnect()) {
                    logger.warn("Failed to connect, will re-try after $delay second")
                } else {
                    logger.info("Connected.")
                    synchronized(connectSyncRoot) {
                        connectTask?.cancel(false)
                        connectTask = null
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to connect: ${e.message}, will re-try after $delay second", e)
            }
        }, 0, delay, TimeUnit.SECONDS)
    }

    private val connectionListener = object : ConnectionListener {
        override fun authenticated(connection: XMPPConnection?, resumed: Boolean) {
            registered = true
            logger.info(
                "Registered (resumed=$resumed)." + if (connection is XMPPTCPConnection) {
                    " isSmEnabled:" + connection.isSmEnabled +
                        " isSmAvailable:" + connection.isSmAvailable +
                        " isSmResumptionPossible:" + connection.isSmResumptionPossible
                } else {
                    ""
                }
            )

            config.xmppDomain?.let {
                logger.info("Will discover components for $it")
                TaskPools.ioPool.submit { discoverComponents(it) }
            } ?: run {
                logger.info("No xmpp-domain configured, will not discover components.")
            }
        }

        override fun connectionClosed() {
            logger.info("XMPP connection closed")
            GlobalMetrics.xmppDisconnects.inc()
            registered = false
        }

        override fun connectionClosedOnError(e: Exception) {
            logger.error("XMPP connection closed on error: ${e.message}", e)
            GlobalMetrics.xmppDisconnects.inc()
            registered = false
        }
    }

    override fun toString(): String = "XmppProvider[$config]"

    fun start() {
        if (!started.compareAndSet(false, true)) {
            logger.info("Already started.")
        } else {
            scheduleConnectTask()
        }
    }

    fun shutdown() {
        if (!started.compareAndSet(true, false)) {
            logger.info("Already stopped or not started.")
        } else {
            synchronized(this) {
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
    fun addListener(listener: Listener) = listeners.add(listener)

    /** Removes the specified listener. */
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /**
     * Tries to establish the XMPP connection.
     *
     * @return `true` if another attempt should be performed, and `false` otherwise. Returns `false` (i.e. success) if
     * either the connection was successful or the provider hasn't been started.
     */
    private fun doConnect(): Boolean {
        if (!started.get()) {
            return false
        }
        synchronized(this) {
            return try {
                xmppConnection.connect()
                logger.info("Connected, JID=${xmppConnection.user}")

                // XXX Is there a reason we add listeners *after* we call connect()?
                xmppConnection.addConnectionListener(connectionListener)
                ReconnectionManager.getInstanceFor(xmppConnection)?.addReconnectionListener(reconnectionListener)
                if (config.password != null) {
                    val login = config.username.toString()
                    val pass = config.password
                    val resource = config.resource
                    xmppConnection.login(login, pass, resource)
                }
                false
            } catch (e: java.lang.Exception) {
                logger.error("Failed to connect/login: ${e.message}", e)
                // If the connect part succeeded, but login failed we don't want to
                // rely on Smack's built-in retries, as it will be handled by
                // the RetryStrategy
                xmppConnection.removeConnectionListener(connectionListener)
                ReconnectionManager.getInstanceFor(xmppConnection)?.removeReconnectionListener(reconnectionListener)
                if (xmppConnection.isConnected) {
                    xmppConnection.disconnect()
                }
                true
            }
        }
    }

    private fun fireComponentsChanged(components: Set<Component>) = listeners.forEach {
        TaskPools.ioPool.submit {
            try {
                it.componentsChanged(components)
            } catch (throwable: Throwable) {
                logger.error("An error occurred while executing componentsChanged() on $it", throwable)
            }
        }
    }

    private fun fireRegistrationStateChanged(registered: Boolean) {
        listeners.forEach {
            TaskPools.ioPool.submit {
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
    fun createRoom(name: EntityBareJid): ChatRoom = muc.createChatRoom(name, null)
    fun findOrCreateRoom(name: EntityBareJid, logLevel: Level): ChatRoom = muc.findOrCreateRoom(name, logLevel)

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
        val featureStrings: List<String> = try {
            discoveryManager.discoverInfo(jid)?.features?.map { it.`var` }?.toList() ?: emptyList()
        } catch (e: SmackException.NoResponseException) {
            logger.info("No response for disco#info, assuming default features.")
            return Features.defaultFeatures
        } catch (e: Exception) {
            logger.warn("Failed to discover features for $jid: ${e.message}, assuming default feature set.", e)
            return Features.defaultFeatures
        }

        logger.info("Discovered features for $jid in ${System.currentTimeMillis() - start} ms.")
        val features = featureStrings.mapNotNull { Features.parseString(it) }.toSet()
        if (features.size != featureStrings.size) {
            val unrecognizedFeatures = featureStrings - features.map { it.value }.toSet()
            logger.info("Unrecognized features for $jid: $unrecognizedFeatures")
        }
        return features
    }

    private fun discoverComponents(domain: DomainBareJid) {
        val discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppConnection)

        val components: Set<Component> = if (discoveryManager == null) {
            logger.info("Can not discover components, no ServiceDiscoveryManager")
            emptySet()
        } else {
            try {
                discoveryManager.discoverInfo(domain)?.identities
                    ?.filter { it.category == "component" }
                    ?.map { Component(it.type, it.name) }?.toSet() ?: emptySet()
            } catch (e: Exception) {
                logger.warn("Failed to discover info", e)
                emptySet()
            }
        }

        this.components = components
    }

    class RoomExistsException(message: String) : Exception(message)
    interface Listener {
        fun registrationChanged(registered: Boolean) { }
        fun componentsChanged(components: Set<Component>) { }
    }
    data class Component(val type: String, val address: String)
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
            /* TODO - make this required except on localhost. */
            setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
        }
        if (config.password == null) {
            performSaslAnonymousAuthentication()
        }
        if (config.disableCertificateVerification) {
            logger.warn("Disabling TLS certificate verification!")
            setCustomX509TrustManager(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            setHostnameVerifier { _, _ -> true }
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
    private val rooms: MutableMap<EntityBareJid, ChatRoomImpl> = HashMap()

    @Throws(RoomExistsException::class)
    fun createChatRoom(roomJid: EntityBareJid, logLevel: Level? = Level.ALL): ChatRoom {
        synchronized(rooms) {
            if (rooms.containsKey(roomJid)) {
                throw RoomExistsException("Room '$roomJid' exists")
            }
            return ChatRoomImpl(xmppProvider, roomJid, logLevel) { removeRoom(it) }.also {
                rooms[roomJid] = it
            }
        }
    }

    fun findOrCreateRoom(roomJid: EntityBareJid, logLevel: Level = Level.ALL): ChatRoom {
        synchronized(rooms) {
            try {
                return rooms[roomJid] ?: createChatRoom(roomJid, logLevel)
            } catch (e: RoomExistsException) {
                throw RuntimeException("Unexpected RoomExistsException.")
            }
        }
    }

    fun removeRoom(chatRoom: ChatRoomImpl) = synchronized(rooms) {
        rooms.remove(chatRoom.roomJid)
    }
}
