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

import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.XmppCapsStats
import org.jitsi.jicofo.xmpp.XmppConfig
import org.jitsi.jicofo.xmpp.muc.MemberRole.Companion.fromSmack
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.jitsimeet.AudioMutedExtension
import org.jitsi.xmpp.extensions.jitsimeet.FeaturesExtension
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantCodecList
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantRegionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.StatsId
import org.jitsi.xmpp.extensions.jitsimeet.VideoMutedExtension
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smackx.caps.packet.CapsExtension
import org.json.simple.JSONArray
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
    override var isTranscriber = false
        private set
    override var isJibri = false
        private set
    override var statsId: String? = null
        private set
    override var videoCodecs: List<String>? = null
        private set
    override var isAudioMuted = true
        private set
    override var isVideoMuted = true
        private set
    override var sourceInfos = emptySet<SourceInfo>()
        private set

    /** The node#ver advertised in a Caps extension. */
    private var capsNodeVer: String? = null

    override var role: MemberRole =
        chatRoom.getOccupant(this)?.let { fromSmack(it.role, it.affiliation) } ?: MemberRole.VISITOR
        private set

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

    private fun updateSourceInfo(presence: Presence) {
        val sourceInfo = presence.getExtension<StandardExtensionElement>("SourceInfo", "jabber:client")
        if (sourceInfo == null) {
            sourceInfos = emptySet()
        } else {
            val sourceInfos: Set<SourceInfo> = try {
                parseSourceInfoJson(sourceInfo.text)
            } catch (e: Exception) {
                logger.warn("Ignoring invalid SourceInfo JSON", e)
                return
            }
            this.sourceInfos = sourceInfos
        }

        val wasAudioMuted = isAudioMuted
        isAudioMuted = if (sourceInfo == null) {
            // Support the old format, still used by jigasi
            presence.getExtension(AudioMutedExtension::class.java)?.isAudioMuted ?: true
        } else {
            sourceInfos.filter { it.mediaType == MediaType.AUDIO }.none { !it.muted }
        }
        if (isAudioMuted != wasAudioMuted) {
            logger.debug { "isAudioMuted = $isAudioMuted" }
            if (isAudioMuted) chatRoom.audioSendersCount-- else chatRoom.audioSendersCount++
        }

        val wasVideoMuted = isVideoMuted
        isVideoMuted = if (sourceInfo == null) {
            // Support the old format, still used by jigasi
            presence.getExtension(VideoMutedExtension::class.java)?.isVideoMuted ?: true
        } else {
            sourceInfos.filter { it.mediaType == MediaType.VIDEO }.none { !it.muted }
        }
        if (isVideoMuted != wasVideoMuted) {
            logger.debug { "isVideoMuted = $isVideoMuted" }
            if (isVideoMuted) chatRoom.videoSendersCount-- else chatRoom.videoSendersCount++
        }
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

        val firstPresence = (this.presence == null)
        this.presence = presence

        presence.getExtension(CapsExtension::class.java)?.let {
            capsNodeVer = "${it.node}#${it.ver}"
        }

        updateSourceInfo(presence)

        // We recognize jigasi by the existence of a "feature" extension in its presence.
        val features = presence.getExtension(FeaturesExtension::class.java)
        if (features != null) {
            val domain = jid?.asDomainBareJid()
            val jidTrusted = domain != null && XmppConfig.config.trustedDomains.contains(domain)
            val jibriSignaled = features.featureExtensions.any { it.`var` == "http://jitsi.org/protocol/jibri" }
            val jigasiSignaled = features.featureExtensions.any { it.`var` == "http://jitsi.org/protocol/jigasi" }
            val transcriberSignaled = features.featureExtensions.any {
                it.`var` == "http://jitsi.org/protocol/transcriber"
            }

            isJibri = jibriSignaled && jidTrusted
            isJigasi = jigasiSignaled && jidTrusted
            isTranscriber = transcriberSignaled && jidTrusted

            if (jibriSignaled && !jidTrusted) {
                logger.warn(
                    "Jibri signaled from a non-trusted domain ($domain). The domain can be " +
                        "configured as trusted with the jicofo.xmpp.trusted-domains property."
                )
            }
            if (jigasiSignaled && !jidTrusted) {
                logger.warn(
                    "Jigasi signaled from a non-trusted domain ($domain). The domain can be " +
                        "configured as trusted with the jicofo.xmpp.trusted-domains property."
                )
            }
        } else {
            isJigasi = false
            isJibri = false
            isTranscriber = false
        }

        var newRole: MemberRole = MemberRole.VISITOR
        chatRoom.getOccupant(this)?.let { newRole = fromSmack(it.role, it.affiliation) }
        if (!firstPresence && (role == MemberRole.VISITOR) != (newRole == MemberRole.VISITOR)) {
            // Allowing this to change would mess up various member counts, ignore the change
            // TODO: Should we try to update them, instead?
            if (presence.type != Presence.Type.unavailable) {
                logger.warn("Member role changed from $role to $newRole - not supported!")
            }
        } else {
            role = newRole
        }

        presence.getExtension(JitsiParticipantRegionPacketExtension::class.java)?.let {
            region = it.regionId
        }

        presence.getExtension(StatsId::class.java)?.let {
            statsId = it.statsId
        }

        val newVideoCodecs =
            presence.getExtension(JitsiParticipantCodecList::class.java)?.let {
                if (!it.codecs.contains("vp8")) {
                    if (firstPresence) {
                        logger.warn("Video codec list {${it.codecs}} does not contain vp8! Adding manually.")
                    }
                    it.codecs + "vp8"
                } else {
                    it.codecs
                }
            } ?: // Older clients sent a single codec in codecType rather than all supported ones in codecList
                presence.getExtensionElement("jitsi_participant_codecType", "jabber:client")?.let {
                    if (it is StandardExtensionElement) {
                        val codec = it.text.lowercase()
                        if (codec == "vp8") {
                            listOf(codec)
                        } else {
                            listOf(codec, "vp8")
                        }
                    } else {
                        null
                    }
                }
        if (!firstPresence && newVideoCodecs != videoCodecs) {
            // Allowing this to change would mess up visitor codec preference counts, ignore the change
            if (role == MemberRole.VISITOR && presence.type != Presence.Type.unavailable && newVideoCodecs != null) {
                logger.warn("Visitor video codec list changed from $videoCodecs to $newVideoCodecs - not supported!")
            }
        } else {
            videoCodecs = newVideoCodecs
        }
    }

    override fun toString() = "ChatMember[id=$name role=$role]"

    override val features: Set<Features> by lazy {
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
            this["is_jibri"] = isJibri
            this["is_jigasi"] = isJigasi
            this["is_transcriber"] = isTranscriber
            this["role"] = role.toString()
            this["video_codecs"] = JSONArray().apply { videoCodecs?.let { addAll(it) } }
            this["stats_id"] = statsId.toString()
            this["is_audio_muted"] = isAudioMuted
            this["is_video_muted"] = isVideoMuted
            this["features"] = features.map { it.name }
            this["capsNodeVer"] = capsNodeVer.toString()
        }
}
