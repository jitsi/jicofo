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

import org.jitsi.impl.protocol.xmpp.RegistrationListener
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.EmptyConferenceStore
import org.jitsi.jicofo.TaskPools
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.MessageTypeFilter
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException

/**
 * Adds the A/V moderation handling. Process incoming messages and when audio or video moderation is enabled,
 * muted all participants in the meeting (that are not moderators). Moderators are always allowed to unmute.
 */
class AvModerationHandler(val xmppProvider: XmppProvider) : RegistrationListener {
    var conferenceStore: ConferenceStore = EmptyConferenceStore()
    private val jsonParser = JSONParser()
    private var avModerationAddress: DomainBareJid? = null

    init {
        xmppProvider.xmppConnection.addAsyncStanzaListener(
            StanzaListener { stanza -> TaskPools.ioPool.submit { processStanza(stanza) } },
            MessageTypeFilter.NORMAL
        )
        // If the provider manages to register early our registration listener will not be processed
        // this seems not to be the case, but calling registrationChanged when already registered break the tests
        // will leave it commented for now, till we manage to differentiate is this ran by the tests
        // registrationChanged(xmppProvider.isRegistered)
        xmppProvider.addRegistrationListener(this)
    }

    private fun processStanza(stanza: Stanza) {
        if (stanza.from != avModerationAddress) {
            return
        }

        val jsonMessage = stanza.getExtension<JsonMessageExtension>(
            JsonMessageExtension.ELEMENT_NAME, JsonMessageExtension.NAMESPACE
        ) ?: return Unit.also {
            logger.warn("Skip processing stanza without JsonMessageExtension")
        }

        try {
            val incomingJson = jsonParser.parse(jsonMessage.json) as JSONObject
            if (incomingJson["type"] == "av_moderation") {
                val conferenceJid = JidCreate.entityBareFrom(incomingJson["room"]?.toString())

                val conference = conferenceStore.getConference(conferenceJid) ?: return Unit.also {
                    logger.warn("Not processing message for not existing conference conferenceJid=$conferenceJid")
                }

                val enabled = incomingJson["enabled"] as Boolean?
                val lists = incomingJson["whitelists"] as JSONObject?

                if (enabled != null) {
                    val mediaType = MediaType.parseString(incomingJson["mediaType"] as String)
                    val oldEnabledValue = conference.chatRoom.isAvModerationEnabled(mediaType)
                    conference.chatRoom.setAvModerationEnabled(mediaType, enabled)
                    if (oldEnabledValue != enabled && enabled) {
                        // let's mute everyone
                        conference.muteAllNonModeratorParticipants(mediaType)
                    }
                } else if (lists != null) {
                    conference.chatRoom.updateAvModerationWhitelists(lists as Map<String, List<String>>)
                }
            }
        } catch (e: Exception) {
            logger.warn("Cannot parse json for av_moderation coming from ${stanza.from}")
        }
    }

    /**
     * When the connection is registered we do disco-info query to check for 'av_moderation' component
     * and we use that address to verify incoming messages.
     * We do that only once for the life of jicofo and skip it on reconnections.
     */
    override fun registrationChanged(registered: Boolean) {
        if (!registered || avModerationAddress != null) {
            return
        }

        try {
            val discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppProvider.xmppConnection)
            val info = discoveryManager.discoverInfo(JidCreate.bareFrom(XmppConfig.client.xmppDomain))
            val avModIdentities = info?.getIdentities("component", "av_moderation")

            if (avModIdentities != null && avModIdentities.size > 0) {
                avModerationAddress = JidCreate.domainBareFrom(avModIdentities[0].name)
            }
        } catch (e: XmppStringprepException) {
            logger.error("Error checking for av_moderation component", e)
        }
    }
}
