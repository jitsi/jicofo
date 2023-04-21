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

import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.JicofoServices
import org.jitsi.jicofo.jibri.BaseJibri
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

/**
 * A Smack [IQRequestHandler] for "jibri" IQs. Terminates all "jibri" IQs received by Smack, but delegates their
 * handling to specific [BaseJibri] instances.
 */
class JibriIqHandler(
    connections: Set<AbstractXMPPConnection>,
    private val conferenceStore: ConferenceStore
) :
    AbstractIqHandler<JibriIq>(
        connections,
        JibriIq.ELEMENT,
        JibriIq.NAMESPACE,
        setOf(IQ.Type.set),
        IQRequestHandler.Mode.sync
    ) {
    val logger = createLogger()

    /**
     * {@inheritDoc}
     * Pass the request to the first [BaseJibri] that wants it.
     *
     * Note that this is synchronized to ensure correct use of the synchronized list (and we want to avoid using a
     * copy on write list for performance reasons).
     */
    override fun handleRequest(request: IqRequest<JibriIq>): IqProcessingResult {
        // TODO: we should be able to recognize the conference for a jibri IQ simply based on the `to` address.
        conferenceStore.getAllConferences().forEach { conference ->
            when (val result = conference.handleJibriRequest(request)) {
                is AcceptedWithResponse, is AcceptedWithNoResponse, is RejectedWithError -> return result
                else -> Unit // Proceed to the next conference
            }
        }

        // No conference accepted the request.
        logger.warn("Jibri IQ not accepted by any conference: ${request.iq.toXML()}")
        if (JicofoServices.jicofoServicesSingleton?.jibriDetector == null &&
            JicofoServices.jicofoServicesSingleton?.sipJibriDetector == null
        ) {
            logger.warn("No jibri detectors configured.")
        }
        return RejectedWithError(request, StanzaError.Condition.item_not_found)
    }
}
