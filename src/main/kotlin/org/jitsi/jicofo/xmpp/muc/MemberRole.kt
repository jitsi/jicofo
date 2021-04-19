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

import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole

/**
 * Indicates roles that a chat room member detains in its containing chat room.
 */
enum class MemberRole {
    /**
     * A role implying the full set of chat room permissions
     */
    OWNER,

    /**
     * A role implying administrative permissions.
     */
    ADMINISTRATOR,

    /**
     * A role implying moderator permissions.
     */
    MODERATOR,

    /**
     * A role which combines the standard XMPP 'member' and 'visitor' permissions, i.e. the member may or may not be
     * allowed to send messages, but they certainly don't have any moderation permissions.
     */
    GUEST;

    /**
     * Has sufficient rights to moderate (i.e. is MODERATOR, ADMINISTRATOR or OWNER).
     */
    fun hasModeratorRights() = this <= MODERATOR
    fun hasAdministratorRights() = this <= ADMINISTRATOR
    fun hasOwnerRights() = this <= OWNER

    companion object {
        @JvmStatic
        fun fromSmack(mucRole: MUCRole?, mucAffiliation: MUCAffiliation?) = when (mucAffiliation) {
            MUCAffiliation.admin -> ADMINISTRATOR
            MUCAffiliation.owner -> OWNER
            else -> when (mucRole) {
                MUCRole.moderator -> MODERATOR
                else -> GUEST
            }
        }
    }
}
