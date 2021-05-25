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
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.rayo.RayoIqProvider
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jivesoftware.smack.packet.id.StanzaIdUtil
import org.jxmpp.jid.Jid
import java.util.concurrent.atomic.AtomicInteger

class JigasiIqHandler(
    connections: Set<AbstractXMPPConnection>,
    private val conferenceStore: ConferenceStore,
    private val jigasiDetector: JigasiDetector
) : AbstractIqHandler<RayoIqProvider.DialIq>(
    connections,
    RayoIqProvider.DialIq.ELEMENT_NAME,
    RayoIqProvider.NAMESPACE,
    setOf(IQ.Type.set)
) {
    private val logger = createLogger()

    private val stats = Stats()
    val statsJson = stats.toJson()

    override fun handleRequest(request: IqRequest<RayoIqProvider.DialIq>): IqProcessingResult {
        val conferenceJid = request.iq.from.asEntityBareJidIfPossible()
            ?: return RejectedWithError(request, XMPPError.Condition.bad_request).also {
                logger.warn("Rejected request with invalid conferenceJid: ${request.iq.from}")
                stats.requestRejected()
            }

        val conference = conferenceStore.getConference(conferenceJid)
            ?: return RejectedWithError(request, XMPPError.Condition.item_not_found).also {
                logger.warn("Rejected request for non-existent conference: $conferenceJid")
                stats.requestRejected()
            }

        if (!conference.acceptJigasiRequest(request.iq.from)) {
            return RejectedWithError(request, XMPPError.Condition.forbidden).also {
                logger.warn("Rejected request from unauthorized user: ${request.iq.from}")
                stats.requestRejected()
            }
        }

        logger.info("Accepted jigasi request from ${request.iq.from}: ${request.iq.toXML()}")
        stats.requestAccepted()

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
            logger.warn("Request failed, no instances available: ${request.iq.toXML()}")
            request.connection.tryToSendStanza(
                IQ.createErrorResponse(
                    request.iq,
                    XMPPError.getBuilder(XMPPError.Condition.service_unavailable).build()
                )
            )
            stats.noInstanceAvailable()
            return
        }

        // Forward the request to the selected Jigasi instance.
        val requestToJigasi = RayoIqProvider.DialIq(request.iq).apply {
            from = null
            to = jigasiJid
            stanzaId = StanzaIdUtil.newStanzaId()
        }
        val responseFromJigasi = try {
            jigasiDetector.xmppConnection.sendIqAndGetResponse(requestToJigasi)
        } catch (e: SmackException.NotConnectedException) {
            logger.error("Request failed,  XMPP not connected: ${request.iq.toXML()}")
            stats.xmppNotConnected()
            return
        }

        when (responseFromJigasi) {
            null, is ErrorIQ -> {
                if (responseFromJigasi == null) {
                    logger.warn("Jigasi instance timed out: $jigasiJid")
                    stats.singleInstanceTimeout()
                } else {
                    logger.warn("Jigasi instance returned error ($jigasiJid): ${responseFromJigasi.toXML()}")
                    stats.singleInstanceError()
                }

                if (retryCount > 0) {
                    logger.info("Will retry up to $retryCount more times.")
                    stats.retry()
                    // Do not try the same instance again.
                    inviteJigasi(request, conferenceRegions, retryCount - 1, exclude + jigasiJid)
                } else {
                    val condition = if (responseFromJigasi == null) {
                        XMPPError.Condition.remote_server_timeout
                    } else {
                        XMPPError.Condition.undefined_condition
                    }
                    logger.warn("Request failed, all instances failed.")
                    stats.allInstancesFailed()
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

    class Stats {
        /** User requests which were rejected (e.g. not authorized, bad request). */
        private val rejectedRequests = AtomicInteger()
        /** User requests which were accepted. */
        private val acceptedRequests = AtomicInteger()
        /** User requests which failed due to no jigasi instances being available. */
        private val requestsFailedNoInstanceAvailable = AtomicInteger()
        /** User requests which failed due to the jigasi XMPP connection not being connected. */
        private val requestsFailedXmppNotConnected = AtomicInteger()
        /** User requests which failed because all jigasi instances (up to the max retry count) failed. */
        private val requestsFailedAllInstancesFailed = AtomicInteger()
        /** Errors received from jigasi instances. */
        private val singleInstanceErrors = AtomicInteger()
        /** Timeouts for requests send to jigasi instances. */
        private val singleInstanceTimeouts = AtomicInteger()
        /** A request was retried with a different jigasi instance. */
        private val retries = AtomicInteger()

        fun requestRejected() = rejectedRequests.incrementAndGet()
        fun requestAccepted() = acceptedRequests.incrementAndGet()
        fun noInstanceAvailable() = requestsFailedNoInstanceAvailable.incrementAndGet()
        fun xmppNotConnected() = requestsFailedXmppNotConnected.incrementAndGet()
        fun allInstancesFailed() = requestsFailedAllInstancesFailed.incrementAndGet()
        fun singleInstanceError() = singleInstanceErrors.incrementAndGet()
        fun singleInstanceTimeout() = singleInstanceTimeouts.incrementAndGet()
        fun retry() = retries.incrementAndGet()

        fun toJson() = OrderedJsonObject().apply {
            this["rejected_requests"] = rejectedRequests.get()
            this["accepted_requests"] = acceptedRequests.get()
            this["requests_failed_no_instance"] = requestsFailedNoInstanceAvailable.get()
            this["requests_failed_xmpp_not_connected"] = requestsFailedXmppNotConnected.get()
            this["instance_errors"] = singleInstanceErrors.get()
            this["instance_timeout"] = singleInstanceTimeouts.get()
            this["retries"] = retries.get()
        }
    }
}
