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
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

/**
 * Maintain a weak map of [JingleSession]s and route incoming Jingle IQs to the associated session.
 * @author Pawel Domas
 */
open class JingleIqRequestHandler :
    AbstractIqRequestHandler(JingleIQ.ELEMENT, JingleIQ.NAMESPACE, IQ.Type.set, IQRequestHandler.Mode.sync) {
    private val logger = createLogger()

    /**
     * The list of active Jingle sessions.
     */
    @JvmField
    protected val sessions = WeakValueMap<String, JingleSession>()

    override fun handleIQRequest(iq: IQ): IQ {
        val jingleIq = iq as JingleIQ
        val session = sessions.get(jingleIq.sid)
        if (session == null) {
            logger.warn("No session found for SID ${jingleIq.sid}")
            return IQ.createErrorResponse(jingleIq, StanzaError.getBuilder(StanzaError.Condition.bad_request).build())
        }

        val error = session.processIq(jingleIq)
        return if (error == null) {
            IQ.createResultIQ(iq)
        } else {
            IQ.createErrorResponse(iq, error)
        }
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
