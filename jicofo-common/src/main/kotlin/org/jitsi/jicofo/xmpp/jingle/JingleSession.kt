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
package org.jitsi.jicofo.xmpp.jingle

import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.protocol.xmpp.JingleApi
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.JinglePacketFactory
import org.jitsi.xmpp.extensions.jingle.Reason
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.Jid

/**
 * Class describes Jingle session.
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 */
class JingleSession(
    /** Jingle session identifier. */
    val sessionID: String,
    /** Remote peer XMPP address. */
    val address: Jid,
    private val jingleApi: JingleApi,
    private val requestHandler: JingleRequestHandler
) {
    val logger = createLogger()

    fun processIq(iq: JingleIQ): StanzaError? {
        val action = iq.action
            ?: return StanzaError.getBuilder(StanzaError.Condition.bad_request)
                .setConditionText("Missing 'action'").build()
        JingleApi.stats.stanzaReceived(action)

        return when (action) {
            JingleAction.SESSION_ACCEPT -> requestHandler.onSessionAccept(this, iq.contentList)
            JingleAction.SESSION_INFO -> requestHandler.onSessionInfo(this, iq)
            JingleAction.SESSION_TERMINATE -> requestHandler.onSessionTerminate(this, iq)
            JingleAction.TRANSPORT_ACCEPT -> requestHandler.onTransportAccept(this, iq.contentList)
            JingleAction.TRANSPORT_INFO -> { requestHandler.onTransportInfo(this, iq.contentList); null }
            JingleAction.TRANSPORT_REJECT -> { requestHandler.onTransportReject(this, iq); null }
            JingleAction.ADDSOURCE, JingleAction.SOURCEADD -> requestHandler.onAddSource(this, iq.contentList)
            JingleAction.REMOVESOURCE, JingleAction.SOURCEREMOVE -> requestHandler.onRemoveSource(this, iq.contentList)
            else -> {
                logger.warn("unsupported action $action")
                StanzaError.getBuilder(StanzaError.Condition.feature_not_implemented)
                    .setConditionText("Unsupported 'action'").build()
            }
        }
    }

    fun terminate(
        reason: Reason,
        message: String?,
        sendTerminate: Boolean
    ) {
        logger.info("Terminating session with $address, reason=$reason, sendTerminate=$sendTerminate")

        if (sendTerminate) {
            val terminate = JinglePacketFactory.createSessionTerminate(
                jingleApi.ourJID,
                address,
                sessionID,
                reason,
                message
            )
            jingleApi.connection.tryToSendStanza(terminate)
            JingleApi.stats.stanzaSent(JingleAction.SESSION_TERMINATE)
        }

        jingleApi.removeSession(this)
    }
}
