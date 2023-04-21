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

import org.jitsi.jicofo.util.WeakValueMap
import org.jitsi.jicofo.xmpp.AbstractIqHandler
import org.jitsi.jicofo.xmpp.IqProcessingResult
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.jicofo.xmpp.IqRequest
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

/**
 * Maintain a weak map of [JingleSession]s and route incoming Jingle IQs to the associated session.
 * @author Pawel Domas
 */
class JingleIqRequestHandler(
    connections: Set<AbstractXMPPConnection>
) : AbstractIqHandler<JingleIQ>(connections, JingleIQ.ELEMENT, JingleIQ.NAMESPACE, setOf(IQ.Type.set)) {
    private val logger = createLogger()

    /** The list of active Jingle sessions. */
    private val sessions = WeakValueMap<String, JingleSession>()

    override fun handleRequest(request: IqRequest<JingleIQ>): IqProcessingResult {
        val session = sessions.get(request.iq.sid)
        if (session == null) {
            logger.warn("No session found for SID ${request.iq.sid}")
            return RejectedWithError(
                IQ.createErrorResponse(
                    request.iq,
                    StanzaError.getBuilder(StanzaError.Condition.bad_request).build()
                )
            )
        }

        return session.processIq(request.iq)
    }

    fun registerSession(session: JingleSession) {
        val existingSession = sessions.get(session.sid)
        if (existingSession != null) {
            logger.warn("Replacing existing session with SID ${session.sid}")
        }
        sessions.put(session.sid, session)
    }

    fun removeSession(session: JingleSession) {
        sessions.remove(session.sid)
    }
}
