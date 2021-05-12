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
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.jigasi.JigasiConfig
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.reservation.ReservationSystem
import org.jitsi.utils.logging2.createLogger

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

    private val avModerationHandler = AvModerationHandler(clientConnection)

    private val audioMuteHandler = AudioMuteIqHandler(setOf(clientConnection.xmppConnection))
    private val videoMuteHandler = VideoMuteIqHandler(setOf(clientConnection.xmppConnection))

    var iqHandler: IqHandler? = null
    fun stop() {
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
        audioMuteHandler.conferenceStore = focusManager
        videoMuteHandler.conferenceStore = focusManager

        val iqHandler = IqHandler(conferenceIqHandler, authenticationIqHandler).apply {
            init(clientConnection.xmppConnection)
        }
        this.iqHandler = iqHandler
    }

    fun dispose() {
        jigasiDetector?.dispose()
        jibriIqHandler.shutdown()
        jigasiIqHandler?.shutdown()
        audioMuteHandler.shutdown()
        videoMuteHandler.shutdown()
    }
}

enum class XmppConnectionEnum { Client, Service }
