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

import org.jitsi.jicofo.ConferenceConfig
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.jibri.JibriSession.StateListener
import org.jitsi.jicofo.xmpp.IqProcessingResult
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.NotProcessed
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.jicofo.xmpp.IqRequest
import org.jitsi.jicofo.xmpp.muc.hasModeratorRights
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.logging2.Logger
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jitsi.xmpp.extensions.jibri.JibriIq.Action
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import java.lang.Exception
import org.jitsi.jicofo.util.ErrorResponse.create as error

/**
 * Base class shared between [JibriRecorder] (which can deal with only one [JibriSession]) and [JibriSipGateway]
 * (which is capable of handling multiple, simultaneous [JibriSession]s).
 *
 * @author Pawel Domas
 */
abstract class BaseJibri internal constructor(
    protected val conference: JitsiMeetConferenceImpl,
    parentLogger: Logger,
    val jibriDetector: JibriDetector
) : StateListener {

    protected val logger: Logger = parentLogger.createChildLogger(BaseJibri::class.simpleName)

    fun handleJibriRequest(request: JibriRequest): IqProcessingResult = if (accept(request.iq)) {
        logger.info("Accepted jibri request: ${request.iq.toXML()}")
        // Handle the request in the room's queue. This keeps the request from racing with e.g., member presence.
        val chatRoom = conference.chatRoom ?: run {
            logger.warn("No chat room found for conference ${conference.roomName}")
            return RejectedWithError(request.iq, StanzaError.Condition.internal_server_error)
        }

        chatRoom.queueXmppTask {
            val response = try {
                doHandleIQRequest(request.iq)
            } catch (e: Exception) {
                logger.warn("Failed to handle request: ${request.iq}", e)
                request.connection.tryToSendStanza(
                    IQ.createErrorResponse(request.iq, StanzaError.Condition.internal_server_error)
                )
                null
            }
            response?.let { request.connection.tryToSendStanza(it) }
        }
        AcceptedWithNoResponse()
    } else {
        NotProcessed()
    }

    /**
     * Returns the [JibriSession] associated with a specific [JibriIq] coming from a client in the conference.
     *
     * If the extending class can deal with only one [JibriSession] at a time it should return it. If it's
     * capable of handling multiple sessions then it should try to identify the session based on the information
     * contained in the [iq]. If it's unable to match any session it should return [iq].
     *
     * The purpose of having this method abstract is to share the logic for handling start and stop requests. For
     * example if there's an incoming stop request it will be handled if this method returns a valid [JibriSession]
     * instance. In case of a start request a new session will be created if this method returns `null`.
     *
     * @param iq the IQ originated from the Jitsi Meet participant (start or stop request)
     * @return [JibriSession] if there is any [JibriSession] currently active for given IQ.
     */
    protected abstract fun getJibriSessionForMeetIq(iq: JibriIq): JibriSession?

    /**
     * @return a list with all [JibriSession]s used by this instance.
     */
    abstract val jibriSessions: List<JibriSession>

    /**
     * This method will be called when a `start IQ` arrives from a Jitsi Meet participant and
     * [.getJibriSessionForMeetIq] returns `null`. The implementing class should allocate and store a new
     * [JibriSession]. Once a [JibriSession] is created it must be started by the implementing class.
     * @param iq the start request coming from a Jitsi Meet participant.
     * @return the response to be sent. It should be 'result' if a new session was started or 'error' otherwise.
     */
    protected abstract fun handleStartRequest(iq: JibriIq): IQ

    /**
     * Checks if the given [JibriIq] should be accepted by this instance. The IQ may originate either from a
     * participant in the conference, or from a Jibri instance. It should be accepted if:
     * 1a. It originates from a participant in the conference AND
     * 1b. Matches the type of this instance (recording/streaming vs SIP), OR
     * 2. Originates from a Jibri with which this instance has an active session.
     *
     * @return `true` if the IQ is to be accepted.
     */
    protected fun accept(iq: JibriIq): Boolean {
        // Process if it belongs to an active recording session
        val session = getJibriSessionForMeetIq(iq)
        if (session != null && session.accept(iq)) {
            return true
        }

        // Check if the implementation wants to deal with this IQ sub-type (recording/live-streaming vs SIP).
        if (!acceptType(iq)) {
            return false
        }

        return conference.roomName.equals(iq.from.asBareJid())
    }

    protected abstract fun acceptType(packet: JibriIq): Boolean

    /**
     * Handles an incoming Jibri IQ from either a jibri instance or a participant in the conference. This may block
     * waiting for a response over the network.
     *
     * @return the IQ to be sent back as a response ('result' or 'error').
     */
    private fun doHandleIQRequest(iq: JibriIq): IQ {
        logger.debug { "Jibri request. IQ: ${iq.toXML()}" }

        // Coming from a Jibri instance.
        val session = getJibriSessionForMeetIq(iq)
        if (session != null && session.accept(iq)) {
            return session.processJibriIqRequestFromJibri(iq)
        }

        if (ConferenceConfig.config.enableModeratorChecks) {
            verifyModeratorRole(iq)?.let {
                logger.warn("Ignored Jibri request from non-moderator.")
                return IQ.createErrorResponse(iq, it)
            }
        }

        return when (iq.action) {
            Action.START -> when (session) {
                null -> handleStartRequest(iq)
                else -> {
                    logger.info("Will not start a Jibri session, a session is already active")
                    error(
                        iq,
                        StanzaError.Condition.unexpected_request,
                        "Recording or live streaming is already enabled"
                    )
                }
            }
            Action.STOP -> when (session) {
                null -> {
                    logger.warn("Rejecting STOP request for an unknown session.: ${iq.toXML()}")
                    error(iq, StanzaError.Condition.item_not_found, "Unknown session")
                }
                else -> {
                    session.stop(iq.from)
                    IQ.createResultIQ(iq)
                }
            }
            Action.UNDEFINED, null -> {
                return error(iq, StanzaError.Condition.bad_request, "undefined action ${iq.toXML()}")
            }
        }
    }

    private fun verifyModeratorRole(iq: JibriIq): StanzaError? {
        val role = conference.getRoleForMucJid(iq.from)
        return when {
            // XXX do we need to keep the difference between `forbidden` and `not_allowed`?
            role == null -> StanzaError.getBuilder(StanzaError.Condition.forbidden).build()
            role.hasModeratorRights() -> null // no error
            else -> StanzaError.getBuilder(StanzaError.Condition.not_allowed).build()
        }
    }
}

typealias JibriRequest = IqRequest<JibriIq>
