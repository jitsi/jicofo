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
import org.jitsi.jicofo.reservation.ReservationSystem
import org.jitsi.utils.logging2.createLogger

class XmppServices(xmppProviderFactory: XmppProviderFactory) {
    private val logger = createLogger()

    val clientConnection: XmppProvider = xmppProviderFactory.createXmppProvider(XmppConfig.client, logger).apply {
        start()
    }

    val serviceConnection: XmppProvider = if (XmppConfig.service.enabled) {
        logger.info("Using dedicated Service XMPP connection for JVB MUC.")
        xmppProviderFactory.createXmppProvider(XmppConfig.service, logger).apply {
            start()
        }
    } else {
        logger.info("No dedicated Service XMPP connection configured, re-using the client XMPP connection.")
        clientConnection
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

        val iqHandler = IqHandler(focusManager, conferenceIqHandler, authenticationIqHandler).apply {
            init(clientConnection.xmppConnection)
        }
        this.iqHandler = iqHandler
    }
}
