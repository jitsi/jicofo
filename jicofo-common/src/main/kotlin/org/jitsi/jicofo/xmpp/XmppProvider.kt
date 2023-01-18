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
import org.jitsi.impl.protocol.xmpp.RegistrationListener
import org.jitsi.jicofo.TaskPools.Companion.ioPool
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smackx.disco.packet.DiscoverInfo
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid

/**
 * Based on Jitsi's `ProtocolProviderService`, simplified for the needs of jicofo.
 */
abstract class XmppProvider(val config: XmppConnectionConfig, parentLogger: Logger) {
    abstract fun start()
    abstract fun shutdown()

    private val logger: Logger = createChildLogger(parentLogger)

    /** A list of all listeners registered with this instance. */
    private val registrationListeners: MutableList<RegistrationListener> = mutableListOf()

    var registered = false
        private set

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

    protected open fun fireRegistrationStateChanged(registered: Boolean) {
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
    }

    protected fun setRegistered(registered: Boolean) {
        if (this.registered != registered) {
            this.registered = registered
            fireRegistrationStateChanged(registered)
        }
    }

    abstract val xmppConnection: AbstractXMPPConnection

    @Throws(RoomExistsException::class)
    abstract fun createRoom(name: EntityBareJid): ChatRoom

    abstract fun findOrCreateRoom(name: EntityBareJid): ChatRoom

    abstract fun discoverFeatures(jid: EntityFullJid): List<String>
    abstract fun discoverInfo(jid: Jid): DiscoverInfo?

    class RoomExistsException(message: String) : Exception(message)
}
