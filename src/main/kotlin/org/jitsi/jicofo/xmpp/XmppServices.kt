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

import net.java.sip.communicator.service.protocol.ProtocolProviderFactory
import org.apache.commons.lang3.StringUtils
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.ProtocolProviderHandler
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.reservation.ReservationSystem
import org.jitsi.jicofo.util.getService
import org.jitsi.protocol.xmpp.XmppConnection
import org.jitsi.service.configuration.ConfigurationService
import org.jitsi.utils.logging2.createLogger
import org.osgi.framework.BundleContext
import java.util.concurrent.ScheduledExecutorService

class XmppServices(
    private val bundleContext: BundleContext,
    scheduledExecutorService: ScheduledExecutorService,
    xmppProviderFactory: ProtocolProviderFactory
) {
    private val logger = createLogger()

    val clientConnection: ProtocolProviderHandler = ProtocolProviderHandler(
        XmppConfig.client,
        scheduledExecutorService
    ).apply {
        start(bundleContext, xmppProviderFactory)
        register()
    }

    val serviceConnection: ProtocolProviderHandler = if (XmppConfig.service.enabled) {
        logger.info("Using dedicated Service XMPP connection for JVB MUC.")
        ProtocolProviderHandler(XmppConfig.service, scheduledExecutorService).apply {
            start(bundleContext, xmppProviderFactory)
            register()
        }
    } else {
        logger.info("No dedicated Service XMPP connection configured, re-using the client XMPP connection.")
        clientConnection
    }

    var iqHandler: IqHandler? = null
    var focusComponent: FocusComponent? = null

    fun stop() {
        iqHandler?.stop()
        clientConnection.stop()
        if (serviceConnection != clientConnection) {
            serviceConnection.stop()
        }
        focusComponent?.disconnect()
    }

    fun init(
        authenticationAuthority: AbstractAuthAuthority?,
        focusManager: FocusManager,
        reservationSystem: ReservationSystem?,
        jigasiEnabled: Boolean
    ) {
        val authenticationIqHandler = authenticationAuthority?.let { AuthenticationIqHandler(it) }
        val conferenceIqHandler = ConferenceIqHandler(
            focusManager = focusManager,
            focusAuthJid = "${XmppConfig.client.username}@${XmppConfig.client.domain}",
            isFocusAnonymous = StringUtils.isBlank(XmppConfig.client.password),
            authAuthority = authenticationAuthority,
            reservationSystem = reservationSystem,
            jigasiEnabled = jigasiEnabled
        )

        val iqHandler = IqHandler(focusManager, conferenceIqHandler, authenticationIqHandler).apply {
            clientConnection.addXmppConnectionListener(
                object : ProtocolProviderHandler.XmppConnectionListener {
                    override fun xmppConnectionInitialized(xmppConnection: XmppConnection) {
                        init(xmppConnection)
                    }
                })
        }
        this.iqHandler = iqHandler

        focusComponent = if (XmppComponentConfig.config.enabled) {
            FocusComponent(XmppComponentConfig.config, iqHandler).apply {
                val configService = getService(bundleContext, ConfigurationService::class.java)
                loadConfig(configService, "org.jitsi.jicofo")
            }
        } else {
            null
        }
        focusComponent?.connect()
    }

}