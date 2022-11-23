/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.impl.protocol.xmpp.RegistrationListener
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.auth.ErrorFactory
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate

/**
 * Handles XMPP requests for a new conference ([ConferenceIq]).
 */
class ConferenceIqHandler(
    val xmppProvider: XmppProvider,
    val focusManager: FocusManager,
    val focusAuthJid: String,
    val isFocusAnonymous: Boolean,
    val authAuthority: AuthenticationAuthority?,
    val jigasiEnabled: Boolean
) : RegistrationListener, AbstractIqRequestHandler(
    ConferenceIq.ELEMENT,
    ConferenceIq.NAMESPACE,
    IQ.Type.set,
    IQRequestHandler.Mode.sync
) {
    private val connection = xmppProvider.xmppConnection
    private var breakoutAddress: DomainBareJid? = null
    private val logger = createLogger()

    init {
        xmppProvider.addRegistrationListener(this)
        registrationChanged(xmppProvider.isRegistered)
    }

    /** Handle a [ConferenceIq] synchronously and return a response. */
    fun handleConferenceIq(query: ConferenceIq): IQ {
        val response = ConferenceIq()
        val room = query.room ?: return IQ.createErrorResponse(
            query,
            StanzaError.from(StanzaError.Condition.bad_request, "No 'room' specified.").build()
        )

        logger.info("Focus request for room: $room")
        val roomExists = focusManager.getConference(room) != null

        // Authentication logic
        val error: IQ? = processExtensions(query, room, response, roomExists)
        if (error != null) {
            return error
        }

        var ready: Boolean = focusManager.conferenceRequest(room, query.propertiesMap)
        if (!isFocusAnonymous && authAuthority == null) {
            // Focus is authenticated system admin, so we let them in immediately. Focus will get OWNER anyway.
            ready = true
        }
        response.type = IQ.Type.result
        response.stanzaId = query.stanzaId
        response.from = query.to
        response.to = query.from
        response.room = query.room
        response.isReady = ready

        // Config
        response.focusJid = focusAuthJid

        // Authentication module enabled?
        if (authAuthority != null) {
            response.addProperty(ConferenceIq.Property("authentication", "true"))
            response.addProperty(ConferenceIq.Property("externalAuth", authAuthority.isExternal.toString()))
        } else {
            response.addProperty(ConferenceIq.Property("authentication", "false"))
        }

        if (jigasiEnabled) {
            response.addProperty(ConferenceIq.Property("sipGatewayEnabled", "true"))
        }
        return response
    }

    /**
     * Additional logic added for conference IQ processing like authentication.
     *
     * @param query <tt>ConferenceIq</tt> query
     * @param response <tt>ConferenceIq</tt> response which can be modified during this processing.
     * @param roomExists <tt>true</tt> if room mentioned in the <tt>query</tt> already exists.
     *
     * @return <tt>null</tt> if everything went ok or an error/response IQ
     * which should be returned to the user
     */
    private fun processExtensions(
        query: ConferenceIq,
        room: EntityBareJid,
        response: ConferenceIq?,
        roomExists: Boolean
    ): IQ? {
        val isBreakoutRoom = breakoutAddress != null && room.domain == breakoutAddress

        // Authentication. We do not perform authentication for breakout rooms, expecting the breakout room prosody
        // module to handle it.
        if (!isBreakoutRoom && authAuthority != null) {
            val authErrorOrResponse = authAuthority.processAuthentication(query, response)

            // Checks if authentication module wants to cancel further
            // processing and eventually returns its response
            if (authErrorOrResponse != null) {
                return authErrorOrResponse
            }
            // Only authenticated users are allowed to create new rooms
            if (!roomExists) {
                // If an associated breakout room exists and all members have left the main room, skip
                // authentication for the main room so users can go back to it.
                val breakoutRoomExists = focusManager.getConferences().any { conference ->
                    conference.chatRoom?.let { it.isBreakoutRoom && room.toString() == it.mainRoom } ?: false
                }
                if (!breakoutRoomExists && authAuthority.getUserIdentity(query.from) == null) {
                    // Error not authorized
                    return ErrorFactory.createNotAuthorizedError(query, "not authorized user domain")
                }
            }
        }

        return null
    }

    override fun handleIQRequest(iqRequest: IQ?): IQ? {
        if (iqRequest !is ConferenceIq) {
            return IQ.createErrorResponse(
                iqRequest, StanzaError.getBuilder(StanzaError.Condition.internal_server_error).build()
            ).also {
                logger.error("Received an unexpected IQ type: $iqRequest")
            }
        }

        // If the IQ comes from mod_client_proxy, parse and substitute the original sender's JID.
        val originalFrom = iqRequest.from
        iqRequest.from = parseJidFromClientProxyJid(XmppConfig.client.clientProxy, originalFrom)

        TaskPools.ioPool.execute {
            val response = handleConferenceIq(iqRequest).apply { to = originalFrom }

            try {
                connection.sendStanza(response)
            } catch (e: Exception) {
                logger.error("Failed to send response", e)
            }
        }

        return null
    }

    override fun registrationChanged(registered: Boolean) {
        if (!registered) {
            breakoutAddress = null
            return
        }

        try {
            val info = xmppProvider.discoverInfo(JidCreate.bareFrom(XmppConfig.client.xmppDomain))
            val breakoutAddressStr = info?.getIdentities("component", "breakout_rooms")?.firstOrNull()?.name

            if (breakoutAddressStr == null) {
                breakoutAddress = null
                logger.info("No breakout room component address configured.")
            } else {
                breakoutAddress = JidCreate.domainBareFrom(breakoutAddressStr)
                logger.info("Using breakout room component address: $breakoutAddress")
            }
        } catch (e: Exception) {
            logger.error("Could not discover breakout rooms component address.", e)
        }
    }
}
