/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
import org.jitsi.jicofo.EmptyConferenceStore
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.rayo.RayoIqProvider
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jivesoftware.smack.packet.id.StanzaIdUtil
import org.jxmpp.jid.Jid

class JigasiIqHandler(
    connections: Set<AbstractXMPPConnection>,
    private val jigasiDetector: JigasiDetector
) : AbstractIqHandler<RayoIqProvider.DialIq>(
    connections,
    RayoIqProvider.DialIq.ELEMENT_NAME,
    RayoIqProvider.NAMESPACE,
    setOf(IQ.Type.set)
) {
    var conferenceStore: ConferenceStore = EmptyConferenceStore()

    private val logger = createLogger()

    override fun handleRequest(request: IqRequest<RayoIqProvider.DialIq>): IqProcessingResult {
        val conferenceJid = request.iq.from.asEntityBareJidIfPossible()
            ?: return RejectedWithError(request, XMPPError.Condition.bad_request)

        val conference = conferenceStore.getConference(conferenceJid)
            ?: return RejectedWithError(request, XMPPError.Condition.item_not_found).also {
                logger.warn("Jigasi request for non-existent conference: $conferenceJid")
            }

        if (!conference.acceptJigasiRequest(request.iq.from)) {
            return RejectedWithError(request, XMPPError.Condition.forbidden).also {
                logger.warn("Rejecting jigasi request from ${request.iq.from}")
            }
        }

        logger.info("Accepted jigasi request from ${request.iq.from}: ${request.iq.toXML()}")

        TaskPools.ioPool.submit {
            inviteJigasi(request, conference.bridges.keys.mapNotNull { it.region }.toSet())
        }
        return AcceptedWithNoResponse()
    }

    /**
     * Invites a jigasi given a specific validated [request] from a participant. Handles jigasi instance selection and
     * retry logic. Sends an IQ response for the [request].
     */
    private fun inviteJigasi(
        request: IqRequest<RayoIqProvider.DialIq>,
        conferenceRegions: Set<String>,
        retryCount: Int = 2,
        exclude: List<Jid> = emptyList()
    ) {
        // Check if Jigasi is available
        val jigasiJid = jigasiDetector.selectSipJigasi(exclude, conferenceRegions) ?: run {
            logger.warn("Can not invite jigasi, no instances available available. Request: ${request.iq.toXML()}")
            request.connection.tryToSendStanza(
                IQ.createErrorResponse(
                    request.iq,
                    XMPPError.getBuilder(XMPPError.Condition.service_unavailable).build()
                )
            )
            return
        }

        // Forward the request to the selected Jigasi instance.
        val requestToJigasi = RayoIqProvider.DialIq(request.iq).apply {
            from = null
            to = jigasiJid
            stanzaId = StanzaIdUtil.newStanzaId()
        }
        val responseFromJigasi = try {
            request.connection.sendIqAndGetResponse(requestToJigasi)
        } catch (e: SmackException.NotConnectedException) {
            logger.error("Can not invite jigasi, XMPP not connected. Request: ${request.iq.toXML()}")
            return
        }

        when (responseFromJigasi) {
            null, is ErrorIQ -> {
                if (responseFromJigasi == null) {
                    logger.warn("Timed out waiting for a response from $jigasiJid")
                } else {
                    logger.warn("Received error from " + jigasiJid + ": " + responseFromJigasi.toXML())
                }

                if (retryCount > 0) {
                    logger.info("Will retry up to $retryCount more times.")
                    // Do not try the same instance again.
                    inviteJigasi(request, conferenceRegions, retryCount - 1, exclude + jigasiJid)
                } else {
                    val condition = if (responseFromJigasi == null) {
                        XMPPError.Condition.remote_server_timeout
                    } else {
                        XMPPError.Condition.undefined_condition
                    }
                    logger.warn("Giving up.")
                    request.connection.tryToSendStanza(
                        IQ.createErrorResponse(request.iq, XMPPError.getBuilder(condition))
                    )
                }
            }
            else -> {
                // Successful response from Jigasi, forward it as the response to the client.
                request.connection.tryToSendStanza(
                    responseFromJigasi.apply {
                        from = null
                        to = request.iq.from
                        stanzaId = request.iq.stanzaId
                    }
                )
                return
            }
        }
    }
}
