/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
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
package org.jitsi.impl.protocol.xmpp

sealed class ChatRoomMemberPresenceChangeEvent constructor(
    /**
     * The chat room member that the event relates to.
     */
    val chatRoomMember: ChatRoomMember
) {
    override fun toString(): String {
        return javaClass.simpleName + " member=" + chatRoomMember
    }

    class Joined(chatRoomMember: ChatRoomMember) : ChatRoomMemberPresenceChangeEvent(chatRoomMember)
    class Left(chatRoomMember: ChatRoomMember) : ChatRoomMemberPresenceChangeEvent(chatRoomMember)
    class Kicked(chatRoomMember: ChatRoomMember) : ChatRoomMemberPresenceChangeEvent(chatRoomMember)
    // The state didn't changed, but the presence extension itself was updated.
    class PresenceUpdated(chatRoomMember: ChatRoomMember) : ChatRoomMemberPresenceChangeEvent(chatRoomMember)
}

/**
 * A listener that will be notified of changes in the presence of a member in a particular chat room. Changes may
 * include member being kicked, join, left.
 *
 * @author Emil Ivov
 */
interface ChatRoomMemberPresenceListener {
    /**
     * Called to notify interested parties that a change in the presence of a
     * member in a particular chat room has occurred. Changes may include member
     * being kicked, join, left.
     *
     * @param evt the <tt>ChatRoomMemberPresenceChangeEvent</tt> instance
     * containing the source chat room and type, and reason of the presence
     * change
     */
    fun memberPresenceChanged(evt: ChatRoomMemberPresenceChangeEvent)
}
