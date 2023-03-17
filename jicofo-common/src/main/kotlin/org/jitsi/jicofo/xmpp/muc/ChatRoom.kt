/*
 * Jicofo, the Jitsi Conference Focus.
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

import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid

/** Wraps a Smack [MultiUserChat] for the purposes of jicofo. */
interface ChatRoom {
    val xmppProvider: XmppProvider

    /** The JID of the chat room. */
    val roomJid: EntityBareJid

    /**
     * Joins this chat room with the nickname of the local user so that the
     * user would start receiving events and messages for it.
     */
    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    fun join()

    /** Leave the chat room. */
    fun leave()

    /** Whether the local user is currently in the multi user chat (after calling one of the [.join] methods). */
    val isJoined: Boolean

    /** The local user's role in the chat room or <tt>null</tt> if not currently joined. */
    val userRole: MemberRole?

    /**
     * Returns the list of members of this [ChatRoom]. Note that this does not include a representation of the local
     * user as an occupant of the MUC.
     */
    val members: List<ChatRoomMember>

    /** Returns the number of members that currently have their audio sources unmuted. */
    var audioSendersCount: Int

    /** Returns the number of members that currently have their video sources unmuted. */
    var videoSendersCount: Int

    /**
     * Grants ownership privileges to another user. Room owners may grant
     * ownership privileges. Some room implementations will not allow to grant
     * ownership privileges to other users. An owner is allowed to change
     * defining room features as well as perform all administrative functions.
     *
     * @param member the member to grant ownership to.
     */
    fun grantOwnership(member: ChatRoomMember)

    /**
     * Get the [ChatRoomMember] for a given MUC occupant jid.
     *
     * @param occupantJid full MUC jid of the user for whom we want to find chat
     * member instance. Ex. chatroom1@muc.server.com/nick1234
     */
    fun getChatMember(occupantJid: EntityFullJid): ChatRoomMember?

    /** @return the list of all our presence [ExtensionElement]s. */
    val presenceExtensions: Collection<ExtensionElement>

    /**
     * Modifies our current MUC presence by adding and/or removing specified extensions. The extensions are compared by
     * instance equality.
     * The goal of this API is to provide a way to add and remove extensions without sending intermediate presence
     * stanzas.
     * @param toRemove the list of extensions to be removed.
     * @param toAdd the list of extension to be added.
     */
    fun modifyPresenceExtensions(toRemove: Collection<ExtensionElement>, toAdd: Collection<ExtensionElement>)
    fun addPresenceExtensions(extensions: Collection<ExtensionElement>) =
        modifyPresenceExtensions(toRemove = emptyList(), toAdd = extensions)
    fun removePresenceExtensions(extensions: Collection<ExtensionElement>) =
        modifyPresenceExtensions(toRemove = extensions, toAdd = emptyList())

    /**
     * Add [extension] to presence and remove any previous extensions with the same QName.
     *
     * Note that this always sends a presence stanza. If the intention is to only send a packet if the extension was
     * modified use [presenceExtensions] and [modifyPresenceExtensions].
     */
    fun setPresenceExtension(extension: ExtensionElement)

    /** Add a [ChatRoomListener] to the list of listeners to be notified of events from this [ChatRoom]. */
    fun addListener(listener: ChatRoomListener)

    /** Removes a [ChatRoomListener] from the list of listeners to be notified of events from this [ChatRoom]. */
    fun removeListener(listener: ChatRoomListener)

    /** Removes all [ChatRoomListener]s. */
    fun removeAllListeners()

    /** Whether A/V moderation is enabled for [mediaType] (audio or video). */
    fun isAvModerationEnabled(mediaType: MediaType): Boolean

    /** Enable or disable a/v moderation for [mediaType]. */
    fun setAvModerationEnabled(mediaType: MediaType, value: Boolean)

    /** Updates the list of members that are allowed to unmute audio or video. */
    fun setAvModerationWhitelist(mediaType: MediaType, whitelist: List<String>)

    /** whether the current A/V moderation setting allow the member [jid] to unmute (for a specific [mediaType]). */
    fun isMemberAllowedToUnmute(jid: Jid, mediaType: MediaType): Boolean

    /** Whether this [ChatRoom] is a breakout room. */
    val isBreakoutRoom: Boolean

    /** The JID of the main room associated with this [ChatRoom], if this [ChatRoom] is a breakout room (else null) */
    val mainRoom: String?

    /** Get the unique meeting ID associated by this room (set by the MUC service). */
    val meetingId: String?

    val debugState: OrderedJsonObject
}
