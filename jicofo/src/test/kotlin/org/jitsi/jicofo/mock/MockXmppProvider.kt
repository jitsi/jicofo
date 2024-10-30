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
import org.jxmpp.jid.EntityBareJid

class MockXmppProvider(val xmppConnection: AbstractXMPPConnection = MockXmppConnection().xmppConnection) {
    val chatRooms = mutableMapOf<EntityBareJid, MockChatRoom>()
    val xmppProvider = mockk<XmppProvider>(relaxed = true) {
        every { registered } returns true
        every { findOrCreateRoom(any(), any()) } answers { getRoom(arg(0)).chatRoom }
        every { xmppConnection } returns this@MockXmppProvider.xmppConnection
    }

    fun getRoom(jid: EntityBareJid): MockChatRoom = chatRooms.computeIfAbsent(jid) { MockChatRoom(this.xmppProvider) }
}
