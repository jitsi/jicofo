/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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
import org.jitsi.jicofo.JitsiMeetConferenceImpl.MuteResult
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jitsimeet.MuteIq
import org.jitsi.xmpp.extensions.jitsimeet.MuteVideoIq
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jxmpp.jid.Jid

class AudioMuteIqHandler(
    connections: Set<AbstractXMPPConnection>,
    private val conferenceStore: ConferenceStore
) :
    AbstractIqHandler<MuteIq>(
        connections,
        MuteIq.ELEMENT_NAME,
        MuteIq.NAMESPACE,
        setOf(IQ.Type.set),
        IQRequestHandler.Mode.sync
    ) {
    override fun handleRequest(request: IqRequest<MuteIq>): IqProcessingResult {
        return handleRequest(
            MuteRequest(
                request.iq,
                request.connection,
                conferenceStore,
                request.iq.mute,
                request.iq.jid,
                MediaType.AUDIO
            )
        )
    }
}

class VideoMuteIqHandler(
    connections: Set<AbstractXMPPConnection>,
    private val conferenceStore: ConferenceStore
) :
    AbstractIqHandler<MuteVideoIq>(
        connections,
        MuteVideoIq.ELEMENT_NAME,
        MuteVideoIq.NAMESPACE,
        setOf(IQ.Type.set),
        IQRequestHandler.Mode.sync
    ) {
    override fun handleRequest(request: IqRequest<MuteVideoIq>): IqProcessingResult {
        return handleRequest(
            MuteRequest(
                request.iq,
                request.connection,
                conferenceStore,
                request.iq.mute,
                request.iq.jid,
                MediaType.VIDEO
            )
        )
    }
}

private val logger = LoggerImpl("org.jitsi.jicofo.xmpp.MuteIqHandler")

private fun handleRequest(request: MuteRequest): IqProcessingResult {
    if (request.doMute == null || request.jidToMute == null) {
        logger.warn("Mute request missing required fields: ${request.iq.toXML()}")
        return RejectedWithError(request.iq, XMPPError.Condition.bad_request)
    }

    val conference = request.conferenceStore.getConference(request.iq.from.asEntityBareJidIfPossible())
        ?: return RejectedWithError(request.iq, XMPPError.Condition.item_not_found).also {
            logger.warn("Mute request for unknown conference: ${request.iq.toXML()}")
        }

    TaskPools.ioPool.submit {
        when (conference.handleMuteRequest(request.iq.from, request.jidToMute, request.doMute, request.mediaType)) {
            MuteResult.SUCCESS -> {
                request.connection.tryToSendStanza(IQ.createResultIQ(request.iq))
                // If this was a remove mute, notify the participant that was muted.
                if (request.iq.from != request.jidToMute) {
                    request.connection.tryToSendStanza(
                        if (request.mediaType == MediaType.AUDIO)
                            MuteIq().apply {
                                actor = request.iq.from
                                type = IQ.Type.set
                                to = request.jidToMute
                                mute = request.doMute
                            }
                        else
                            MuteVideoIq().apply {
                                actor = request.iq.from
                                type = IQ.Type.set
                                to = request.jidToMute
                                mute = request.doMute
                            }
                    )
                }
            }
            MuteResult.NOT_ALLOWED -> request.connection.tryToSendStanza(
                IQ.createErrorResponse(request.iq, XMPPError.getBuilder(XMPPError.Condition.not_allowed))
            )
            MuteResult.ERROR -> request.connection.tryToSendStanza(
                IQ.createErrorResponse(request.iq, XMPPError.getBuilder(XMPPError.Condition.internal_server_error))
            )
        }
    }

    return AcceptedWithNoResponse()
}

private data class MuteRequest(
    val iq: IQ,
    val connection: AbstractXMPPConnection,
    val conferenceStore: ConferenceStore,
    val doMute: Boolean?,
    val jidToMute: Jid?,
    val mediaType: MediaType
)
