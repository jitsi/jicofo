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
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.rayo.DialIq
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.id.StandardStanzaIdSource
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.atomic.AtomicInteger

class JigasiIqHandler(
    connections: Set<AbstractXMPPConnection>,
    private val conferenceStore: ConferenceStore,
    private val jigasiDetector: JigasiDetector
) : AbstractIqHandler<DialIq>(
    connections,
    DialIq.ELEMENT,
    DialIq.NAMESPACE,
    setOf(IQ.Type.set)
) {
    private val logger = createLogger()

    private val stanzaIdSource = stanzaIdSourceFactory.constructStanzaIdSource()

    private val stats = Stats()
    val statsJson: OrderedJsonObject
        get() = stats.toJson()

    override fun handleRequest(request: IqRequest<DialIq>): IqProcessingResult {
        val conferenceJid = request.iq.from.asEntityBareJidIfPossible()
            ?: return RejectedWithError(request, StanzaError.Condition.bad_request).also {
                logger.warn("Rejected request with invalid conferenceJid: ${request.iq.from}")
                Stats.rejectedRequests.inc()
            }

        val conference = conferenceStore.getConference(conferenceJid)
            // search for visitor room with that jid, maybe it's an invite from a visitor
            ?: conferenceStore.getAllConferences().find { c -> c.visitorRoomsJids.contains(conferenceJid) }
            ?: return RejectedWithError(request, StanzaError.Condition.item_not_found).also {
                logger.warn("Rejected request for non-existent conference: $conferenceJid")
                Stats.rejectedRequests.inc()
            }

        if (!conference.acceptJigasiRequest(request.iq.from)) {
            return RejectedWithError(request, StanzaError.Condition.forbidden).also {
                logger.warn("Rejected request from unauthorized user: ${request.iq.from}")
                Stats.rejectedRequests.inc()
            }
        }

        val roomNameHeader = request.iq.getHeader("JvbRoomName")
        if (roomNameHeader != null && JidCreate.entityBareFrom(roomNameHeader) != conference.roomName) {
            return RejectedWithError(request, StanzaError.Condition.forbidden).also {
                logger.warn(
                    "Rejecting request with non-matching JvbRoomName: from=${request.iq.from} " +
                        ", roomName=${conference.roomName}, JvbRoomName=$roomNameHeader"
                )
                Stats.rejectedRequests.inc()
            }
        }

        logger.info("Accepted jigasi request from ${request.iq.from}: ${request.iq.toXML()}")
        Stats.acceptedRequests.inc()

        TaskPools.ioPool.execute {
            try {
                inviteJigasi(request, conference)
            } catch (e: Exception) {
                logger.warn("Failed to invite jigasi", e)
                request.connection.tryToSendStanza(
                    IQ.createErrorResponse(request.iq, StanzaError.Condition.internal_server_error)
                )
            }
        }
        return AcceptedWithNoResponse()
    }

    /**
     * Invites a jigasi given a specific validated [request] from a participant. Handles jigasi instance selection and
     * retry logic. Sends an IQ response for the [request].
     */
    private fun inviteJigasi(
        request: IqRequest<DialIq>,
        conference: JitsiMeetConference,
        retryCount: Int = 2,
        exclude: List<Jid> = emptyList()
    ) {
        val selector = if (request.iq.destination == "jitsi_meet_transcribe") {
            if (conference.hasTranscriber()) {
                logger.warn("Request failed, transcriber already available: ${request.iq.toXML()}")
                IQ.createErrorResponse(
                    request.iq,
                    StanzaError.getBuilder(StanzaError.Condition.conflict).build()
                )
                return
            }
            jigasiDetector::selectTranscriber
        } else {
            jigasiDetector::selectSipJigasi
        }

        // Check if Jigasi is available
        val jigasiJid = selector(exclude, conference.bridgeRegions) ?: run {
            logger.warn("Request failed, no instances available: ${request.iq.toXML()}")
            request.connection.tryToSendStanza(
                IQ.createErrorResponse(
                    request.iq,
                    StanzaError.getBuilder(StanzaError.Condition.service_unavailable).build()
                )
            )
            stats.noInstanceAvailable()
            return
        }

        logger.info("Selected $jigasiJid (request from ${request.iq.from})")
        // Forward the request to the selected Jigasi instance.
        val requestToJigasi = DialIq(request.iq).apply {
            from = null
            to = jigasiJid
            stanzaId = stanzaIdSource.newStanzaId
        }
        val responseFromJigasi = try {
            jigasiDetector.xmppConnection.sendIqAndGetResponse(requestToJigasi)
        } catch (e: SmackException.NotConnectedException) {
            logger.error("Request failed,  XMPP not connected: ${request.iq.toXML()}")
            stats.xmppNotConnected()
            return
        }

        if (responseFromJigasi == null || responseFromJigasi.error != null) {
            // Timeout or error.
            if (responseFromJigasi == null) {
                logger.warn("Jigasi instance timed out: $jigasiJid")
                Stats.singleInstanceTimeouts.inc()
            } else {
                logger.warn("Jigasi instance returned error ($jigasiJid): ${responseFromJigasi.toXML()}")
                Stats.singleInstanceErrors.inc()
            }

            if (retryCount > 0) {
                logger.info("Will retry up to $retryCount more times.")
                Stats.retries.inc()
                // Do not try the same instance again.
                inviteJigasi(request, conference, retryCount - 1, exclude + jigasiJid)
            } else {
                val condition = if (responseFromJigasi == null) {
                    StanzaError.Condition.remote_server_timeout
                } else {
                    StanzaError.Condition.undefined_condition
                }
                logger.warn("Request failed, all instances failed.")
                stats.allInstancesFailed()
                request.connection.tryToSendStanza(
                    IQ.createErrorResponse(request.iq, StanzaError.getBuilder(condition).build())
                )
            }

            return
        }

        logger.info("Response from jigasi: ${responseFromJigasi.toXML()}")
        // Successful response from Jigasi, forward it as the response to the client.
        request.connection.tryToSendStanza(
            responseFromJigasi.apply {
                from = null
                to = request.iq.from
                stanzaId = request.iq.stanzaId
            }
        )
    }

    companion object {
        private val stanzaIdSourceFactory = StandardStanzaIdSource.Factory()
    }

    class Stats {
        /** User requests which failed due to no jigasi instances being available. */
        private val requestsFailedNoInstanceAvailable = AtomicInteger()

        /** User requests which failed due to the jigasi XMPP connection not being connected. */
        private val requestsFailedXmppNotConnected = AtomicInteger()

        /** User requests which failed because all jigasi instances (up to the max retry count) failed. */
        private val requestsFailedAllInstancesFailed = AtomicInteger()

        fun noInstanceAvailable() = requestsFailedNoInstanceAvailable.incrementAndGet()
        fun xmppNotConnected() = requestsFailedXmppNotConnected.incrementAndGet()
        fun allInstancesFailed() = requestsFailedAllInstancesFailed.incrementAndGet()

        fun toJson() = statsJson().apply {
            this["requests_failed_no_instance"] = requestsFailedNoInstanceAvailable.get()
            this["requests_failed_xmpp_not_connected"] = requestsFailedXmppNotConnected.get()
        }

        companion object {
            private const val PREFIX = "jigasi_iq_handler"
            val rejectedRequests = JicofoMetricsContainer.instance.registerCounter(
                "${PREFIX}_rejected_requests",
                "User requests which were rejected (e.g. not authorized, bad request)."
            )
            val acceptedRequests = JicofoMetricsContainer.instance.registerCounter(
                "${PREFIX}_accepted_requests",
                "User requests which were accepted."
            )
            val retries = JicofoMetricsContainer.instance.registerCounter(
                "${PREFIX}_retries",
                "Requests retried with a different jigasi instance."
            )
            val singleInstanceErrors = JicofoMetricsContainer.instance.registerCounter(
                "${PREFIX}_instance_errors",
                "Errors received from jigasi instances."
            )
            val singleInstanceTimeouts = JicofoMetricsContainer.instance.registerCounter(
                "${PREFIX}_instance_timeouts",
                "Timeouts for requests sent to jigasi instances."
            )

            fun statsJson() = OrderedJsonObject().apply {
                put("rejected_requests", rejectedRequests.get())
                put("accepted_requests", acceptedRequests.get())
                put("retries", retries.get())
                put("instance_errors", singleInstanceErrors.get())
                put("instance_timeout", singleInstanceTimeouts.get())
            }
        }
    }
}

private fun JitsiMeetConference.hasTranscriber(): Boolean = this.chatRoom?.members?.any { it.isTranscriber } ?: false
