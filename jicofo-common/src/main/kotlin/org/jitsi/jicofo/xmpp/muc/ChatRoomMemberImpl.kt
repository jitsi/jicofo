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

import org.jitsi.impl.protocol.xmpp.ChatRoomImpl
import org.jitsi.jicofo.xmpp.XmppCapsStats
import org.jitsi.jicofo.xmpp.XmppConfig
import org.jitsi.jicofo.xmpp.muc.MemberRole.Companion.fromSmack
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.jitsimeet.AudioMutedExtension
import org.jitsi.xmpp.extensions.jitsimeet.FeaturesExtension
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantRegionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.StartMutedPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.StatsId
import org.jitsi.xmpp.extensions.jitsimeet.UserInfoPacketExt
import org.jitsi.xmpp.extensions.jitsimeet.VideoMutedExtension
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smackx.caps.packet.CapsExtension
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid

/**
 * Stripped Smack implementation of [ChatRoomMember].
 *
 * @author Pawel Domas
 */
class ChatRoomMemberImpl(
    /** The full MUC JID (room_name@muc.server.net/nickname) */
    override val occupantJid: EntityFullJid,
    override val chatRoom: ChatRoomImpl,
    parentLogger: Logger
) : ChatRoomMember {
    override val name: String = occupantJid.resourceOrThrow.toString()

    private val logger: Logger = createChildLogger(parentLogger).apply {
        addContext("id", name)
    }

    override var region: String? = null
        private set
    override var presence: Presence? = null
        private set
    override var isJigasi = false
        private set
    override var isJibri = false
        private set
    override var statsId: String? = null
        private set
    override var isAudioMuted = true
        private set
    override var isVideoMuted = true
        private set
    override var sourceInfos = emptySet<SourceInfo>()
        private set

    /** The node#ver advertised in a Caps extension. */
    private var capsNodeVer: String? = null

    override var role: MemberRole? = null
        private set
        get() {
            if (field == null) {
                val o = chatRoom.getOccupant(this)
                if (o == null) {
                    return MemberRole.VISITOR // ??
                } else {
                    field = fromSmack(o.role, o.affiliation)
                }
            }
            return field
        }

    /**
     * Reset cached user role so that it will be refreshed when [.getRole] is called.
     */
    fun resetCachedRole() { role = null }

    /**
     * Caches user JID of the participant if we're able to see it.
     */
    override var jid: Jid? = null
        get() {
            if (field == null) {
                field = chatRoom.getJid(occupantJid)
            }
            return field
        }
        private set

    private var robot = false
    override val isRobot: Boolean
        get() {
            // Jigasi and Jibri do not use the "robot" signaling, but semantically they should be considered "robots".
            return robot || isJigasi || isJibri
        }

    private fun setSourceInfo(sourceInfoString: String) {
        val sourceInfos: Set<SourceInfo> = try {
            parseSourceInfoJson(sourceInfoString)
        } catch (e: Exception) {
            logger.warn("Ignoring invalid SourceInfo JSON", e)
            return
        }
        this.sourceInfos = sourceInfos
    }

    /**
     * Process a new presence published by the member and update the mutable fields.
     *
     * @param presence the <tt>Presence</tt> which was sent by this chat member.
     * @throws IllegalArgumentException if [presence] does not
     * belong to this <tt>ChatMemberImpl</tt>.
     */
    fun processPresence(presence: Presence) {
        require(presence.from == occupantJid) { "Ignoring presence for a different member: ${presence.from}" }

        this.presence = presence
        presence.getExtension(UserInfoPacketExt::class.java)?.let {
            val newStatus = it.isRobot
            if (newStatus != null && robot != newStatus) {
                logger.debug { "robot: $robot" }
                robot = newStatus
            }
        }

        presence.getExtension(CapsExtension::class.java)?.let {
            capsNodeVer = "${it.node}#${it.ver}"
        }

        val sourceInfo = presence.getExtension<StandardExtensionElement>("SourceInfo", "jabber:client")
        setSourceInfo(if (sourceInfo == null) "{}" else sourceInfo.text)

        // We recognize jigasi by the existence of a "feature" extension in its presence.
        val features = presence.getExtension(FeaturesExtension::class.java)
        if (features != null) {
            isJigasi = features.featureExtensions.any { it.`var` == "http://jitsi.org/protocol/jigasi" }
            if (features.featureExtensions.any { it.`var` == "http://jitsi.org/protocol/jibri" }) {
                isJibri = isJidTrusted().also {
                    if (!it) {
                        val domain = jid?.asDomainBareJid()
                        logger.warn(
                            "Jibri signaled from a non-trusted domain ($domain). The domain can be " +
                                "configured as trusted with the jicofo.xmpp.trusted-domains property."
                        )
                    }
                }
            }
        } else {
            isJigasi = false
            isJibri = false
        }

        presence.getExtension(JitsiParticipantRegionPacketExtension::class.java)?.let {
            region = it.regionId
        }

        presence.getExtension(StartMutedPacketExtension::class.java)?.let {
            val startMuted = booleanArrayOf(it.audioMuted, it.videoMuted)

            // XXX Is this intended to be allowed for moderators or not?
            if (role.hasAdministratorRights()) {
                chatRoom.setStartMuted(startMuted)
            }
        }

        presence.getExtension(StatsId::class.java)?.let {
            statsId = it.statsId
        }

        val wasAudioMuted = isAudioMuted
        // defaults to true
        isAudioMuted = presence.getExtension(AudioMutedExtension::class.java)?.isAudioMuted ?: true
        if (isAudioMuted != wasAudioMuted) {
            logger.debug { "isAudioMuted = $isAudioMuted" }
            if (isAudioMuted) chatRoom.removeAudioSender() else chatRoom.addAudioSender()
        }

        val wasVideoMuted = isVideoMuted
        // defaults to true
        isVideoMuted = presence.getExtension(VideoMutedExtension::class.java)?.isVideoMuted ?: true
        if (isVideoMuted != wasVideoMuted) {
            logger.debug { "isVideoMuted = $isVideoMuted" }
            if (isVideoMuted) chatRoom.removeVideoSender() else chatRoom.addVideoSender()
        }
    }

    /**
     * Whether this member is a trusted entity (logged in to one of the pre-configured trusted domains).
     */
    private fun isJidTrusted() = jid?.let { XmppConfig.config.trustedDomains.contains(it.asDomainBareJid()) } ?: false

    /**
     * {@inheritDoc}
     */
    override fun toString() = "ChatMember[$name]"

    override val features: List<String> by lazy {
        val features = chatRoom.xmppProvider.discoverFeatures(occupantJid)
        // Update the stats once when the features are discovered.
        capsNodeVer?.let {
            XmppCapsStats.update(it, features)
        } ?: logger.error("No caps nodeVer found")

        features
    }

    override val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["region"] = region.toString()
            this["occupant_jid"] = occupantJid.toString()
            this["jid"] = jid.toString()
            this["robot"] = robot
            this["is_jibri"] = isJibri
            this["is_jigasi"] = isJigasi
            this["role"] = role.toString()
            this["stats_id"] = statsId.toString()
            this["is_audio_muted"] = isAudioMuted
            this["is_video_muted"] = isVideoMuted
            this["features"] = features
            this["capsNodeVer"] = capsNodeVer.toString()
        }
}
