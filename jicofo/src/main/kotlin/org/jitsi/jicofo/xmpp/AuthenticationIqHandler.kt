/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.LoginUrlIq
import org.jitsi.xmpp.extensions.jitsimeet.LogoutIq
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

class AuthenticationIqHandler(private val authAuthority: AuthenticationAuthority) {
    private val logger = createLogger()
    val loginUrlIqHandler: AbstractIqRequestHandler = LoginUrlIqHandler()
    val logoutIqHandler: AbstractIqRequestHandler = LogoutIqHandler()

    private fun handleLoginUrlIq(loginUrlIq: LoginUrlIq): IQ {
        val peerFullJid = loginUrlIq.from.asEntityFullJidIfPossible()
        val roomName = loginUrlIq.room ?: return createNotAcceptableErrorResponse(loginUrlIq)
        val popup = loginUrlIq.popup != null && loginUrlIq.popup
        val machineUID = loginUrlIq.machineUID
        if (StringUtils.isBlank(machineUID)) {
            return createBadRequestErrorResponse(loginUrlIq, "missing mandatory attribute 'machineUID'")
        }

        return LoginUrlIq().apply {
            type = IQ.Type.result
            stanzaId = loginUrlIq.stanzaId
            to = loginUrlIq.from
            url = authAuthority.createLoginUrl(machineUID, peerFullJid, roomName, popup)
            logger.info("Sending url: ${toXML()}")
        }
    }

    private fun handleLogoutIq(logoutIq: LogoutIq): IQ = authAuthority.processLogoutIq(logoutIq)

    private inner class LoginUrlIqHandler : AbstractIqRequestHandler(
        LoginUrlIq.ELEMENT,
        LoginUrlIq.NAMESPACE,
        IQ.Type.get,
        IQRequestHandler.Mode.sync
    ) {
        override fun handleIQRequest(iqRequest: IQ): IQ {
            return if (iqRequest is LoginUrlIq) {
                // If the IQ comes from mod_client_proxy, parse and substitute the original sender's JID.
                val originalFrom = iqRequest.from
                iqRequest.from = parseJidFromClientProxyJid(XmppConfig.client.clientProxy, originalFrom)
                handleLoginUrlIq(iqRequest).also {
                    it.to = originalFrom
                }
            } else {
                logger.error("Received an unexpected IQ type: $iqRequest")
                createInternalServerErrorResponse(iqRequest)
            }
        }
    }

    private inner class LogoutIqHandler : AbstractIqRequestHandler(
        LogoutIq.ELEMENT,
        LogoutIq.NAMESPACE,
        IQ.Type.set,
        IQRequestHandler.Mode.sync
    ) {
        override fun handleIQRequest(iqRequest: IQ): IQ {
            return if (iqRequest is LogoutIq) {
                // If the IQ comes from mod_client_proxy, parse and substitute the original sender's JID.
                val originalFrom = iqRequest.from
                iqRequest.from = parseJidFromClientProxyJid(XmppConfig.client.clientProxy, originalFrom)
                handleLogoutIq(iqRequest).also {
                    it.to = originalFrom
                }
            } else {
                logger.error("Received an unexpected IQ type: $iqRequest")
                createInternalServerErrorResponse(iqRequest)
            }
        }
    }

    private fun createErrorResponse(iq: IQ, condition: StanzaError.Condition): IQ =
        IQ.createErrorResponse(iq, StanzaError.getBuilder(condition).build())
    private fun createNotAcceptableErrorResponse(iq: IQ): IQ =
        createErrorResponse(iq, StanzaError.Condition.not_acceptable)
    private fun createBadRequestErrorResponse(iq: IQ, message: String): IQ =
        IQ.createErrorResponse(iq, StanzaError.from(StanzaError.Condition.bad_request, message).build())
    private fun createInternalServerErrorResponse(iq: IQ): IQ =
        IQ.createErrorResponse(iq, StanzaError.Condition.internal_server_error)
}
