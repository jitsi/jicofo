/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2023-Present 8x8, Inc.
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
import org.jitsi.jicofo.visitors.VisitorsConfig
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.visitors.VisitorsIq
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import java.lang.Exception

/**
 * Serve two purposes:
 * 1. Communicates with a prosody "visitors" component (jicofo initiates communication).
 * 2. Handle incoming [VisitorsIq]s from endpoints in a conference.
 */
class VisitorsManager(
    val xmppProvider: XmppProvider,
    val conferenceStore: ConferenceStore
) : StanzaListener {
    init {
        xmppProvider.xmppConnection.addSyncStanzaListener(this, VisitorsIqFilter())
    }

    private val logger = createLogger().apply {
        addContext("connection", xmppProvider.config.name)
    }

    var address: DomainBareJid? = null

    private fun updateAddress(components: Set<XmppProvider.Component>) {
        address = components.find { it.type == "visitors" }?.address?.let { JidCreate.domainBareFrom(it) }
        logger.info("VisitorsComponentManager is now ${if (enabled) "en" else "dis"}abled with address $address")
    }

    private fun createIq(roomJid: EntityBareJid, extensions: List<ExtensionElement>): VisitorsIq {
        val address = this.address ?: throw Exception("Component not available.")
        return VisitorsIq.Builder(xmppProvider.xmppConnection).apply {
            to(address)
            ofType(IQ.Type.get)
            room = roomJid
            addExtensions(extensions)
        }.build()
    }

    /** Send an IQ, block for response or timeout, return the result. */
    fun sendIqToComponentAndGetResponse(roomJid: EntityBareJid, extensions: List<ExtensionElement>): IQ? =
        xmppProvider.xmppConnection.sendIqAndGetResponse(createIq(roomJid, extensions))

    /** Send an IQ, return immediately. Log an error if there's no response. */
    fun sendIqToComponent(roomJid: EntityBareJid, extensions: List<ExtensionElement>) {
        TaskPools.ioPool.submit {
            val response = sendIqToComponentAndGetResponse(roomJid, extensions)
            when {
                response == null -> logger.warn("Timeout waiting for VisitorsIq response.")
                response.type == IQ.Type.result -> {
                    logger.info("Received VisitorsIq response: ${response.toXML()}")
                }
                else -> logger.warn("Received error response: ${response.toXML()}")
            }
        }
    }

    init {
        updateAddress(xmppProvider.components)
        xmppProvider.addListener(
            object : XmppProvider.Listener {
                override fun componentsChanged(components: Set<XmppProvider.Component>) {
                    updateAddress(components)
                }
            }
        )
    }
    val enabled
        get() = VisitorsConfig.config.enabled && address != null

    override fun processStanza(stanza: Stanza) {
        if (stanza !is VisitorsIq) {
            logger.error("Received unexpected stanza type received: ${stanza.javaClass.name}")
            return
        }

        conferenceStore.getConference(stanza.room) ?: run {
            logger.warn("Ignoring VisitorsIq for unknown conference ${stanza.room}.")
            return
        }
        logger.info("Received VisitorsIq: ${stanza.toXML()}")
        // TODO pass to the conference
    }
}

class VisitorsIqFilter : StanzaFilter {
    override fun accept(stanza: Stanza) = stanza is VisitorsIq
}
