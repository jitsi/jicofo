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
package org.jitsi.impl.protocol.xmpp

import org.jitsi.impl.protocol.xmpp.tmp.ChatRoom
import org.jitsi.jicofo.recording.jibri.OperationSetJibri
import org.jitsi.jicofo.xmpp.XmppConnectionConfig
import org.jitsi.protocol.xmpp.OperationSetJingle
import org.jitsi.protocol.xmpp.XmppConnection
import org.jivesoftware.smack.XMPPConnection
import org.jxmpp.stringprep.XmppStringprepException
import java.util.concurrent.ScheduledExecutorService

/**
 * Based on Jitsi's `ProtocolProviderService`, simplified for the needs of jicofo.
 */
interface XmppProvider {
    fun register(executorService: ScheduledExecutorService?)
    fun unregister()

    /**
     * @return true if the provider is currently registered and false otherwise.
     */
    val isRegistered: Boolean

    /**
     * Registers the specified listener with this provider so that it would receive notifications on changes of its
     * state.
     */
    fun addRegistrationListener(listener: RegistrationListener?)

    /**
     * Removes the specified listener.
     * @param listener the listener to remove.
     */
    fun removeRegistrationListener(listener: RegistrationListener?)

    val config: XmppConnectionConfig
    val xmppConnection: XmppConnection?
    val xmppConnectionRaw: XMPPConnection?
    val jingleApi: OperationSetJingle
    val jibriApi: OperationSetJibri?

    @Throws(RoomExistsException::class, XmppStringprepException::class)
    fun createRoom(name: String): ChatRoom

    @Throws(RoomExistsException::class, XmppStringprepException::class)
    fun findOrCreateRoom(name: String): ChatRoom

    class RoomExistsException(message: String) : Exception(message)
}