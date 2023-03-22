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
import org.jitsi.jicofo.TaskPools
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.MessageTypeFilter
import org.jivesoftware.smack.packet.Stanza
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import java.lang.IllegalArgumentException
import kotlin.jvm.Throws

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

        TaskPools.ioPool.execute {
            try {
                val incomingJson = JSONParser().parse(jsonMessage.json) as JSONObject
                if (incomingJson["type"] == "av_moderation") {
                    val conferenceJid = JidCreate.entityBareFrom(incomingJson["room"]?.toString())

                    val conference = conferenceStore.getConference(conferenceJid)
                        ?: throw IllegalStateException("Conference $conferenceJid does not exist.")
                    val chatRoom = conference.chatRoom
                        ?: throw IllegalStateException("Conference has no associated chatRoom.")

                    incomingJson["enabled"]?.let { enabled ->
                        if (enabled !is Boolean) {
                            throw IllegalArgumentException("Invalid value for the 'enabled' attribute: $enabled")
                        }
                        val mediaType = MediaType.parseString(incomingJson["mediaType"] as String)
                        val oldEnabledValue = chatRoom.isAvModerationEnabled(mediaType)
                        chatRoom.setAvModerationEnabled(mediaType, enabled)
                        if (oldEnabledValue != enabled && enabled) {
                            logger.info(
                                "Moderation for $mediaType in $conferenceJid was enabled by ${incomingJson["actor"]}"
                            )
                            // let's mute everyone
                            conference.muteAllParticipants(mediaType)
                        }
                    }
                    incomingJson["whitelists"]?.let {
                        parseWhitelists(it).forEach { (mediaType, whitelist) ->
                            chatRoom.setAvModerationWhitelist(mediaType, whitelist)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to process av_moderation request from ${stanza.from}", e)
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

/**
 * Parses the given object (expected to be a [JSONObject]) as a Map<MediaType, List<String>>. Throws
 * [IllegalArgumentException] if [o] is not of the expected type.
 * @return a map that is guaranteed to have a runtime type of Map<MediaType, List<String>>.
 */
@Throws(IllegalArgumentException::class)
private fun parseWhitelists(o: Any): Map<MediaType, List<String>> {
    val jsonObject: JSONObject = o as? JSONObject ?: throw IllegalArgumentException("Not a JSONObject")
    val map = mutableMapOf<MediaType, List<String>>()
    jsonObject.forEach { (k, v) ->
        k as? String ?: throw IllegalArgumentException("Key is not a string")
        v as? List<*> ?: throw IllegalArgumentException("Value is not a list")
        val mediaType = MediaType.parseString(k)
        if (mediaType != MediaType.AUDIO && mediaType != MediaType.VIDEO) {
            throw IllegalArgumentException("Invalid mediaType: $k")
        }
        map[mediaType] = v.map { it.toString() }
    }
    return map
}
