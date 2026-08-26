/*
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.jicofo.mock

import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionListener
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate

class MockXmppProvider(
    val xmppConnection: AbstractXMPPConnection = MockXmppConnection().xmppConnection,
    /** The name of the connection, as it appears in [XmppProvider.getConfig]. */
    val name: String = "mock",
    /** The XMPP domain of the connection, needed to map a main room JID to a visitor room JID. */
    xmppDomain: String? = null
) {
    val chatRooms = mutableMapOf<EntityBareJid, MockChatRoom>()

    /** Settable registration state, to simulate the XMPP connection going down and coming back up. */
    var registered = true

    val xmppProvider = mockk<XmppProvider>(relaxed = true) {
        every { registered } answers { this@MockXmppProvider.registered }
        every { findOrCreateRoom(any(), any()) } answers { getRoom(arg(0)).chatRoom }
        every { xmppConnection } returns this@MockXmppProvider.xmppConnection
        every { config } returns mockk(relaxed = true) {
            every { this@mockk.name } returns this@MockXmppProvider.name
            every { this@mockk.xmppDomain } returns xmppDomain?.let { JidCreate.domainBareFrom(it) }
        }
    }

    /**
     * The Smack connection listeners that were registered on [xmppConnection]. Note that when instances share an
     * [xmppConnection] only the instance that was created last captures the listeners.
     */
    val connectionListeners = mutableListOf<ConnectionListener>()

    init {
        every { xmppConnection.addConnectionListener(capture(connectionListeners)) } returns Unit
        every { xmppConnection.removeConnectionListener(any()) } answers {
            connectionListeners.remove(arg(0))
            Unit
        }
    }

    /** Simulate the connection authenticating, i.e. coming up or coming back up after a disconnect. */
    fun authenticated(resumed: Boolean) {
        registered = true
        connectionListeners.toList().forEach { it.authenticated(xmppConnection, resumed) }
    }

    fun getRoom(jid: EntityBareJid): MockChatRoom =
        chatRooms.computeIfAbsent(jid) { MockChatRoom(this.xmppProvider, jid) }
}
