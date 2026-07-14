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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomListener
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jivesoftware.smack.packet.ExtensionElement
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import java.lang.IllegalArgumentException
import javax.xml.namespace.QName

class MockChatRoom(
    val xmppProvider: XmppProvider,
    val roomJid: EntityBareJid = JidCreate.entityBareFrom("room@conference.example.com")
) {
    val chatRoomListeners = mutableListOf<ChatRoomListener>()
    val memberList = mutableListOf<ChatRoomMember>()

    /** Settable audio/video sender counts (the real ChatRoom derives them from member presence). */
    var audioSenders = 0
    var videoSenders = 0

    val chatRoom = mockk<ChatRoom>(relaxed = true) {
        every { addListener(capture(chatRoomListeners)) } returns Unit
        every { roomJid } returns this@MockChatRoom.roomJid
        every { members } returns memberList
        every { memberCount } answers { memberList.size }
        every { audioSendersCount } answers { audioSenders }
        every { videoSendersCount } answers { videoSenders }
        every { xmppProvider } returns this@MockChatRoom.xmppProvider
        every { debugState } returns JsonNodeFactory.instance.objectNode()
        every { getChatMember(any()) } answers { memberList.find { it.occupantJid == arg(0) } }
    }

    fun addMember(
        id: String,
        role: MemberRole = MemberRole.PARTICIPANT,
        jibri: Boolean = false,
        jigasi: Boolean = false
    ): ChatRoomMember {
        val occupant = JidCreate.entityFullFrom("$roomJid/$id")
        val member = mockk<ChatRoomMember>(relaxed = true) {
            every { name } returns id
            every { occupantJid } returns occupant
            every { chatRoom } returns this@MockChatRoom.chatRoom
            every { features } returns Features.defaultFeatures
            every { isJibri } returns jibri
            every { isJigasi } returns jigasi
            every { debugState } returns JsonNodeFactory.instance.objectNode()
            every { presence } returns mockk {
                every { status } returns null
                every { getExtension(any<String>()) } returns null
                every { getExtension(any<QName>()) } returns null
                every { getExtension(any<Class<out ExtensionElement>>()) } returns null
            }
        }
        every { member.role } returns role
        memberList.add(member)
        chatRoomListeners.forEach { it.memberJoined(member) }
        return member
    }

    /** Change the role of [member] and notify listeners, as if a presence update was received. */
    fun setRole(member: ChatRoomMember, role: MemberRole) {
        every { member.role } returns role
        chatRoomListeners.forEach { it.memberPresenceChanged(member) }
    }

    fun removeMember(member: ChatRoomMember) {
        if (!memberList.contains(member)) throw IllegalArgumentException("not a member")
        memberList.remove(member)
        chatRoomListeners.forEach { it.memberLeft(member) }
    }
}
