/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024-Present 8x8, Inc.
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

class RoomMetadataHandler(
    private val xmppProvider: XmppProvider,
    private val conferenceStore: ConferenceStore
) : XmppProvider.Listener, StanzaListener {
    private var componentAddress: DomainBareJid? = null
    private val logger = createLogger()

    init {
        xmppProvider.xmppConnection.addSyncStanzaListener(this, MessageTypeFilter.NORMAL)
        xmppProvider.addListener(this)
        registrationChanged(xmppProvider.registered)
        componentsChanged(xmppProvider.components)
    }

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["address"] = componentAddress.toString()
        }

    private fun doProcess(jsonMessage: JsonMessageExtension) {
        val (chatRoom, roomMetadata) = try {
            val conferenceJid = JidCreate.entityBareFrom(jsonMessage.getAttribute("room")?.toString())
            val roomMetadata = JsonMessage.parse(jsonMessage.json)

            if (roomMetadata !is RoomMetadata) {
                throw IllegalArgumentException("Received invalid message type: ${jsonMessage.json}")
            }

            val conference = conferenceStore.getConference(conferenceJid)
                ?: throw IllegalStateException("Conference $conferenceJid does not exist.")

            Pair(
                conference.chatRoom ?: throw IllegalStateException("Conference has no associated chatRoom."),
                roomMetadata
            )
        } catch (e: Exception) {
            logger.info("Failed to process room_metadata request: ${jsonMessage.toXML()}")
            return
        }

        chatRoom.setRoomMetadata(roomMetadata)
    }

    override fun processStanza(stanza: Stanza) {
        if (stanza.from != componentAddress) {
            return
        }

        val jsonMessage = stanza.getExtension(JsonMessageExtension::class.java) ?: return Unit.also {
            logger.warn("Skip processing stanza without JsonMessageExtension.")
        }

        doProcess(jsonMessage)
    }

    override fun componentsChanged(components: Set<XmppProvider.Component>) {
        val address = components.find { it.type == "room_metadata" }?.address

        componentAddress = if (address == null) {
            logger.info("No room_metadata component discovered.")
            null
        } else {
            logger.info("Using room_metadata component at $address.")
            JidCreate.domainBareFrom(address)
        }
    }

    fun shutdown() {
        xmppProvider.xmppConnection.removeSyncStanzaListener(this)
    }
}
