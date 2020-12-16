/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError

class AuthenticationIqHandler(val authAuthority: AuthenticationAuthority) {
    private val logger = createLogger()

    fun handleLoginUrlIq(loginUrlIq: LoginUrlIq): IQ {
        val peerFullJid = loginUrlIq.from.asEntityFullJidIfPossible()
        val roomName = loginUrlIq.room
        if (roomName == null) {
            val error = XMPPError.getBuilder(XMPPError.Condition.not_acceptable)
            return IQ.createErrorResponse(loginUrlIq, error)
        }
        val result = LoginUrlIq()
        result.type = IQ.Type.result
        result.stanzaId = loginUrlIq.stanzaId
        result.to = loginUrlIq.from
        val popup = loginUrlIq.popup != null && loginUrlIq.popup
        val machineUID = loginUrlIq.machineUID
        if (StringUtils.isBlank(machineUID)) {
            val error = XMPPError.from(
                XMPPError.Condition.bad_request,
                "missing mandatory attribute 'machineUID'"
            )
            return IQ.createErrorResponse(loginUrlIq, error)
        }
        val authUrl = authAuthority.createLoginUrl(machineUID, peerFullJid, roomName, popup)
        result.url = authUrl
        logger.info("Sending url: " + result.toXML())
        return result
    }

    fun handleLogoutUrlIq(logoutIq: LogoutIq): IQ = authAuthority.processLogoutIq(logoutIq)
}

