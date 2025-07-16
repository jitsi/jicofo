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
package org.jitsi.jicofo.jibri

import org.apache.commons.lang3.StringUtils
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.jibri.JibriConfig.Companion.config
import org.jitsi.jicofo.jibri.JibriSession.StartException
import org.jitsi.jicofo.jibri.JibriSession.StartException.AllBusy
import org.jitsi.jicofo.jibri.JibriSession.StartException.NotAvailable
import org.jitsi.utils.logging2.Logger
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jitsi.xmpp.extensions.jibri.JibriIq.RecordingMode
import org.jitsi.xmpp.extensions.jibri.JibriIq.Status
import org.jitsi.xmpp.extensions.jibri.RecordingStatus
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import java.util.*
import org.jitsi.jicofo.util.ErrorResponse.create as error

/**
 * Handles conference recording through Jibri.
 * Waits for updates from [JibriDetector] about recorder instance
 * availability and publishes that information in Jicofo's MUC presence.
 * Handles incoming Jibri IQs coming from conference moderator to
 * start/stop the recording.
 *
 * @author Pawel Domas
 * @author Sam Whited
 */
class JibriRecorder(
    conference: JitsiMeetConferenceImpl,
    jibriDetector: JibriDetector,
    parentLogger: Logger
) : BaseJibri(
    conference,
    parentLogger,
    jibriDetector
) {
    /**
     * The current recording session or <tt>null</tt>.
     */
    private var jibriSession: JibriSession? = null

    fun shutdown() {
        jibriSession?.let {
            TaskPools.ioPool.submit { it.stop(null) }
        }
        jibriSession = null
    }

    /**
     * Accepts only [JibriIq] without a SIP address.
     */
    override fun acceptType(packet: JibriIq): Boolean {
        // the packet cannot contain a SIP address (must be handled
        // by JibriSipGateway)
        return StringUtils.isBlank(packet.sipAddress)
    }

    /**
     * [JibriRecorder] has at most one jibri session.
     */
    override fun getJibriSessionForMeetIq(iq: JibriIq): JibriSession? = jibriSession

    /**
     * {@inheritDoc}
     */
    override val jibriSessions: List<JibriSession>
        get() = jibriSession?.let { listOf(it) } ?: emptyList()

    /**
     * Handles a request to start a jibri session coming from a client in the conference and returns the response to
     * be sent.
     *
     * [BaseJibri] has checked that there is no recording session currently active.
     */
    override fun handleStartRequest(iq: JibriIq): IQ {
        val streamIdIsEmpty = StringUtils.isBlank(iq.streamId)

        return if (streamIdIsEmpty && iq.recordingMode != RecordingMode.FILE) {
            // Stream ID is mandatory unless we're recording to a file.
            error(iq, StanzaError.Condition.bad_request, "Stream ID is empty or undefined")
        } else if (!streamIdIsEmpty && iq.recordingMode == RecordingMode.FILE) {
            // Stream ID should not be provided with requests to record to a file.
            error(iq, StanzaError.Condition.bad_request, "Stream ID is provided for a FILE recording.")
        } else {
            val sessionId = UUID.randomUUID().toString()
            try {
                val jibriSession = JibriSession(
                    this,
                    conference.roomName,
                    iq.from,
                    config.pendingTimeout.seconds,
                    config.numRetries,
                    jibriDetector,
                    false, null, iq.displayName, iq.streamId, iq.youtubeBroadcastId, sessionId, iq.appData,
                    logger
                )
                this.jibriSession = jibriSession
                jibriSession.start()
                logger.info("Started Jibri session")
                JibriIq.createResult(iq, sessionId)
            } catch (exc: StartException) {
                jibriSession = null
                when (exc) {
                    is AllBusy -> {
                        logger.info("Failed to start a Jibri session, all Jibris were busy")
                        error(iq, StanzaError.Condition.resource_constraint, "all Jibris are busy")
                    }
                    is NotAvailable -> {
                        logger.info("Failed to start a Jibri session, no Jibris available")
                        error(iq, StanzaError.Condition.service_unavailable, "no Jibris available")
                    }
                    else -> {
                        logger.warn("Failed to start a Jibri session: ${exc.message}", exc)
                        error(iq, StanzaError.Condition.internal_server_error, exc.message)
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun onSessionStateChanged(
        jibriSession: JibriSession,
        newStatus: Status,
        failureReason: JibriIq.FailureReason?
    ) {
        if (this.jibriSession !== jibriSession) {
            logger.error("onSessionStateChanged for unknown session: $jibriSession")
            return
        }
        publishJibriRecordingStatus(newStatus, failureReason)
        if (newStatus == Status.OFF) {
            this.jibriSession = null
        }
    }

    /**
     * Sends the new jibri status as presence in the conference MUC.
     */
    private fun publishJibriRecordingStatus(newStatus: Status, failureReason: JibriIq.FailureReason?) {
        logger.info("Got jibri status $newStatus and failure $failureReason")
        val jibriSession = jibriSession
        if (jibriSession == null) {
            // It's possible back-to-back 'stop' requests could be received, and while processing the result of the
            // first we set jibriSession // to null, so in the processing of the second one it will already be null.
            logger.info("Jibri session was already cleaned up, not sending new status")
            return
        }

        val recordingStatus = RecordingStatus().apply {
            status = newStatus
            this.failureReason = failureReason
            sessionId = jibriSession.sessionId
            when (newStatus) {
                Status.ON -> initiator = jibriSession.initiator
                Status.OFF -> initiator = jibriSession.terminator
                else -> Unit
            }

            jibriSession.recordingMode.let {
                if (it != RecordingMode.UNDEFINED) {
                    recordingMode = it
                }
            }
        }
        logger.info(
            "Publishing new jibri-recording-status: ${recordingStatus.toXML()} in: ${conference.roomName}"
        )

        conference.setPresenceExtension(recordingStatus)
    }
}
