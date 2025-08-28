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

import org.jitsi.jicofo.MediaType
import org.jitsi.jicofo.xmpp.RoomMetadata
import org.jitsi.jicofo.xmpp.XmppProvider
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

    /** Whether the local user is currently in the multi user chat (after calling one of the [.join] methods). */
    val isJoined: Boolean

    /**
     * Returns the list of members of this [ChatRoom]. Note that this does not include a representation of the local
     * user as an occupant of the MUC.
     */
    val members: List<ChatRoomMember>

    /** The size of [members], exposed separately for performance (avoid creating a new list just to get the count) */
    val memberCount: Int

    /**
     *  The number of [members] with role VISITOR. Exposed separately for performance (avoid creating a new list
     *  just to get the count).  Also includes visitors who have joined within the last vnode-join-latency-interval,
     *  as reported by [visitorInvited]. */
    val visitorCount: Int

    /** Whether a lobby is enabled for the room. Read from the MUC config form. */
    val lobbyEnabled: Boolean

    /** Whether the visitors feature is enabled for the room. Read from the MUC config form. */
    val visitorsEnabled: Boolean?

    /** Whether the visitorsLive flag is enabled for the room. Read from the MUC config form. */
    val visitorsLive: Boolean

    /** The number of participants in the room after which new endpoints should be redirected to visitors.
     *  Read from the MUC config form. */
    val participantsSoftLimit: Int?

    val debugState: OrderedJsonObject

    /** Returns the number of members that currently have their audio sources unmuted. */
    var audioSendersCount: Int

    /** Returns the number of members that currently have their video sources unmuted. */
    var videoSendersCount: Int

    /**
     * Whether a user with a certain ID and a certain group ID would be allowed to join the main room. Note that this
     * does not actually perform authentication (i.e. if the user has a valid claim for the user ID and group ID),
     * just checks the MUC configuration.
     */
    fun isAllowedInMainRoom(userId: String?, groupId: String?): Boolean

    /**
     * Whether a user with a certain ID and a certain group ID is preferred in the main room, i.e. is explicitly listed
     * in the list of participants or moderators for the main room.
     */
    fun isPreferredInMainRoom(userId: String?, groupId: String?): Boolean

    /**
     * Joins this chat room with the preconfigured nickname. Returns the fields read from the MUC config form after
     * joining.
     */
    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    fun join(): ChatRoomInfo

    /** Leave the chat room. */
    fun leave()

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

    /** Add all of [extensions] to our presence. */
    fun addPresenceExtensions(extensions: Collection<ExtensionElement>)

    /** Add [extension] to our presence, no-op if we already have an extension with the same QName. */
    fun addPresenceExtensionIfMissing(extension: ExtensionElement)

    /** Remove presence extensions matching the predicate [pred]. */
    fun removePresenceExtensions(pred: (ExtensionElement) -> Boolean)

    /**
     * Add [extension] to presence and remove any previous extensions with the same QName.
     *
     * Note that this always sends a presence stanza. If the intention is to only send a packet if the extension was
     * modified use [addPresenceExtensionIfMissing].
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

    /** Update the value in the room_metadata structure */
    fun setRoomMetadata(roomMetadata: RoomMetadata)

    /** whether the current A/V moderation setting allow the member [jid] to unmute (for a specific [mediaType]). */
    fun isMemberAllowedToUnmute(jid: Jid, mediaType: MediaType): Boolean

    /** Re-load the MUC configuration form, updating local state if relevant fields have changed. */
    fun reloadConfiguration()

    /** Notify the chatroom that a visitor has been redirected to this room.
     */
    fun visitorInvited()

    /** Queue a task to be executed sequentially with respect to other tasks queued through this method. */
    fun queueXmppTask(runnable: () -> Unit)
}

/** Holds fields read from the MUC config form at join time, which never change. */
data class ChatRoomInfo(
    /** The meeting ID, or null if none is set. */
    val meetingId: String?,
    /** The JID of the main room if this is a breakout room, otherwise null. */
    val mainRoomJid: EntityBareJid?
)

/** Whether a user with a certain JID is allowed to unmute with any of [mediaTypes]. */
fun ChatRoom.isMemberAllowedToUnmute(jid: Jid, mediaTypes: Set<MediaType>): Boolean =
    mediaTypes.any { isMemberAllowedToUnmute(jid, it) }
