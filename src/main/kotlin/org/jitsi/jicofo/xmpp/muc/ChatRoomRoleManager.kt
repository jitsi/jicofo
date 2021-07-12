/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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

import org.jitsi.impl.protocol.xmpp.ChatRoom
import org.jitsi.impl.protocol.xmpp.ChatRoomMember
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.auth.AuthenticationListener
import org.jitsi.utils.logging2.createLogger

/**
 * Manages to XMPP roles of occupants in a chat room, i.e. grants ownership to certain users.
 */
sealed class ChatRoomRoleManager(
    protected val chatRoom: ChatRoom
) : ChatRoomListener {
    protected val logger = createLogger()

    /** Grants ownership to [member], blocks for a response from the MUC service. */
    protected fun grantOwner(member: ChatRoomMember): Boolean {
        if (!chatRoom.userRole.hasOwnerRights()) {
            logger.warn("Can not grant owner, lacking privileges.")
            return false
        }

        return try {
            chatRoom.grantOwnership(member.jid.toString())
            true
        } catch (e: RuntimeException) {
            logger.error("Failed to grant owner status to ${member.jid}", e)
            false
        }
    }

    override fun memberLeft(member: ChatRoomMember) = memberLeftOrKicked(member)
    override fun memberKicked(member: ChatRoomMember) = memberLeftOrKicked(member)

    protected open fun memberLeftOrKicked(member: ChatRoomMember) {}

    open fun stop() {}
}

/**
 * A [ChatRoomRoleManager] which grants ownership to a single occupant in the room (if the room is left without an
 * owner, it elects a new one).
 */
class AutoOwnerRoleManager(chatRoom: ChatRoom) : ChatRoomRoleManager(chatRoom) {
    private var owner: ChatRoomMember? = null

    override fun memberJoined(member: ChatRoomMember) {
        if (owner == null) { electNewOwner() }
    }

    override fun memberLeftOrKicked(member: ChatRoomMember) {
        if (member == owner) {
            logger.debug("The owner left the room, electing a new one.")
            owner = null
            electNewOwner()
        }
    }

    override fun localRoleChanged(newRole: MemberRole, oldRole: MemberRole?) {
        if (!newRole.hasOwnerRights()) {
            logger.error("Local role has no owner rights, can not manage roles.")
            return
        }

        electNewOwner()
    }

    private fun electNewOwner() {
        if (owner != null) {
            return
        }

        owner = chatRoom.members.find { !it.isRobot && it.role.hasOwnerRights() }
        if (owner != null) {
            return
        }

        val newOwner = chatRoom.members.find { !it.isRobot }
        if (newOwner != null) {
            logger.info("Electing new owner: $newOwner")
            grantOwner(newOwner)
            owner = newOwner
        }
    }
}

/** A [ChatRoomRoleManager] which grants ownership to all authenticated users. */
class AuthenticationRoleManager(
    chatRoom: ChatRoom,
    /** Used to check whether a user is authenticated, and subscribe to authentication events. */
    private val authenticationAuthority: AuthenticationAuthority
) : ChatRoomRoleManager(chatRoom) {

    private val authenticationListener = AuthenticationListener { userJid, _, _ ->
        chatRoom.members.find { it.jid == userJid }?.let { grantOwner(it) }
    }

    init {
        authenticationAuthority.addAuthenticationListener(authenticationListener)
    }

    private fun grantOwnerToAuthenticatedUsers() {
        chatRoom.members.filter {
            !it.role.hasOwnerRights() && authenticationAuthority.getSessionForJid(it.jid) != null
        }.forEach {
            grantOwner(it)
        }
    }

    override fun localRoleChanged(newRole: MemberRole, oldRole: MemberRole?) {
        if (!newRole.hasOwnerRights()) {
            logger.error("Local role has no owner rights, can not manage roles.")
            return
        }

        grantOwnerToAuthenticatedUsers()
    }

    /**
     * Handles cases where moderators(already authenticated users) reload and join again.
     */
    override fun memberJoined(member: ChatRoomMember) {
        if (member.role != MemberRole.OWNER && authenticationAuthority.getSessionForJid(member.jid) != null) {
            grantOwner(member)
        }
    }

    override fun stop() = authenticationAuthority.removeAuthenticationListener(authenticationListener)
}
