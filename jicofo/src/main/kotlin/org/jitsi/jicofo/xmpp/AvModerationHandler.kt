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
package org.jitsi.jicofo.xmpp

import org.jitsi.jicofo.ConferenceStore
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.MessageTypeFilter
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import java.lang.IllegalArgumentException

/**
 * Adds the A/V moderation handling. Process incoming messages and when audio or video moderation is enabled,
 * muted all participants in the meeting (that are not moderators). Moderators are always allowed to unmute.
 */
class AvModerationHandler(
    private val xmppProvider: XmppProvider,
    private val conferenceStore: ConferenceStore
) : XmppProvider.Listener, StanzaListener {
    private var avModerationAddress: DomainBareJid? = null
    private val logger = createLogger()

    init {
        xmppProvider.xmppConnection.addSyncStanzaListener(this, MessageTypeFilter.NORMAL)
        xmppProvider.addListener(this)
        registrationChanged(xmppProvider.registered)
        componentsChanged(xmppProvider.components)
    }

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["address"] = avModerationAddress.toString()
        }

    override fun processStanza(stanza: Stanza) {
        if (stanza.from != avModerationAddress) {
            return
        }

        val jsonMessage = stanza.getExtension(
            JsonMessageExtension::class.java
        ) ?: return Unit.also {
            logger.warn("Skip processing stanza without JsonMessageExtension")
        }

        val message = try {
            val m = JsonMessage.parse(jsonMessage.json)
            if (m !is AvModerationMessage) {
                throw IllegalArgumentException("Expected AvModerationMessage, got ${m.type}")
            }
            m
        } catch (e: Exception) {
            logger.warn("Failed to process av_moderation request from ${stanza.from}", e)
            return
        }

        val (conference, conferenceJid, chatRoom) = try {
            val conferenceJid = JidCreate.entityBareFrom(message.room)
            val conference = conferenceStore.getConference(conferenceJid)
                ?: throw IllegalStateException("Conference $conferenceJid does not exist.")

            Triple(
                conference,
                conferenceJid,
                conference.chatRoom ?: throw IllegalStateException("Conference has no associated chatRoom.")
            )
        } catch (e: Exception) {
            logger.warn("Failed to process av_moderation request from ${stanza.from}", e)
            return
        }

        chatRoom.queueXmppTask {
            val mediaType = message.mediaType
            val enabled = message.enabled
            message.whitelists?.let { whitelists ->
                whitelists.forEach { (mediaType, whitelist) ->
                    chatRoom.setAvModerationWhitelist(mediaType, whitelist)
                }
            }
            if (enabled != null && mediaType != null) {
                val wasEnabled = chatRoom.isAvModerationEnabled(mediaType)
                chatRoom.setAvModerationEnabled(mediaType, enabled)
                if (enabled && !wasEnabled) {
                    logger.info("Moderation for ${message.mediaType} in $conferenceJid was enabled by ${message.actor}")
                    // let's mute everyone except the actor
                    conference.muteAllParticipants(
                        mediaType,
                        JidCreate.entityFullFrom(message.actor ?: "")
                    )
                }
            }
        }
    }

    override fun componentsChanged(components: Set<XmppProvider.Component>) {
        val address = components.find { it.type == "av_moderation" }?.address

        avModerationAddress = if (address == null) {
            logger.info("No av_moderation component discovered.")
            null
        } else {
            logger.info("Using av_moderation component at $address.")
            JidCreate.domainBareFrom(address)
        }
    }

    fun shutdown() {
        xmppProvider.xmppConnection.removeSyncStanzaListener(this)
    }
}
