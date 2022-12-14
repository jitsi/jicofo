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
import org.jitsi.impl.protocol.xmpp.ChatRoom
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.discovery.DiscoveryUtil
import org.jitsi.jicofo.xmpp.muc.ChatRoomListener
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.utils.OrderedJsonObject
import java.lang.IllegalArgumentException

class MockChatRoom(val xmppProvider: XmppProvider) {
    val chatRoomListeners = mutableListOf<ChatRoomListener>()
    val memberList = mutableListOf<ChatRoomMember>()
    val chatRoom = mockk<ChatRoom>(relaxed = true) {
        every { addListener(capture(chatRoomListeners)) } returns Unit
        every { members } returns memberList
        every { membersCount } answers { memberList.size }
        every { xmppProvider } returns this@MockChatRoom.xmppProvider
        every { debugState } returns OrderedJsonObject()
    }

    fun addMember(id: String): ChatRoomMember {
        val member = mockk<ChatRoomMember>(relaxed = true) {
            every { name } returns id
            every { chatRoom } returns this@MockChatRoom.chatRoom
            every { features } returns DiscoveryUtil.getDefaultParticipantFeatureSet()
            every { debugState } returns OrderedJsonObject()
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
