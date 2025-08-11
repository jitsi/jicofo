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

import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.jigasi.JigasiConfig
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.xmpp.jingle.JingleIqRequestHandler
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger

class XmppServices(
    conferenceStore: ConferenceStore,
    authenticationAuthority: AbstractAuthAuthority?,
    focusManager: FocusManager
) {
    private val logger = createLogger()

    val clientConnection: XmppProvider = XmppProvider(XmppConfig.client, logger).apply {
        start()
    }

    val serviceConnection: XmppProvider = if (XmppConfig.service.enabled) {
        logger.info("Using a dedicated Service XMPP connection.")
        XmppProvider(XmppConfig.service, logger).apply { start() }
    } else {
        logger.info("No dedicated Service XMPP connection configured, re-using the client XMPP connection.")
        clientConnection
    }

    val visitorConnections: List<XmppProvider> = XmppConfig.visitors.values.mapNotNull { config ->
        if (config.enabled) {
            logger.info("Using XMPP visitor connection ${config.name}")
            XmppProvider(config, logger).apply { start() }
        } else {
            logger.info("Visitor connection ${config.name} is disabled.")
            null
        }
    }

    fun getXmppConnectionByName(name: XmppConnectionEnum) = when (name) {
        XmppConnectionEnum.Client -> clientConnection
        XmppConnectionEnum.Service -> serviceConnection
    }

    fun getXmppVisitorConnectionByName(name: String) = visitorConnections.find { it.config.name == name }

    val jigasiDetector = JigasiConfig.config.breweryJid?.let { breweryJid ->
        JigasiDetector(
            getXmppConnectionByName(JigasiConfig.config.xmppConnectionName),
            breweryJid
        ).apply {
            init()
            JicofoMetricsContainer.instance.metricsUpdater.addUpdateTask { updateMetrics() }
        }
    } ?: run {
        logger.info("No Jigasi detector configured.")
        null
    }

    private val jibriIqHandler = JibriIqHandler(
        setOf(clientConnection.xmppConnection, serviceConnection.xmppConnection),
        conferenceStore
    )

    private val jigasiIqHandler = if (jigasiDetector != null) {
        JigasiIqHandler(
            setOf(clientConnection.xmppConnection, serviceConnection.xmppConnection) + visitorConnections.map {
                it.xmppConnection
            }.toSet(),
            conferenceStore,
            jigasiDetector
        )
    } else {
        null
    }
    val jigasiStats: OrderedJsonObject
        get() = jigasiIqHandler?.statsJson ?: OrderedJsonObject()

    val avModerationHandler = AvModerationHandler(clientConnection, conferenceStore)
    val configurationChangeHandler = ConfigurationChangeHandler(clientConnection, conferenceStore)
    val roomMetadataHandler = RoomMetadataHandler(clientConnection, conferenceStore)
    private val audioMuteHandler = AudioMuteIqHandler(setOf(clientConnection.xmppConnection), conferenceStore)
    private val videoMuteHandler = VideoMuteIqHandler(setOf(clientConnection.xmppConnection), conferenceStore)
    private val desktopMuteHandler = DesktopMuteIqHandler(setOf(clientConnection.xmppConnection), conferenceStore)
    val jingleHandler = JingleIqRequestHandler(
        visitorConnections.map { it.xmppConnection }.toSet() + clientConnection.xmppConnection
    )
    val visitorsManager = VisitorsManager(clientConnection, focusManager)

    val conferenceIqHandler = ConferenceIqHandler(
        xmppProvider = clientConnection,
        focusManager = focusManager,
        focusAuthJid = XmppConfig.client.jid,
        authAuthority = authenticationAuthority,
        jigasiEnabled = jigasiDetector != null,
        visitorsManager
    ).apply {
        clientConnection.xmppConnection.registerIQRequestHandler(this)
    }

    private val authenticationIqHandler: AuthenticationIqHandler? = if (authenticationAuthority == null) {
        null
    } else {
        AuthenticationIqHandler(authenticationAuthority).also {
            clientConnection.xmppConnection.registerIQRequestHandler(it.loginUrlIqHandler)
            clientConnection.xmppConnection.registerIQRequestHandler(it.logoutIqHandler)
        }
    }

    fun shutdown() {
        clientConnection.shutdown()
        if (serviceConnection != clientConnection) {
            serviceConnection.shutdown()
        }
        jigasiDetector?.shutdown()
        jibriIqHandler.shutdown()
        jigasiIqHandler?.shutdown()
        audioMuteHandler.shutdown()
        videoMuteHandler.shutdown()
        desktopMuteHandler.shutdown()
        avModerationHandler.shutdown()
        roomMetadataHandler.shutdown()
        configurationChangeHandler.shutdown()
        jingleHandler.shutdown()

        clientConnection.xmppConnection.unregisterIQRequestHandler(conferenceIqHandler)
        authenticationIqHandler?.let {
            clientConnection.xmppConnection.unregisterIQRequestHandler(it.loginUrlIqHandler)
            clientConnection.xmppConnection.unregisterIQRequestHandler(it.logoutIqHandler)
        }
    }
}
