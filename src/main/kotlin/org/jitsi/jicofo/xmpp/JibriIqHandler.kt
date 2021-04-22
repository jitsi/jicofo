/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017-Present 8x8, Inc.
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

import org.jitsi.jicofo.jibri.BaseJibri
import org.jitsi.jicofo.jibri.JibriSessionIqHandler
import org.jitsi.jicofo.util.ErrorResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import java.util.Collections
import java.util.LinkedList

/**
 * A Smack [IQRequestHandler] for "jibri" IQs. Terminates all "jibri" IQs received by Smack, but delegates their
 * handling to specific [BaseJibri] instances.
 */
class JibriIqHandler(connections: Set<XMPPConnection>) :
    AbstractIqHandler<JibriIq>(
        connections,
        JibriIq.ELEMENT_NAME,
        JibriIq.NAMESPACE,
        setOf(IQ.Type.set),
        IQRequestHandler.Mode.sync
    ) {

    private val jibris = Collections.synchronizedList(LinkedList<JibriSessionIqHandler>())

    fun addJibri(jibri: JibriSessionIqHandler) {
        jibris.add(jibri)
    }

    fun removeJibri(jibri: JibriSessionIqHandler?) {
        jibris.remove(jibri)
    }

    /**
     * {@inheritDoc}
     * Pass the request to the first [BaseJibri] that wants it.
     *
     * Note that this is synchronized to ensure correct use of the synchronized list (and we want to avoid using a
     * copy on write list for performance reasons).
     */
    override fun handleRequest(request: IqRequest<JibriIq>): IqProcessingResult = synchronized(jibris) {
        val jibri = jibris.find { it.accept(request.iq) }
            ?: return RejectedWithError(ErrorResponse.create(request.iq, XMPPError.Condition.item_not_found, null))
        jibri.handleJibriRequest(request)
        AcceptedWithNoResponse()
    }
}
