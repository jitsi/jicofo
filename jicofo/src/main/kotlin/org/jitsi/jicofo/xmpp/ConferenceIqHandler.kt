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

import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.auth.ErrorFactory
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jwt.JitsiToken
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import java.lang.Boolean.parseBoolean
import org.jitsi.jicofo.visitors.VisitorsConfig.Companion.config as visitorsConfig

/**
 * Handles XMPP requests for a new conference ([ConferenceIq]).
 */
class ConferenceIqHandler(
    val xmppProvider: XmppProvider,
    val focusManager: FocusManager,
    val focusAuthJid: String,
    val authAuthority: AuthenticationAuthority?,
    val jigasiEnabled: Boolean,
    val visitorsManager: VisitorsManager
) : XmppProvider.Listener, AbstractIqRequestHandler(
    ConferenceIq.ELEMENT,
    ConferenceIq.NAMESPACE,
    IQ.Type.set,
    IQRequestHandler.Mode.sync
) {
    private val connection = xmppProvider.xmppConnection
    private var breakoutAddress: DomainBareJid? = null
    private val logger = createLogger()

    init {
        xmppProvider.addListener(this)
        registrationChanged(xmppProvider.registered)
        componentsChanged(xmppProvider.components)
    }

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["breakout_address"] = breakoutAddress.toString()
            this["focus_auth_jid"] = focusAuthJid
            this["jigasi_enabled"] = jigasiEnabled
            this["auth_authority"] = authAuthority?.javaClass?.simpleName ?: "null"
        }

    /** Handle a [ConferenceIq] synchronously and return a response. */
    fun handleConferenceIq(query: ConferenceIq): IQ {
        val room = query.room ?: return IQ.createErrorResponse(
            query,
            StanzaError.from(StanzaError.Condition.bad_request, "No 'room' specified.").build()
        )
        val token = parseToken(query.token)
        val userId = token?.context?.user?.id
        val groupId = token?.context?.group

        val response = ConferenceIq().apply {
            type = IQ.Type.result
            stanzaId = query.stanzaId
            from = query.to
            to = query.from
            this.room = query.room
            focusJid = focusAuthJid
        }

        logger.info("Conference request for room $room, from ${query.from}, token=${query.token != null}")
        logger.debug { "User ID: $userId, Group ID: $groupId" }
        conferenceRequestCounter.inc()
        var conference = focusManager.getConference(room)
        val roomExists = conference != null
        if (!roomExists) {
            newConferenceRequestCounter.inc()
            if (!parseBoolean(query.propertiesMap.getOrDefault("rtcstatsEnabled", "true"))) {
                logger.info("New room with rtcstats disabled: $room")
            }
        }

        // Authentication logic
        val error: IQ? = processExtensions(query, room, response, roomExists)
        if (error != null) {
            return error
        }

        val visitorSupported = query.properties.any { it.name == "visitors-version" }
        val visitorRequested = query.properties.any { it.name == "visitor" && it.value == "true" }
        var visitorsLive = conference?.chatRoom?.visitorsLive ?: false
        logger.debug {
            "visitorSupported=$visitorSupported, visitorRequested=$visitorRequested, visitorsLive=$visitorsLive"
        }
        // Here we return early without creating a conference.
        if (visitorSupported && visitorRequested && visitorsConfig.enableLiveRoom && !visitorsLive) {
            logger.debug("Sending to queue")
            response.isReady = false
            response.addProperty(ConferenceIq.Property("live", "false"))
            return response
        }

        // If the conference didn't exist previously, it will be created and the MUC will be joined here (blocking).
        conference = focusManager.conferenceRequest(room, query.propertiesMap)
        response.isReady = conference.isStarted && (conference.chatRoom?.isJoined == true)

        // We've now joined the MUC and room metadata has been set.
        visitorsLive = conference.chatRoom?.visitorsLive ?: false
        val visitorsSupported = visitorsManager.enabled &&
            (conference.chatRoom?.visitorsEnabled == true || !visitorsConfig.requireMucConfigFlag)
        response.addProperty("visitors-supported", visitorsSupported.toString())
        val allowedInMainRoom = conference.chatRoom?.isAllowedInMainRoom(userId, groupId) == true
        val preferredInMainRoom = conference.chatRoom?.isPreferredInMainRoom(userId, groupId) == true

        logger.debug {
            "allowedInMainRoom=$allowedInMainRoom, preferredInMainRoom=$preferredInMainRoom, visitorsLive=$visitorsLive"
        }
        if (visitorsConfig.enableLiveRoom && !allowedInMainRoom && !visitorsLive && visitorSupported) {
            logger.debug("Sending to queue")
            response.isReady = false
            response.addProperty(ConferenceIq.Property("live", "false"))
            return response
        }

        val vnode = if (visitorSupported && visitorsManager.enabled && (visitorRequested || !preferredInMainRoom)) {
            conference.redirectVisitor(
                visitorRequested || !allowedInMainRoom,
                userId,
                groupId
            )
        } else {
            null
        }
        if (visitorsManager.enabled && !visitorSupported) {
            logger.info("Endpoint with no visitor support.")
        }

        XmppConfig.visitors[vnode]?.jid?.let {
            logger.info("Redirecting to $vnode")
            response.vnode = vnode
            response.focusJid = it
        } ?: run {
            if (vnode != null) {
                logger.error("No XmppConnectionConfig for vnode=$vnode")
            }
        }

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
                if (!focusManager.hasBreakoutRooms(room) && authAuthority.getUserIdentity(query.from) == null) {
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
                iqRequest,
                StanzaError.getBuilder(StanzaError.Condition.internal_server_error).build()
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

    override fun componentsChanged(components: Set<XmppProvider.Component>) {
        val address = components.find { it.type == "breakout_rooms" }?.address

        breakoutAddress = if (address == null) {
            logger.info("No breakout room component discovered.")
            null
        } else {
            logger.info("Using breakout room component at $address.")
            JidCreate.domainBareFrom(address)
        }
    }

    companion object {
        val conferenceRequestCounter = JicofoMetricsContainer.instance.registerCounter(
            "conference_requests",
            "Number of conference requests received."
        )
        val newConferenceRequestCounter = JicofoMetricsContainer.instance.registerCounter(
            "conference_requests_new",
            "Number of conference requests received for new conferences."
        )
    }
}

/**
 * Read context.user.id from an unparsed token. Note that no validation is performed on the token, this is intentional.
 * Validation will be performed when the user attempts to login to XMPP.
 */
private fun parseToken(token: String?): JitsiToken? = try {
    token?.let { return JitsiToken.parseWithoutValidation(it) }
    null
} catch (e: Throwable) {
    null
}
