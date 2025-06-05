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
package org.jitsi.jicofo.xmpp.muc

/** Listener for events fired from a [org.jitsi.impl.protocol.xmpp.ChatRoom] **/
interface ChatRoomListener {
    fun memberJoined(member: ChatRoomMember) {}
    fun memberKicked(member: ChatRoomMember) {}
    fun memberLeft(member: ChatRoomMember) {}
    fun memberPresenceChanged(member: ChatRoomMember) {}

    fun roomDestroyed(reason: String? = null) {}
    fun startMutedChanged(startAudioMuted: Boolean, startVideoMuted: Boolean) {}
    fun localRoleChanged(newRole: MemberRole) {}
    fun numAudioSendersChanged(numAudioSenders: Int) {}
    fun numVideoSendersChanged(numVideoSenders: Int) {}
    fun transcribingEnabledChanged(enabled: Boolean) {}
}

/** A class with the default kotlin method implementations (to avoid using @JvmDefault) **/
open class DefaultChatRoomListener : ChatRoomListener
