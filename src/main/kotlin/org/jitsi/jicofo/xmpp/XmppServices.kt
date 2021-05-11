/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

import org.apache.commons.lang3.StringUtils
import org.jitsi.impl.protocol.xmpp.RegistrationListener
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.EmptyConferenceStore
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.jigasi.JigasiConfig
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.reservation.ReservationSystem
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.MessageTypeFilter
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.json.simple.parser.ParseException
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException

class XmppServices(xmppProviderFactory: XmppProviderFactory) {
    private val logger = createLogger()

    val clientConnection: XmppProvider = xmppProviderFactory.createXmppProvider(XmppConfig.client, logger).apply {
        start()
    }

    val serviceConnection: XmppProvider = if (XmppConfig.service.enabled) {
        logger.info("Using a dedicated Service XMPP connection.")
        xmppProviderFactory.createXmppProvider(XmppConfig.service, logger).apply {
            start()
        }
    } else {
        logger.info("No dedicated Service XMPP connection configured, re-using the client XMPP connection.")
        clientConnection
    }

    val jigasiDetector = JigasiConfig.config.breweryJid?.let { breweryJid ->
        JigasiDetector(clientConnection, breweryJid).apply { init() }
    } ?: run {
        logger.info("No Jigasi detector configured.")
        null
    }

    private val jibriIqHandler = JibriIqHandler(
        setOf(clientConnection.xmppConnection, serviceConnection.xmppConnection)
    )

    private val jigasiIqHandler = if (jigasiDetector != null) {
        JigasiIqHandler(
            setOf(clientConnection.xmppConnection, serviceConnection.xmppConnection),
            jigasiDetector
        )
    } else null

    private val avModerationHandler = AvModerationHandler(clientConnection.xmppConnection).also {
        clientConnection.xmppConnection.addAsyncStanzaListener(it, MessageTypeFilter.NORMAL)
        clientConnection.addRegistrationListener(it)
    }

    var iqHandler: IqHandler? = null
    fun stop() {
        iqHandler?.stop()
        clientConnection.stop()
        if (serviceConnection != clientConnection) {
            serviceConnection.stop()
        }
    }

    fun init(
        authenticationAuthority: AbstractAuthAuthority?,
        focusManager: FocusManager,
        reservationSystem: ReservationSystem?,
        jigasiEnabled: Boolean
    ) {
        val authenticationIqHandler = authenticationAuthority?.let { AuthenticationIqHandler(it) }
        val conferenceIqHandler = ConferenceIqHandler(
            connection = clientConnection.xmppConnection,
            focusManager = focusManager,
            focusAuthJid = "${XmppConfig.client.username}@${XmppConfig.client.domain}",
            isFocusAnonymous = StringUtils.isBlank(XmppConfig.client.password),
            authAuthority = authenticationAuthority,
            reservationSystem = reservationSystem,
            jigasiEnabled = jigasiEnabled
        )
        jibriIqHandler.conferenceStore = focusManager
        avModerationHandler.conferenceStore = focusManager

        val iqHandler = IqHandler(focusManager, conferenceIqHandler, authenticationIqHandler).apply {
            init(clientConnection.xmppConnection)
        }
        this.iqHandler = iqHandler
    }

    fun dispose() {
        jigasiDetector?.dispose()
        jibriIqHandler.shutdown()
        jigasiIqHandler?.shutdown()
    }
}

enum class XmppConnectionEnum { Client, Service }

class AvModerationHandler(val xmppConnection: AbstractXMPPConnection) : StanzaListener, RegistrationListener {
    var conferenceStore: ConferenceStore = EmptyConferenceStore()
    private val jsonParser = JSONParser()
    private var avModerationAddress: DomainBareJid? = null

    override fun processStanza(stanza: Stanza) {
        if (stanza == null || stanza.from != avModerationAddress) {
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
                    val actorJid = JidCreate.entityFrom(incomingJson["actor"] as String)
                    val oldEnabledValue = conference.chatRoom.isAvModerationEnabled(mediaType)
                    conference.chatRoom.setAvModerationEnabled(actorJid, mediaType, enabled)
                    if (oldEnabledValue != enabled && enabled) {
                        // let's mute everyone
                        conference.muteAllNonModeratorParticipants(mediaType)
                    }
                } else if (lists != null) {
                    conference.chatRoom.updateAvModerationWhitelists(lists as Map<String, List<String>>)
                }
            }
        } catch (e: ParseException) {
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
            val discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppConnection)
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
