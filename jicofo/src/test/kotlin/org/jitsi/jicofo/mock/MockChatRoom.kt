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
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomListener
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.utils.OrderedJsonObject
import org.jivesoftware.smack.packet.ExtensionElement
import java.lang.IllegalArgumentException
import javax.xml.namespace.QName

class MockChatRoom(val xmppProvider: XmppProvider) {
    val chatRoomListeners = mutableListOf<ChatRoomListener>()
    val memberList = mutableListOf<ChatRoomMember>()
    val chatRoom = mockk<ChatRoom>(relaxed = true) {
        every { addListener(capture(chatRoomListeners)) } returns Unit
        every { members } returns memberList
        every { memberCount } answers { memberList.size }
        every { xmppProvider } returns this@MockChatRoom.xmppProvider
        every { debugState } returns OrderedJsonObject()
        every { getChatMember(any()) } answers { memberList.find { it.occupantJid == arg(0) } }
    }

    fun addMember(id: String): ChatRoomMember {
        val member = mockk<ChatRoomMember>(relaxed = true) {
            every { name } returns id
            every { chatRoom } returns this@MockChatRoom.chatRoom
            every { features } returns Features.defaultFeatures
            every { debugState } returns OrderedJsonObject()
            every { presence } returns mockk {
                every { status } returns null
                every { getExtension(any<String>()) } returns null
                every { getExtension(any<QName>()) } returns null
                every { getExtension(any<Class<out ExtensionElement>>()) } returns null
            }
        }
        memberList.add(member)
        chatRoomListeners.forEach { it.memberJoined(member) }
        return member
    }

    fun removeMember(member: ChatRoomMember) {
        if (!memberList.contains(member)) throw IllegalArgumentException("not a member")
        memberList.remove(member)
        chatRoomListeners.forEach { it.memberLeft(member) }
    }
}
