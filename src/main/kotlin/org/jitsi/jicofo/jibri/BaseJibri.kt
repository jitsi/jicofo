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

import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.JitsiMeetConferenceImpl
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.jibri.JibriSession.StateListener
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jitsi.xmpp.extensions.jibri.JibriIq.Action
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jitsi.jicofo.util.ErrorResponse.create as error

/**
 * Base class shared between [JibriRecorder] (which can deal with only one [JibriSession]) and [JibriSipGateway]
 * (which is capable of handling multiple, simultaneous [JibriSession]s).
 *
 * @author Pawel Domas
 */
abstract class BaseJibri internal constructor(
    protected val conference: JitsiMeetConferenceImpl,
    private val xmppProvider: XmppProvider,
    parentLogger: Logger,
    val jibriDetector: JibriDetector
) : StateListener {

    protected val connection: AbstractXMPPConnection = xmppProvider.xmppConnection

    private val incomingIqQueue = PacketQueue<JibriRequest>(
        50,
        true,
        "jibri-iq-queue-${conference.roomName.localpart}",
        { jibriRequest ->
            val response = doHandleIQRequest(jibriRequest.iq)
            try {
                jibriRequest.connection.sendStanza(response)
            } catch (e: SmackException.NotConnectedException) {
                logger.warn("Failed to send response, smack is not connected.")
            } catch (e: InterruptedException) {
                logger.warn("Failed to send response, interrupted.")
            }

            true
        },
        TaskPools.ioPool
    )

    private val clientIqHandler: JibriSessionIqHandler = object : JibriSessionIqHandler {
        override fun accept(iq: JibriIq) = this@BaseJibri.accept(iq)
        override fun handleIQRequest(iq: JibriIq) = incomingIqQueue.add(JibriRequest(iq, connection))
    }

    protected val logger: Logger = parentLogger.createChildLogger(BaseJibri::class.simpleName)

    init {
        xmppProvider.addJibriIqHandler(clientIqHandler)
    }

    /**
     * Returns the [JibriSession] associated with a specific [JibriIq] coming from a client in the confernce.
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
     * Method called by [JitsiMeetConferenceImpl] when the conference is
     * being stopped.
     */
    open fun dispose() = xmppProvider.removeJibriIqHandler(clientIqHandler)

    /**
     * Checks if the given [JibriIq] should be accepted by this instance. The IQ may originate either from a
     * participant in the conference, or from a Jibri instance. It should be accepted if:
     * 1a. It originates from a participant in the conference AND
     * 1b. Matches the type of this instance (recording/streaming vs SIP), OR
     * 2. Originates from a Jibri with which this instance has an active seesion.
     *
     * @return `true` if the IQ is to be accepted.
     */
    fun accept(iq: JibriIq): Boolean {
        // Process if it belongs to an active recording session
        val session = getJibriSessionForMeetIq(iq)
        if (session != null && session.accept(iq)) {
            return true
        }

        // Check if the implementation wants to deal with this IQ sub-type (recording/live-streaming vs SIP).
        if (!acceptType(iq)) {
            return false
        }
        val from = iq.from
        val roomName = from.asBareJid()
        if (!conference.roomName.equals(roomName)) {
            return false
        }
        val chatMember = conference.findMember(from)
        if (chatMember == null) {
            logger.warn("Chat member not found for: $from")
            return false
        }
        return true
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

        // Process if it belongs to an active recording session
        val session = getJibriSessionForMeetIq(iq)
        if (session != null && session.accept(iq)) {
            return session.processJibriIqRequestFromJibri(iq)
        }
        if (iq.action == Action.UNDEFINED) {
            return error(iq, XMPPError.Condition.bad_request, "undefined action")
        }

        verifyModeratorRole(iq)?.let {
            logger.warn("Ignored Jibri request from non-moderator.")
            return IQ.createErrorResponse(iq, it)
        }

        return when {
            iq.action == Action.START && session == null -> handleStartRequest(iq)
            iq.action == Action.START && session != null -> {
                // If there's a session active, we know there are Jibri's connected
                // (so it isn't XMPPError.Condition.service_unavailable), so it
                // must be that they're all busy.
                logger.info("Failed to start a Jibri session, all Jibris were busy")
                error(iq, XMPPError.Condition.resource_constraint, "all Jibris are busy")
            }
            iq.action == Action.STOP && session != null -> {
                session.stop(iq.from)
                IQ.createResultIQ(iq)
            }
            else -> {
                logger.warn("Discarded jibri IQ with an unknown action: ${iq.toXML()}")
                error(iq, XMPPError.Condition.bad_request, "Unable to handle ${iq.action}")
            }
        }
    }

    private fun verifyModeratorRole(iq: JibriIq): XMPPError? {
        val role = conference.getRoleForMucJid(iq.from)
        return when {
            // XXX do we need to keep the difference between `forbidden` and `not_allowed`?
            role == null -> XMPPError.getBuilder(XMPPError.Condition.forbidden).build()
            role.hasModeratorRights() -> null // no error
            else -> XMPPError.getBuilder(XMPPError.Condition.not_allowed).build()
        }
    }

    protected fun generateSessionId(): String = Utils.generateSessionId(SESSION_ID_LENGTH)

    companion object {
        /**
         * The length of the session id field we generate to uniquely identify a Jibri session.
         */
        const val SESSION_ID_LENGTH = 16
    }
}

internal data class JibriRequest(val iq: JibriIq, val connection: XMPPConnection)
