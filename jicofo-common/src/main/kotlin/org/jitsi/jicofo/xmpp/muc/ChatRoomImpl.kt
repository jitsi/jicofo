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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.jicofo.JicofoConfig
import org.jitsi.jicofo.MediaType
import org.jitsi.jicofo.TaskPools.Companion.ioPool
import org.jitsi.jicofo.util.PendingCount
import org.jitsi.jicofo.xmpp.RoomMetadata
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.muc.MemberRole.Companion.fromSmack
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.observableWhenChanged
import org.jitsi.utils.queue.PacketQueue
import org.jivesoftware.smack.PresenceListener
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.PresenceBuilder
import org.jivesoftware.smack.util.Consumer
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole
import org.jivesoftware.smackx.muc.MucConfigFormManager
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.Occupant
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.muc.packet.MUCAdmin
import org.jivesoftware.smackx.muc.packet.MUCInitialPresence
import org.jivesoftware.smackx.muc.packet.MUCItem
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jivesoftware.smackx.xdata.form.Form
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level

@SuppressFBWarnings(
    value = ["JLM_JSR166_UTILCONCURRENT_MONITORENTER"],
    justification = "We intentionally synchronize on [members] (a ConcurrentHashMap)."
)
class ChatRoomImpl(
    override val xmppProvider: XmppProvider,
    override val roomJid: EntityBareJid,
    logLevel: Level?,
    /** Callback to call when the room is left. */
    private val leaveCallback: (ChatRoomImpl) -> Unit
) : ChatRoom, PresenceListener {
    private val logger = createLogger().apply {
        logLevel?.let { level = it }
        addContext("room", roomJid.toString())
    }

    /**
     * Keep track of the recently added visitors.
     */
    private val pendingVisitorsCounter = PendingCount(
        JicofoConfig.config.vnodeJoinLatencyInterval
    )

    /**
     * Latch that is counted down when the room metadata is set.
     * This is used to block the join until the room metadata is available.
     */
    private val roomMetadataLatch = CountDownLatch(1)

    /**
     * Latch that is counted down when the room is joined. Processing presence blocks until this latch is counted down.
     */
    private val roomJoinedLatch = CountDownLatch(1)

    override fun visitorInvited() {
        pendingVisitorsCounter.eventPending()
    }

    /**
     * A queue for tasks originating from XMPP for this room which need to be processed in order.
     */
    private val xmppTaskQueue = PacketQueue<Runnable>(
        Integer.MAX_VALUE,
        false,
        "ChatRoomImpl presence queue",
        {
            try {
                it.run()
            } catch (e: Exception) {
                logger.warn("Error processing XMPP task", e)
            }
            true
        },
        ioPool
    )

    private val membersMap: MutableMap<EntityFullJid, ChatRoomMemberImpl> = ConcurrentHashMap()
    override val members: List<ChatRoomMember>
        get() = synchronized(membersMap) { return membersMap.values.toList() }
    override val memberCount
        get() = membersMap.size
    private var visitorMemberCount: Int = 0
    override val visitorCount: Int
        get() = synchronized(membersMap) { visitorMemberCount } +
            pendingVisitorsCounter.getCount().toInt()

    /** Stores our last MUC presence packet for future update. */
    private var lastPresenceSent: PresenceBuilder? = null
    private val memberListener: MemberListener = MemberListener()
    private val userListener = LocalUserStatusListener()

    /** Listener for presence that smack sends on our behalf. */
    private var presenceInterceptor = Consumer<PresenceBuilder> { presenceBuilder ->
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

    /** Smack multi user chat backend instance. */
    private val muc: MultiUserChat =
        MultiUserChatManager.getInstanceFor(xmppProvider.xmppConnection).getMultiUserChat(this.roomJid).apply {
            addUserStatusListener(userListener)
            addParticipantListener(this@ChatRoomImpl)
        }

    override val isJoined
        get() = muc.isJoined && roomJoinedLatch.count <= 0

    /** Our full Multi User Chat XMPP address. */
    private var myOccupantJid: EntityFullJid? = null

    override var lobbyEnabled: Boolean = false
        private set(value) {
            if (value != field) {
                logger.info("Lobby is now ${if (value) "enabled" else "disabled"}.")
                field = value
            }
        }

    override var visitorsEnabled: Boolean? = null
        private set(value) {
            if (value != field) {
                logger.info("Visitors is now: $value")
                field = value
            }
        }
    override var visitorsLive: Boolean = false
        private set(value) {
            if (value != field) {
                logger.info("VisitorsLive is now: $value")
                field = value
            }
        }

    override var participantsSoftLimit: Int? = null
        private set(value) {
            if (value != field) {
                logger.info("ParticipantsSoftLimit is now $value.")
                field = value
            }
        }

    /**
     * List of user IDs which the room is configured to allow to be moderators.
     */
    private var moderators: List<String> = emptyList()

    /**
     * List of user IDs which the room is configured to allow to be moderators.
     * When [null], the feature is not used, meaning that the room is open to participants.
     * When empty, the feature is used and no participants are explicitly allowed (so the room requires a token,
     * and users without a token should be redirected as visitors.
     */
    private var participants: List<String>? = null

    override fun isAllowedInMainRoom(userId: String?, groupId: String?): Boolean {
        if (participants == null) {
            // The room is open to participants.
            return true
        }
        return isPreferredInMainRoom(userId, groupId)
    }

    override fun isPreferredInMainRoom(userId: String?, groupId: String?): Boolean {
        if (userId != null && (moderators.contains(userId) || participants?.contains(userId) == true)) {
            // The user is explicitly allowed to join the main room.
            return true
        }
        if (groupId != null && (moderators.contains(groupId) || participants?.contains(groupId) == true)) {
            // The user is explicitly allowed to join the main room based on their group ID.
            return true
        }
        return false
    }

    private val avModerationByMediaType = ConcurrentHashMap<MediaType, AvModerationForMediaType>()

    /** The emitter used to fire events. */
    private val eventEmitter: EventEmitter<ChatRoomListener> = SyncEventEmitter()

    /** The number of members that currently have their audio sources unmuted. */
    override var audioSendersCount by observableWhenChanged(0) { _, _, newValue ->
        logger.debug { "The number of audio senders has changed to $newValue." }
        eventEmitter.fireEvent { numAudioSendersChanged(newValue) }
    }

    /** The number of members that currently have their video sources unmuted. */
    override var videoSendersCount by observableWhenChanged(0) { _, _, newValue ->
        logger.debug { "The number of video senders has changed to $newValue." }
        eventEmitter.fireEvent { numVideoSendersChanged(newValue) }
    }

    override val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["room_jid"] = roomJid.toString()
            this["my_occupant_jid"] = myOccupantJid.toString()
            val membersJson = OrderedJsonObject()
            membersMap.values.forEach {
                membersJson[it.name] = it.debugState
            }
            this["members"] = membersJson
            this["audio_senders_count"] = audioSendersCount
            this["video_senders_count"] = videoSendersCount
            this["lobby_enabled"] = lobbyEnabled
            this["participants_soft_limit"] = participantsSoftLimit ?: -1
            participants?.let {
                this["participants"] = it
            }
            this["moderators"] = moderators
            this["visitors_enabled"] = visitorsEnabled?.toString() ?: "null"
            this["visitors_live"] = visitorsLive
            this["av_moderation"] = OrderedJsonObject().apply {
                avModerationByMediaType.forEach { (k, v) -> this[k.toString()] = v.debugState }
            }
        }

    override fun addListener(listener: ChatRoomListener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ChatRoomListener) = eventEmitter.removeHandler(listener)

    // Use toList to avoid concurrent modification. TODO: add a removeAll to EventEmitter.
    override fun removeAllListeners() = eventEmitter.eventHandlers.toList().forEach { eventEmitter.removeHandler(it) }

    private fun avModeration(mediaType: MediaType): AvModerationForMediaType =
        avModerationByMediaType.computeIfAbsent(mediaType) { AvModerationForMediaType(mediaType) }
    override fun isAvModerationEnabled(mediaType: MediaType) = avModeration(mediaType).enabled
    override fun setAvModerationEnabled(mediaType: MediaType, value: Boolean) {
        avModeration(mediaType).enabled = value
    }
    override fun setAvModerationWhitelist(mediaType: MediaType, whitelist: List<String>) {
        avModeration(mediaType).whitelist = whitelist
    }

    override fun getChatMember(occupantJid: EntityFullJid) = membersMap[occupantJid]
    override fun isMemberAllowedToUnmute(jid: Jid, mediaType: MediaType): Boolean =
        avModeration(mediaType).isAllowedToUnmute(jid)

    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    override fun join(): ChatRoomInfo {
        // TODO: clean-up the way we figure out what nickname to use.
        resetState()
        return joinAs(xmppProvider.config.username)
    }

    /**
     * Prepare this [ChatRoomImpl] for a call to [.joinAs], which sends initial presence to the MUC. Resets any state
     * that might have been set the previous time the MUC was joined.
     */
    private fun resetState() {
        synchronized(membersMap) {
            if (membersMap.isNotEmpty()) {
                logger.warn("Removing ${membersMap.size} stale members ($visitorMemberCount stale visitors).")
                membersMap.clear()
                visitorMemberCount = 0
                audioSendersCount = 0
                videoSendersCount = 0
            }
        }
        synchronized(this) {
            lastPresenceSent = null
            logger.removeContext("meeting_id")
            avModerationByMediaType.values.forEach { it.reset() }
        }
    }

    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    private fun joinAs(nickname: Resourcepart): ChatRoomInfo {
        // The queue should block until the room is fully joined.
        xmppTaskQueue.add { roomJoinedLatch.await() }

        myOccupantJid = JidCreate.entityFullFrom(roomJid, nickname)
        synchronized(muc) {
            if (muc.isJoined) {
                muc.leave()
            }
        }

        muc.addPresenceInterceptor(presenceInterceptor)
        muc.createOrJoin(nickname)
        val config = muc.configurationForm
        parseConfigForm(config)

        // Make the room non-anonymous, so that others can recognize focus JID
        val answer = config.fillableForm
        answer.setAnswer(MucConfigFields.WHOIS, "anyone")
        muc.sendConfigurationForm(answer)

        // Read the breakout room and meetingId.
        val mainRoomStr = if (config.getField(MucConfigFields.IS_BREAKOUT_ROOM)?.firstValue?.toBoolean() == true) {
            config.getField(MucConfigFields.MAIN_ROOM)?.firstValue
        } else {
            null
        }

        if (config.getField(MucConfigFields.CONFERENCE_PRESET_ENABLED)?.firstValue?.toBoolean() == true) {
            logger.info("Conference presets service is enabled. Will block until RoomMetadata is set.")
            if (roomMetadataLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("RoomMetadata is set, room is fully joined.")
            } else {
                logger.warn("Timed out waiting for RoomMetadata to be set. Will continue without it.")
            }
        }
        roomJoinedLatch.countDown()

        return ChatRoomInfo(
            meetingId = config.getField(MucConfigFields.MEETING_ID)?.firstValue,
            mainRoomJid = if (mainRoomStr == null) null else JidCreate.entityBareFrom(mainRoomStr)
        )
    }

    override fun setRoomMetadata(roomMetadata: RoomMetadata) {
        // The initial RoomMetadata is required to consider the room fully joined. The queue will block until that
        // happens, so handle it outside the queue
        if (roomJoinedLatch.count > 0L) {
            ioPool.submit { doSetRoomMetadata(roomMetadata) }
        } else {
            queueXmppTask { doSetRoomMetadata(roomMetadata) }
        }
    }

    private fun doSetRoomMetadata(roomMetadata: RoomMetadata) {
        visitorsLive = roomMetadata.metadata?.visitors?.live == true
        moderators = roomMetadata.metadata?.moderators ?: emptyList()
        participants = roomMetadata.metadata?.participants
        // We read these fields from both the config form (for backwards compatibility) and the room metadata. Only
        // override if they are set.
        roomMetadata.metadata?.visitorsEnabled?.let {
            visitorsEnabled = it
        }
        roomMetadata.metadata?.participantsSoftLimit?.let {
            participantsSoftLimit = it
        }
        roomMetadata.metadata?.startMuted?.let {
            eventEmitter.fireEvent { startMutedChanged(it.audio == true, it.video == true) }
        }
        eventEmitter.fireEvent {
            transcribingEnabledChanged(
                roomMetadata.metadata?.recording?.isTranscribingEnabled == true &&
                    roomMetadata.metadata.asyncTranscription == true
            )
        }
        roomMetadataLatch.countDown()
    }

    /** Read the fields we care about from [configForm] and update local state. */
    private fun parseConfigForm(configForm: Form) {
        lobbyEnabled =
            configForm.getField(MucConfigFormManager.MUC_ROOMCONFIG_MEMBERSONLY)?.firstValue?.toBoolean() ?: false
        // We read these fields from both the config form (for backwards compatibility) and the room metadata. Only
        // override if they are set.
        configForm.getField(MucConfigFields.VISITORS_ENABLED)?.firstValue?.let {
            visitorsEnabled = it.toBoolean()
        }
        configForm.getField(MucConfigFields.PARTICIPANTS_SOFT_LIMIT)?.firstValue?.let {
            participantsSoftLimit = it.toInt()
        }
    }

    override fun leave() {
        muc.removePresenceInterceptor(presenceInterceptor)
        muc.removeUserStatusListener(userListener)
        muc.removeParticipantListener(this)
        leaveCallback(this)

        // Unblock any threads waiting on the latches
        xmppTaskQueue.close()
        roomMetadataLatch.countDown()
        roomJoinedLatch.countDown()

        // Call MultiUserChat.leave() in an IO thread, because it now (with Smack 4.4.3) blocks waiting for a response
        // from the XMPP server (and we want ChatRoom#leave to return immediately).
        ioPool.execute {
            val connection: XMPPConnection = xmppProvider.xmppConnection
            try {
                // FIXME smack4: there used to be a custom dispose() method if leave() fails, there might still be some
                // listeners lingering around
                if (muc.isJoined) {
                    muc.leave()
                } else {
                    // If the join attempt timed out the XMPP server might have processed it and created the MUC, and
                    // since the XMPP connection is long-lived the MUC will leak.
                    val leavePresence = connection.stanzaFactory.buildPresenceStanza()
                        .ofType(Presence.Type.unavailable)
                        .to(myOccupantJid)
                        .build()
                    connection.sendStanza(leavePresence)
                }
            } catch (e: Exception) {
                // when the connection is not connected or we get NotConnectedException, this is expected (skip log)
                if (connection.isConnected || e !is SmackException.NotConnectedException) {
                    logger.error("Failed to properly leave $muc", e)
                }
            }
        }
    }

    override fun grantOwnership(member: ChatRoomMember) {
        logger.debug("Grant owner to $member")

        // Have to construct the IQ manually as Smack version used here seems
        // to be using wrong namespace(muc#owner instead of muc#admin)
        // which does not work with the Prosody.
        val jid = member.jid?.asBareJid() ?: run {
            logger.warn("Can not grant ownership to ${member.name}, real JID unknown")
            return
        }
        val item = MUCItem(MUCAffiliation.owner, jid)

        val admin = MUCAdmin().apply {
            type = IQ.Type.set
            to = roomJid
            addItem(item)
        }
        try {
            val reply = xmppProvider.xmppConnection.sendIqAndGetResponse(admin)
            if (reply == null || reply.type != IQ.Type.result) {
                logger.warn("Failed to grant ownership: ${reply?.toString() ?: "timeout"}")
            }
        } catch (e: SmackException.NotConnectedException) {
            logger.warn("Failed to grant ownership: XMPP disconnected")
        }
    }

    fun getOccupant(chatMember: ChatRoomMemberImpl): Occupant? = muc.getOccupant(chatMember.occupantJid)

    override fun setPresenceExtension(extension: ExtensionElement) {
        val presenceToSend = synchronized(this) {
            val presence = lastPresenceSent ?: run {
                logger.error("No presence packet obtained yet")
                return
            }

            presence.getExtensions(extension.qName).toList().forEach { existingExtension ->
                presence.removeExtension(existingExtension)
            }
            presence.addExtension(extension)

            presence.build()
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    override fun addPresenceExtensionIfMissing(extension: ExtensionElement) {
        val presenceToSend = synchronized(this) {
            val presence = lastPresenceSent ?: run {
                logger.error("No presence packet obtained yet")
                return
            }

            if (presence.extensions?.any { it.qName == extension.qName } == true) {
                null
            } else {
                presence.addExtension(extension).build()
            }
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    override fun removePresenceExtensions(pred: (ExtensionElement) -> Boolean) {
        val presenceToSend = synchronized(this) {
            lastPresenceSent?.extensions?.filter { pred(it) }?.let {
                modifyPresenceExtensions(toRemove = it)
            }
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    override fun addPresenceExtensions(extensions: Collection<ExtensionElement>) {
        val presenceToSend = synchronized(this) {
            modifyPresenceExtensions(toAdd = extensions)
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    /** Add/remove extensions to/from our presence and return the updated presence or null if it wasn't modified. */
    private fun modifyPresenceExtensions(
        toRemove: Collection<ExtensionElement> = emptyList(),
        toAdd: Collection<ExtensionElement> = emptyList()
    ): Presence? = synchronized(this) {
        val presence = lastPresenceSent ?: run {
            logger.error("No presence packet obtained yet")
            return null
        }
        var changed = false
        toRemove.forEach {
            presence.removeExtension(it)
            // We don't have a good way to check if it was actually removed.
            changed = true
        }
        toAdd.forEach {
            presence.addExtension(it)
            changed = true
        }
        return if (changed) presence.build() else null
    }

    /**
     * Adds a new [ChatRoomMemberImpl] with the given JID to [.members].
     * If a member with the given JID already exists, it returns the existing
     * instance.
     * @param jid the JID of the member.
     * @return the [ChatRoomMemberImpl] for the member with the given JID.
     */
    private fun addMember(jid: EntityFullJid): ChatRoomMemberImpl {
        synchronized(membersMap) {
            membersMap[jid]?.let { return it }
            val newMember = ChatRoomMemberImpl(jid, this@ChatRoomImpl, logger)
            membersMap[jid] = newMember
            if (!newMember.isAudioMuted) audioSendersCount++
            if (!newMember.isVideoMuted) videoSendersCount++
            if (newMember.role == MemberRole.VISITOR) {
                pendingVisitorsCounter.eventOccurred()
                visitorMemberCount++
            }
            return newMember
        }
    }

    /**
     * Gets the "real" JID of an occupant of this room specified by its
     * occupant JID.
     * @param occupantJid the occupant JID.
     * @return the "real" JID of the occupant, or `null`.
     */
    fun getJid(occupantJid: EntityFullJid): Jid? = muc.getOccupant(occupantJid)?.jid ?: run {
        logger.error("Unable to get occupant for $occupantJid")
        null
    }

    /**
     * Processes a <tt>Presence</tt> packet addressed to our own occupant
     * JID.
     * @param presence the packet to process.
     */
    private fun processOwnPresence(presence: Presence) {
        val mucUser = presence.getExtension(MUCUser::class.java)
        if (mucUser != null) {
            val affiliation = mucUser.item.affiliation
            val role = mucUser.item.role

            // This is our initial role and affiliation, as smack does not fire any initial events.
            if (!presence.isAvailable && MUCAffiliation.none == affiliation && MUCRole.none == role) {
                val destroy = mucUser.destroy
                if (destroy == null) {
                    // the room is unavailable to us, there is no
                    // message we will just leave
                    leave()
                } else {
                    logger.info("Leave, reason: ${destroy.reason} alt-jid: ${destroy.jid}")
                    leave()
                }
            } else {
                eventEmitter.fireEvent { localRoleChanged(fromSmack(role, affiliation)) }
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
            logger.warn("Presence without a valid jid: ${presence.from}")
            return
        }

        var memberJoined = false
        var memberLeft = false
        val member = synchronized(membersMap) {
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
            } else {
                memberLeft = presence.type == Presence.Type.unavailable
                m
            }
        }
        if (member != null) {
            member.processPresence(presence)
            if (memberJoined) {
                // Trigger member "joined"
                eventEmitter.fireEvent { memberJoined(member) }
            } else if (memberLeft) {
                if (MUCUser.from(presence).status.contains(MUCUser.Status.KICKED_307)) {
                    memberListener.kicked(jid)
                } else {
                    memberListener.left(jid)
                }
            }
            if (!memberLeft) {
                eventEmitter.fireEvent { memberPresenceChanged(member) }
            }
        }
    }

    /**
     * Offload processing presence for the room from Smack's thread to a queue running in the jicofo IO pool.
     */
    override fun processPresence(presence: Presence?) {
        if (presence == null) {
            logger.warn("Received null presence packet")
            return
        }
        xmppTaskQueue.add { doProcessPresence(presence) }
    }

    private fun doProcessPresence(presence: Presence) {
        if (presence.error != null) {
            logger.warn("Received presence with error: ${presence.toXML()}")
            return
        }
        logger.trace { "Presence received ${presence.toXML()}" }

        // Should never happen, but log if something is broken
        val myOccupantJid = this.myOccupantJid
        if (myOccupantJid == null) {
            logger.error("Processing presence when myOccupantJid is not set: ${presence.toXML()}")
        }
        if (myOccupantJid != null && myOccupantJid.equals(presence.from)) {
            processOwnPresence(presence)
        } else {
            processOtherPresence(presence)
        }
    }

    override fun reloadConfiguration() {
        if (muc.isJoined) {
            // Request the form from the MUC service.
            val config = muc.configurationForm
            parseConfigForm(config)
        }
    }

    override fun queueXmppTask(runnable: () -> Unit) = xmppTaskQueue.add(runnable)

    private object MucConfigFields {
        const val IS_BREAKOUT_ROOM = "muc#roominfo_isbreakout"
        const val MAIN_ROOM = "muc#roominfo_breakout_main_room"
        const val MEETING_ID = "muc#roominfo_meetingId"
        const val WHOIS = "muc#roomconfig_whois"
        const val PARTICIPANTS_SOFT_LIMIT = "muc#roominfo_participantsSoftLimit"
        const val VISITORS_ENABLED = "muc#roominfo_visitorsEnabled"
        const val CONFERENCE_PRESET_ENABLED = "muc#roominfo_conference_presets_service_enabled"
    }

    private inner class MemberListener {
        private fun removeMember(occupantJid: EntityFullJid?): ChatRoomMemberImpl? {
            synchronized(membersMap) {
                val removed = membersMap.remove(occupantJid)
                if (removed == null) {
                    logger.error("$occupantJid not in room")
                } else {
                    if (!removed.isAudioMuted) audioSendersCount--
                    if (!removed.isVideoMuted) videoSendersCount--
                    if (removed.role == MemberRole.VISITOR) {
                        visitorMemberCount--
                    }
                }
                return removed
            }
        }

        /** This needs to be prepared to run twice for the same member. */
        fun left(occupantJid: EntityFullJid) {
            logger.debug { "Left $occupantJid room: $roomJid" }

            val member = synchronized(membersMap) { removeMember(occupantJid) }
            member?.let { eventEmitter.fireEvent { memberLeft(it) } }
                ?: logger.info("Member left event for non-existing member: $occupantJid")
        }

        fun kicked(occupantJid: EntityFullJid) {
            val member = synchronized(membersMap) { removeMember(occupantJid) }
            member?.let { eventEmitter.fireEvent { memberKicked(it) } }
                ?: logger.error("Kicked member does not exist: $occupantJid")
        }
    }

    /**
     * Listens for room destroyed and pass it to the conference.
     */
    private inner class LocalUserStatusListener : UserStatusListener {
        override fun roomDestroyed(alternateMUC: MultiUserChat?, reason: String?) {
            eventEmitter.fireEvent { roomDestroyed(reason) }
        }
    }

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    private inner class AvModerationForMediaType(mediaType: MediaType) {
        var enabled: Boolean by observableWhenChanged(false) { _, _, newValue ->
            logger.info("Setting enabled=$newValue for $mediaType")
        }
        var whitelist: List<String> by observableWhenChanged(emptyList()) { _, _, newValue ->
            logger.info("Setting whitelist for $mediaType: $newValue")
        }

        val debugState: OrderedJsonObject
            get() = OrderedJsonObject().apply {
                this["enabled"] = enabled
                this["whitelist"] = whitelist
            }

        fun isAllowedToUnmute(jid: Jid) = !enabled || whitelist.contains(jid.toString())

        fun reset() {
            enabled = false
            whitelist = emptyList()
        }
    }
}
