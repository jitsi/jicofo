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

import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.auth.AuthenticationListener
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.PacketQueue

/**
 * Manages the XMPP roles of occupants in a chat room, i.e. grants ownership to certain users.
 */
sealed class ChatRoomRoleManager(
    protected val chatRoom: ChatRoom
) : ChatRoomListener {
    protected val logger = createLogger()

    override fun memberLeft(member: ChatRoomMember) = memberLeftOrKicked(member)
    override fun memberKicked(member: ChatRoomMember) = memberLeftOrKicked(member)

    protected open fun memberLeftOrKicked(member: ChatRoomMember) {}

    open fun stop() {}
    open fun grantOwnership() {}

    open val debugState: OrderedJsonObject = OrderedJsonObject()

    protected val queue = PacketQueue<Runnable>(
        Integer.MAX_VALUE,
        false,
        "chat-room-role-manager-queue",
        {
            it.run()
            return@PacketQueue true
        },
        TaskPools.ioPool
    )
}

/**
 * A [ChatRoomRoleManager] which grants ownership to a single occupant in the room (if the room is left without an
 * owner, it elects a new one).
 */
class AutoOwnerRoleManager(chatRoom: ChatRoom) : ChatRoomRoleManager(chatRoom) {
    private var owner: ChatRoomMember? = null

    override fun grantOwnership() = queue.add { electNewOwner() }
    override fun memberJoined(member: ChatRoomMember) {
        if (owner == null) {
            electNewOwner()
        }
    }

    override fun memberLeftOrKicked(member: ChatRoomMember) {
        if (member == owner) {
            logger.debug("The owner left the room, electing a new one.")
            owner = null
            electNewOwner()
        }
    }

    override fun localRoleChanged(newRole: MemberRole) {
        if (!newRole.hasOwnerRights()) {
            logger.error("Local role has no owner rights, can not manage roles.")
            return
        }

        electNewOwner()
    }

    private fun electNewOwner() {
        queue.add {
            if (owner != null) {
                return@add
            }

            owner = chatRoom.members.find { !(it.isJibri || it.isJigasi) && it.role.hasOwnerRights() }
            if (owner != null) {
                return@add
            }

            val newOwner = chatRoom.members.find { !(it.isJibri || it.isJigasi) && it.role != MemberRole.VISITOR }
            if (newOwner != null) {
                logger.info("Electing new owner: $newOwner")
                chatRoom.grantOwnership(newOwner)
                owner = newOwner
            }
        }
    }

    override val debugState
        get() = OrderedJsonObject().apply {
            put("class", this@AutoOwnerRoleManager.javaClass.simpleName)
            put("owner", owner?.jid?.toString() ?: "null")
        }
}

/** A [ChatRoomRoleManager] which grants ownership to all authenticated users. */
class AuthenticationRoleManager(
    chatRoom: ChatRoom,
    /** Used to check whether a user is authenticated, and subscribe to authentication events. */
    private val authenticationAuthority: AuthenticationAuthority
) : ChatRoomRoleManager(chatRoom) {

    private val authenticationListener = AuthenticationListener { userJid, _, _ ->
        chatRoom.members.find { it.jid == userJid }?.let {
            queue.add {
                logger.info("Granting ownership to $it.")
                chatRoom.grantOwnership(it)
            }
        }
    }

    init {
        authenticationAuthority.addAuthenticationListener(authenticationListener)
    }

    override fun grantOwnership() = queue.add { grantOwnerToAuthenticatedUsers() }

    private fun grantOwnerToAuthenticatedUsers() {
        chatRoom.members.filter {
            !it.role.hasOwnerRights() && authenticationAuthority.getSessionForJid(it.jid) != null
        }.forEach {
            chatRoom.grantOwnership(it)
        }
    }

    override fun localRoleChanged(newRole: MemberRole) {
        if (!newRole.hasOwnerRights()) {
            logger.error("Local role has no owner rights, can not manage roles.")
            return
        }

        queue.add { grantOwnerToAuthenticatedUsers() }
    }

    /**
     * Handles cases where moderators(already authenticated users) reload and join again.
     */
    override fun memberJoined(member: ChatRoomMember) {
        if (member.role != MemberRole.OWNER && authenticationAuthority.getSessionForJid(member.jid) != null) {
            queue.add {
                chatRoom.grantOwnership(member)
            }
        }
    }

    override fun stop() = authenticationAuthority.removeAuthenticationListener(authenticationListener)

    override val debugState = OrderedJsonObject().apply {
        put("class", this@AuthenticationRoleManager.javaClass.simpleName)
    }
}
