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
package org.jitsi.jicofo.jibri

import org.apache.commons.lang3.StringUtils
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.jibri.JibriConfig.Companion.config
import org.jitsi.jicofo.jibri.JibriSession.StartException
import org.jitsi.jicofo.jibri.JibriSession.StartException.AllBusy
import org.jitsi.jicofo.jibri.JibriSession.StartException.NotAvailable
import org.jitsi.utils.logging2.Logger
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jitsi.xmpp.extensions.jibri.SipCallState
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import java.util.UUID
import kotlin.collections.HashMap
import org.jitsi.jicofo.util.ErrorResponse.create as error

/**
 * A Jibri SIP gateway that manages SIP calls for single conference. Relies on
 * the information provided by [JibriDetector] to tell whether any Jibris
 * are currently available and to select one for new [JibriSession]s
 * (JibriSession does the actual selection).
 *
 * @author Pawel Domas
 */
class JibriSipGateway(
    conference: JitsiMeetConferenceImpl,
    jibriDetector: JibriDetector,
    parentLogger: Logger
) : BaseJibri(
    conference,
    parentLogger,
    jibriDetector
) {
    /**
     * The SIP [JibriSession]s mapped by the SIP address.
     */
    private val sipSessions: MutableMap<String, JibriSession> = HashMap()

    /**
     * Accepts only [JibriIq] with a SIP address (packets without one are handled by JibriRecorder).
     */
    override fun acceptType(packet: JibriIq): Boolean = StringUtils.isNotBlank(packet.sipAddress)

    fun shutdown() {
        try {
            sipSessions.values.forEach { it.stop(null) }
        } finally {
            sipSessions.clear()
        }
    }

    override fun getJibriSessionForMeetIq(iq: JibriIq): JibriSession? = sipSessions[iq.sipAddress]
    override val jibriSessions: List<JibriSession>
        get() = ArrayList(sipSessions.values)

    override fun handleStartRequest(iq: JibriIq): IQ = if (StringUtils.isNotBlank(iq.sipAddress)) {
        val sessionId = UUID.randomUUID().toString()
        val jibriSession = JibriSession(
            this,
            conference.roomName,
            iq.from,
            config.pendingTimeout.seconds,
            config.numRetries,
            jibriDetector,
            false,
            iq.sipAddress,
            iq.displayName, null, null, sessionId, null,
            logger
        )
        sipSessions[iq.sipAddress] = jibriSession
        try {
            jibriSession.start()
            logger.info("Started Jibri session")
            JibriIq.createResult(iq, sessionId)
        } catch (exc: StartException) {
            val reason = exc.message
            logger.warn("Failed to start a Jibri session: $reason", exc)
            sipSessions.remove(iq.sipAddress)
            when (exc) {
                is AllBusy -> error(iq, StanzaError.Condition.resource_constraint, "all Jibris are busy")
                is NotAvailable -> error(iq, StanzaError.Condition.service_unavailable, "no Jibris available")
                else -> error(iq, StanzaError.Condition.internal_server_error, reason)
            }
        }
    } else {
        // Bad request - no SIP address
        error(iq, StanzaError.Condition.bad_request, "Stream ID is empty or undefined")
    }

    override fun onSessionStateChanged(
        jibriSession: JibriSession,
        newStatus: JibriIq.Status,
        failureReason: JibriIq.FailureReason?
    ) {
        if (!sipSessions.containsValue(jibriSession)) {
            logger.error("onSessionStateChanged for unknown session: $jibriSession")
            return
        }
        publishJibriSipCallState(jibriSession, newStatus, failureReason)
        if (JibriIq.Status.OFF == newStatus) {
            val sipAddress = jibriSession.sipAddress
            sipSessions.remove(sipAddress)
            logger.info("Removing SIP call: $sipAddress")
        }
    }

    /**
     * Updates the status of specific a [JibriSession]. Jicofo adds multiple [SipCallState] MUC presence extensions
     * to its presence. One for each active SIP Jibri session.
     * @param session the session for which the new status will be set
     * @param newStatus the new status
     * @param failureReason option error for OFF state
     */
    private fun publishJibriSipCallState(
        session: JibriSession,
        newStatus: JibriIq.Status,
        failureReason: JibriIq.FailureReason?
    ) {
        val sipCallState = SipCallState().apply {
            setState(newStatus)
            this.failureReason = failureReason
            sipAddress = session.sipAddress
            sessionId = session.sessionId
        }
        logger.info("Publishing new state: ${session.sipAddress} ${sipCallState.toXML()}")

        // Publish that in the presence
        conference.chatRoom?.setPresenceExtension(sipCallState) ?: logger.warn("chatRoom is null")
    }
}
