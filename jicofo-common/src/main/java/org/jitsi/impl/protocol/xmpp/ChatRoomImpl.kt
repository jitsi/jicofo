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
package org.jitsi.impl.protocol.xmpp

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.jicofo.TaskPools.Companion.ioPool
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.muc.ChatRoomAvModeration
import org.jitsi.jicofo.xmpp.muc.ChatRoomListener
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.jicofo.xmpp.muc.ChatRoomMemberImpl
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.jicofo.xmpp.muc.MemberRole.Companion.fromSmack
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jivesoftware.smack.PresenceListener
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.PresenceBuilder
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.Occupant
import org.jivesoftware.smackx.muc.ParticipantStatusListener
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.muc.packet.MUCAdmin
import org.jivesoftware.smackx.muc.packet.MUCInitialPresence
import org.jivesoftware.smackx.muc.packet.MUCItem
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.ConcurrentHashMap
import javax.xml.namespace.QName

@SuppressFBWarnings(
    value = ["JLM_JSR166_UTILCONCURRENT_MONITORENTER"],
    justification = "We intentionally synchronize on [members] (a ConcurrentHashMap)."
)
class ChatRoomImpl(
    private val xmppProvider: XmppProvider,
    private val roomJid: EntityBareJid,
    /** Callback to call when the room is left. */
    private val leaveCallback: (ChatRoomImpl) -> Unit
) : ChatRoom, PresenceListener {
    private val logger = createLogger().apply {
        addContext("room", roomJid.toString())
    }

    private val memberListener: MemberListener = MemberListener()

    private val userListener = LocalUserStatusListener()

    /** Listener for presence that smack sends on our behalf. */
    private var presenceInterceptor: org.jivesoftware.smack.util.Consumer<PresenceBuilder>? = null

    /** Smack multi user chat backend instance. */
    private val muc: MultiUserChat =
        MultiUserChatManager.getInstanceFor(xmppProvider.xmppConnection).getMultiUserChat(this.roomJid).apply {
            addParticipantStatusListener(memberListener)
            addUserStatusListener(userListener)
            addParticipantListener(this@ChatRoomImpl)
        }

    /** Our full Multi User Chat XMPP address. */
    private var myOccupantJid: EntityFullJid? = null

    private val members: MutableMap<EntityFullJid, ChatRoomMemberImpl> = ConcurrentHashMap()

    /** Local user role. */
    private var role: MemberRole? = null

    /** Stores our last MUC presence packet for future update. */
    private var lastPresenceSent: PresenceBuilder? = null

    /** The value of the "meetingId" field from the MUC form, if present. */
    private var meetingId: String? = null

    /** The value of the "isbreakout" field from the MUC form, if present. */
    private var isBreakoutRoom = false

    /** The value of "breakout_main_room" field from the MUC form, if present. */
    private var mainRoom: String? = null

    private val avModeration = ChatRoomAvModeration(logger)

    /** The emitter used to fire events. */
    private val eventEmitter: EventEmitter<ChatRoomListener> = SyncEventEmitter()

    private object MucConfigFields {
        const val IS_BREAKOUT_ROOM = "muc#roominfo_isbreakout"
        const val MAIN_ROOM = "muc#roominfo_breakout_main_room"
        const val MEETING_ID = "muc#roominfo_meetingId"
        const val WHOIS = "muc#roomconfig_whois"
    }

    /** The number of members that currently have their audio sources unmuted. */
    private var numAudioSenders = 0

    /** The number of members that currently have their video sources unmuted. */
    private var numVideoSenders = 0

    override fun getRoomJid() = roomJid
    override fun getXmppProvider() = xmppProvider
    override fun isBreakoutRoom() = isBreakoutRoom
    override fun getMainRoom() = mainRoom
    override fun getMeetingId(): String? = meetingId
    override fun addListener(listener: ChatRoomListener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ChatRoomListener) = eventEmitter.removeHandler(listener)
    override fun isJoined() = muc.isJoined
    override fun getMembers(): List<ChatRoomMember> = synchronized(members) { return members.values.toList() }
    override fun getChatMember(occupantJid: EntityFullJid) = members[occupantJid]
    override fun getMembersCount() = members.size
    override fun isAvModerationEnabled(mediaType: MediaType) = avModeration.isEnabled(mediaType)
    override fun setAvModerationEnabled(mediaType: MediaType, value: Boolean) =
        avModeration.setEnabled(mediaType, value)
    override fun setAvModerationWhitelist(mediaType: MediaType, whitelist: List<String>) =
        avModeration.setWhitelist(mediaType, whitelist)
    override fun isMemberAllowedToUnmute(jid: Jid, mediaType: MediaType): Boolean =
        avModeration.isAllowedToUnmute(mediaType, jid.toString())

    // Use toList to avoid concurrent modification. TODO: add a removeAll to EventEmitter.
    override fun removeAllListeners() = eventEmitter.eventHandlers.toList().forEach { eventEmitter.removeHandler(it) }

    fun setStartMuted(startMuted: BooleanArray) = eventEmitter.fireEvent {
        startMutedChanged(startMuted[0], startMuted[1])
    }

    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    override fun join() {
        // TODO: clean-up the way we figure out what nickname to use.
        resetState()
        joinAs(xmppProvider.config.username)
    }

    /**
     * Prepare this [ChatRoomImpl] for a call to [.joinAs], which send initial presence to
     * the MUC. Resets any state that might have been set the previous time the MUC was joined.
     */
    private fun resetState() {
        synchronized(members) {
            if (members.isNotEmpty()) {
                logger.warn("Removing " + members.size + " stale members.")
                members.clear()
            }
        }
        synchronized(this) {
            role = null
            lastPresenceSent = null
            meetingId = null
            logger.addContext("meeting_id", "")
            isBreakoutRoom = false
            mainRoom = null
            avModeration.reset()
        }
    }

    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    private fun joinAs(nickname: Resourcepart) {
        myOccupantJid = JidCreate.entityFullFrom(roomJid, nickname)
        presenceInterceptor = org.jivesoftware.smack.util.Consumer { presenceBuilder: PresenceBuilder ->
            // The initial presence sent by smack contains an empty "x"
            // extension. If this extension is included in a subsequent stanza,
            // it indicates that the client lost its synchronization and causes
            // the MUC service to re-send the presence of each occupant in the
            // room.
            synchronized(this@ChatRoomImpl) {
                val p = presenceBuilder.build()
                p.removeExtension(
                    MUCInitialPresence.ELEMENT,
                    MUCInitialPresence.NAMESPACE
                )
                lastPresenceSent = p.asBuilder()
            }
        }
        if (muc!!.isJoined) {
            muc.leave()
        }
        muc.addPresenceInterceptor(presenceInterceptor)
        muc.createOrJoin(nickname)
        val config = muc.configurationForm

        // Read breakout rooms options
        val isBreakoutRoomField = config.getField(MucConfigFields.IS_BREAKOUT_ROOM)
        if (isBreakoutRoomField != null) {
            isBreakoutRoom = java.lang.Boolean.parseBoolean(isBreakoutRoomField.firstValue)
            if (isBreakoutRoom) {
                val mainRoomField = config.getField(MucConfigFields.MAIN_ROOM)
                if (mainRoomField != null) {
                    mainRoom = mainRoomField.firstValue
                }
            }
        }

        // Read meetingId
        val meetingIdField = config.getField(MucConfigFields.MEETING_ID)
        if (meetingIdField != null) {
            meetingId = meetingIdField.firstValue
            if (meetingId != null) {
                logger.addContext("meeting_id", meetingId)
            }
        }

        // Make the room non-anonymous, so that others can recognize focus JID
        val answer = config.fillableForm
        answer.setAnswer(MucConfigFields.WHOIS, "anyone")
        muc.sendConfigurationForm(answer)
    }

    private fun leave(reason: String, jid: EntityBareJid) {
        logger.info("Leave, reason: $reason alt-jid: $jid")
        leave()
    }

    override fun leave() {
        if (presenceInterceptor != null) {
            muc.removePresenceInterceptor(presenceInterceptor)
        }
        muc.removeParticipantStatusListener(memberListener)
        muc.removeUserStatusListener(userListener)
        muc.removeParticipantListener(this)
        leaveCallback(this)

        // Call MultiUserChat.leave() in an IO thread, because it now (with Smack 4.4.3) blocks waiting for a response
        // from the XMPP server (and we want ChatRoom#leave to return immediately).
        ioPool.execute {
            val connection: XMPPConnection = xmppProvider.xmppConnection
            try {
                // FIXME smack4: there used to be a custom dispose() method if leave() fails, there might still be some
                // listeners lingering around
                if (isJoined) {
                    muc.leave()
                }
            } catch (e: Exception) {
                // when the connection is not connected or we get NotConnectedException, this is expected (skip log)
                if (connection.isConnected || e !is SmackException.NotConnectedException) {
                    logger.error("Failed to properly leave $muc", e)
                }
            }
        }
    }

    override fun getUserRole(): MemberRole? {
        if (role == null) {
            val o = muc.getOccupant(myOccupantJid)
            if (o == null) {
                return null
            } else {
                role = fromSmack(o.role, o.affiliation)
            }
        }
        return role
    }

    /** Resets cached role instance so that it will be refreshed when [ ][.getUserRole] is called. */
    private fun resetCachedUserRole() {
        role = null
    }

    /**
     * Resets cached role instance for given occupantJid.
     * @param occupantJid full mucJID of the occupant for whom we want to
     * reset the cached role instance.
     */
    private fun resetRoleForOccupant(occupantJid: EntityFullJid) {
        if (occupantJid.resourcepart == myOccupantJid!!.resourcepart) {
            resetCachedUserRole()
        } else {
            val member = members[occupantJid]
            if (member != null) {
                member.resetCachedRole()
            } else {
                logger.error("Role reset for: $occupantJid who does not exist")
            }
        }
    }

    /**
     * Sets the new role for the local user in the context of this chat room.
     */
    private fun setLocalUserRole(newRole: MemberRole) {
        val oldRole = role
        role = newRole
        if (oldRole !== newRole) {
            eventEmitter.fireEvent { localRoleChanged(newRole, role) }
        }
    }


    /**
     * {@inheritDoc}
     */
    @Synchronized
    override fun containsPresenceExtension(elementName: String, namespace: String): Boolean {
        return (lastPresenceSent != null
                && lastPresenceSent!!.getExtension(QName(namespace, elementName))
                != null)
    }

    override fun grantOwnership(member: ChatRoomMember) {
        logger.debug("Grant owner to $member")

        // Have to construct the IQ manually as Smack version used here seems
        // to be using wrong namespace(muc#owner instead of muc#admin)
        // which does not work with the Prosody.
        val admin = MUCAdmin()
        admin.type = IQ.Type.set
        admin.to = roomJid
        val item = MUCItem(MUCAffiliation.owner, member.jid!!.asBareJid())
        admin.addItem(item)
        val connection = xmppProvider.xmppConnection
        try {
            val reply = connection.sendIqAndGetResponse(admin)
            if (reply == null || reply.type != IQ.Type.result) {
                // XXX FIXME throw a declared exception.
                throw RuntimeException("Failed to grant owner: " + if (reply == null) "" else reply.toXML())
            }
        } catch (e: SmackException.NotConnectedException) {
            // XXX FIXME throw a declared exception.
            throw RuntimeException("Failed to grant owner - XMPP disconnected", e)
        }
    }

    fun getOccupant(chatMember: ChatRoomMemberImpl): Occupant? = muc.getOccupant(chatMember.occupantJid)

    /**
     * Returns the MUCUser packet extension included in the packet or
     * <tt>null</tt> if none.
     *
     * @param packet the packet that may include the MUCUser extension.
     * @return the MUCUser found in the packet.
     */
    private fun getMUCUserExtension(packet: Presence?): MUCUser? {
        return packet?.getExtension(MUCUser::class.java)
    }

    override fun setPresenceExtension(extension: ExtensionElement, remove: Boolean) {
        var presenceToSend: Presence? = null
        synchronized(this) {
            if (lastPresenceSent == null) {
                logger.error("No presence packet obtained yet")
                return
            }
            var presenceUpdated = false

            // Remove old
            val old = lastPresenceSent!!.getExtension(extension.qName)
            if (old != null) {
                lastPresenceSent!!.removeExtension(old)
                presenceUpdated = true
            }
            if (!remove) {
                // Add new
                lastPresenceSent!!.addExtension(extension)
                presenceUpdated = true
            }
            if (presenceUpdated) {
                presenceToSend = lastPresenceSent!!.build()
            }
        }
        if (presenceToSend != null) {
            sendPresence(presenceToSend!!)
        }
    }

    /**
     * {@inheritDoc}
     */
    @Synchronized
    override fun getPresenceExtensions(): Collection<ExtensionElement> {
        return if (lastPresenceSent != null) ArrayList(lastPresenceSent!!.extensions) else emptyList()
    }

    /**
     * {@inheritDoc}
     */
    override fun modifyPresence(
        toRemove: Collection<ExtensionElement>,
        toAdd: Collection<ExtensionElement>
    ) {
        var presenceToSend: Presence
        synchronized(this) {
            if (lastPresenceSent == null) {
                logger.error("No presence packet obtained yet")
                return
            }

            // Remove old
            toRemove.forEach(java.util.function.Consumer { extension: ExtensionElement? ->
                lastPresenceSent!!.removeExtension(
                    extension
                )
            })

            // Add new
            toAdd.forEach(java.util.function.Consumer { extensionElement: ExtensionElement? ->
                lastPresenceSent!!.addExtension(
                    extensionElement
                )
            })
            presenceToSend = lastPresenceSent!!.build()
        }
        sendPresence(presenceToSend)
    }

    /**
     * Sends a presence.
     */
    private fun sendPresence(presence: Presence) {
        xmppProvider.xmppConnection.tryToSendStanza(presence)
    }

    override fun getAudioSendersCount(): Int {
        return numAudioSenders
    }

    override fun getVideoSendersCount(): Int {
        return numVideoSenders
    }

    fun addAudioSender() {
        ++numAudioSenders
        logger.debug { "The number of audio senders has increased to $numAudioSenders." }
        eventEmitter.fireEvent { numAudioSendersChanged(numAudioSenders) }
    }

    fun removeAudioSender() {
        --numAudioSenders
        logger.debug { "The number of audio senders has decreased to $numAudioSenders." }
        eventEmitter.fireEvent { numAudioSendersChanged(numAudioSenders) }
    }

    fun addVideoSender() {
        ++numVideoSenders
        logger.debug { "The number of video senders has increased to $numVideoSenders." }
        eventEmitter.fireEvent { numVideoSendersChanged(numVideoSenders) }
    }

    fun removeVideoSender() {
        --numVideoSenders
        logger.debug { "The number of video senders has decreased to $numVideoSenders." }
        eventEmitter.fireEvent { numVideoSendersChanged(numVideoSenders) }
    }

    /**
     * Adds a new [ChatRoomMemberImpl] with the given JID to [.members].
     * If a member with the given JID already exists, it returns the existing
     * instance.
     * @param jid the JID of the member.
     * @return the [ChatRoomMemberImpl] for the member with the given JID.
     */
    private fun addMember(jid: EntityFullJid): ChatRoomMemberImpl? {
        synchronized(members) {
            if (members.containsKey(jid)) {
                return members[jid]
            }
            val newMember = ChatRoomMemberImpl(jid, this@ChatRoomImpl, logger)
            members[jid] = newMember
            if (!newMember.isAudioMuted) addAudioSender()
            if (!newMember.isVideoMuted) addVideoSender()
            return newMember
        }
    }

    /**
     * Gets the "real" JID of an occupant of this room specified by its
     * occupant JID.
     * @param occupantJid the occupant JID.
     * @return the "real" JID of the occupant, or `null`.
     */
    fun getJid(occupantJid: EntityFullJid): Jid? {
        val occupant = muc.getOccupant(occupantJid)
        if (occupant == null) {
            logger.error("Unable to get occupant for $occupantJid")
            return null
        }
        return occupant.jid
    }

    /**
     * Processes a <tt>Presence</tt> packet addressed to our own occupant
     * JID.
     * @param presence the packet to process.
     */
    private fun processOwnPresence(presence: Presence) {
        val mucUser = getMUCUserExtension(presence)
        if (mucUser != null) {
            val affiliation = mucUser.item.affiliation
            val role = mucUser.item.role

            // this is the presence for our member initial role and
            // affiliation, as smack do not fire any initial
            // events lets check it and fire events
            val jitsiRole = fromSmack(role, affiliation)
            if (!presence.isAvailable && MUCAffiliation.none == affiliation && MUCRole.none == role) {
                val destroy = mucUser.destroy
                if (destroy == null) {
                    // the room is unavailable to us, there is no
                    // message we will just leave
                    leave()
                } else {
                    leave(destroy.reason, destroy.jid)
                }
            } else {
                setLocalUserRole(jitsiRole)
            }
        }
    }

    /**
     * Process a <tt>Presence</tt> packet sent by one of the other room
     * occupants.
     * @param presence the presence.
     */
    private fun processOtherPresence(presence: Presence) {
        val jid = presence.from.asEntityFullJidIfPossible() ?: run {
            logger.warn("Presence without a valid jid: " + presence.from)
            return
        }

        var memberJoined = false
        var memberLeft = false
        val member: ChatRoomMemberImpl? = synchronized(members) {
            val m = getChatMember(jid)
            if (m == null) {
                if (presence.type == Presence.Type.available) {
                    // This is how we detect that a new member has joined. We do not use the
                    // ParticipantStatusListener#joined callback.
                    logger.debug { "Joined $jid room: $roomJid" }
                    memberJoined = true
                    addMember(jid)
                } else {
                    // We received presence from an unknown member which doesn't look like a new member's presence.
                    // Ignore it. The member might have been just removed via left(), which is fine.
                    null
                }
            } else if (presence.type == Presence.Type.unavailable) {
                memberLeft = true
                m
            } else {
                null
            }
        }
        if (member != null) {
            member.processPresence(presence)
            if (memberJoined) {
                // Trigger member "joined"
                eventEmitter.fireEvent { memberJoined(member) }
            } else if (memberLeft) {
                // In some cases smack fails to call left(). We'll call it here
                // any time we receive presence unavailable
                memberListener.left(jid)
            }
            if (!memberLeft) {
                eventEmitter.fireEvent { memberPresenceChanged(member) }
            }
        }
    }

    /**
     * Processes an incoming presence packet.
     *
     * @param presence the incoming presence.
     */
    override fun processPresence(presence: Presence?) {
        if (presence == null || presence.error != null) {
            logger.warn("Unable to handle packet: " + if (presence == null) "null" else presence.toXML())
            return
        }
        if (logger.isTraceEnabled) {
            logger.trace("Presence received " + presence.toXML())
        }

        // Should never happen, but log if something is broken
        if (myOccupantJid == null) {
            logger.error("Processing presence when we're not aware of our address")
        }
        if (myOccupantJid != null && myOccupantJid!!.equals(presence.from)) {
            processOwnPresence(presence)
        } else {
            processOtherPresence(presence)
        }
    }

    override fun getDebugState(): OrderedJsonObject {
        val o = OrderedJsonObject()
        o["room_jid"] = roomJid.toString()
        o["my_occupant_jid"] = myOccupantJid.toString()
        val membersJson = OrderedJsonObject()
        for (m in members.values) {
            membersJson[m.name] = m.debugState
        }
        o["members"] = membersJson
        o["role"] = role.toString()
        o["meeting_id"] = meetingId.toString()
        o["is_breakout_room"] = isBreakoutRoom
        o["main_room"] = mainRoom.toString()
        o["num_audio_senders"] = numAudioSenders
        o["num_video_senders"] = numVideoSenders
        return o
    }

    internal inner class MemberListener : ParticipantStatusListener {
        override fun joined(mucJid: EntityFullJid) {
            // When a new member joins, Smack seems to fire
            // ParticipantStatusListener#joined and
            // PresenceListener#processPresence in a non-deterministic order.

            // In order to ensure that we have all the information contained
            // in presence at the time that we create a new ChatMemberImpl,
            // we completely ignore this joined event. Instead, we rely on
            // processPresence to detect when a new member has joined and
            // trigger the creation of a ChatMemberImpl by calling
            // ChatRoomImpl#memberJoined()
            if (logger.isDebugEnabled) {
                logger.debug("Ignore a member joined event for $mucJid")
            }
        }

        private fun removeMember(occupantJid: EntityFullJid): ChatRoomMemberImpl? {
            synchronized(members) {
                val removed = members.remove(occupantJid)
                if (removed == null) {
                    logger.error("$occupantJid not in $roomJid")
                } else {
                    if (!removed.isAudioMuted) removeAudioSender()
                    if (!removed.isVideoMuted) removeVideoSender()
                }
                return removed
            }
        }

        /**
         * This needs to be prepared to run twice for the same member.
         */
        override fun left(occupantJid: EntityFullJid) {
            logger.debug { "Left $occupantJid room: $roomJid" }

            val member = synchronized(members) { removeMember(occupantJid) }
            member?.let { eventEmitter.fireEvent { memberLeft(it) } }
                ?: logger.info("Member left event for non-existing member: $occupantJid")
        }

        override fun kicked(occupantJid: EntityFullJid, actor: Jid, reason: String) {
            logger.debug { "Kicked: $occupantJid, $actor, $reason" }

            val member = synchronized(members) { removeMember(occupantJid) }
            member?.let { eventEmitter.fireEvent { memberKicked(it) } }
                ?: logger.error("Kicked member does not exist: $occupantJid")
        }

        override fun voiceGranted(s: EntityFullJid) {
            logger.trace { "Voice granted: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun voiceRevoked(s: EntityFullJid) {
            logger.trace { "Voice revoked: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun banned(s: EntityFullJid, actor: Jid, reason: String) {
            logger.trace { "Banned: $s, $actor, $reason" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun membershipGranted(s: EntityFullJid) {
            logger.trace { "Membership granted: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun membershipRevoked(s: EntityFullJid) {
            logger.trace { "Membership revoked: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun moderatorGranted(s: EntityFullJid) {
            logger.trace { "Moderator granted: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun moderatorRevoked(s: EntityFullJid) {
            logger.trace { "Moderator revoked: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun ownershipGranted(s: EntityFullJid) {
            logger.trace { "Ownership granted: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun ownershipRevoked(s: EntityFullJid) {
            logger.trace { "Ownership revoked: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun adminGranted(s: EntityFullJid) {
            logger.trace { "Admin granted: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun adminRevoked(s: EntityFullJid) {
            logger.trace { "Admin revoked: $s" }

            // We do not fire events - not required for now
            resetRoleForOccupant(s)
        }

        override fun nicknameChanged(oldNickname: EntityFullJid, newNickname: Resourcepart) {
            logger.error("nicknameChanged - NOT IMPLEMENTED")
        }
    }

    /**
     * Listens for room destroyed and pass it to the conference.
     */
    internal inner class LocalUserStatusListener : UserStatusListener {
        override fun roomDestroyed(alternateMUC: MultiUserChat, reason: String) {
            eventEmitter.fireEvent { roomDestroyed(reason) }
        }
    }
}