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
package org.jitsi.jicofo.bridge.colibri

import org.jitsi.jicofo.OctoConfig
import org.jitsi.jicofo.TranscriptionConfig
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.CascadeLink
import org.jitsi.jicofo.bridge.CascadeNode
import org.jitsi.jicofo.codec.CodecUtil
import org.jitsi.jicofo.codec.Config
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.TemplatedUrl
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Connect
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jitsi.xmpp.extensions.colibri2.InitialLastN
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.ExtmapAllowMixedPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smackx.muc.MUCRole
import java.net.URI
import java.util.Collections.singletonList
import java.util.UUID

/** Represents a colibri2 session with one specific bridge. */
class Colibri2Session(
    val colibriSessionManager: ColibriV2SessionManager,
    val bridge: Bridge,
    // Whether the session was constructed for the purpose of visitor nodes
    val visitor: Boolean,
    private var transcriberUrl: TemplatedUrl?,
    parentLogger: Logger
) : CascadeNode<Colibri2Session, Colibri2Session.Relay> {
    private val logger = createChildLogger(parentLogger).apply {
        bridge.jid.resourceOrNull?.toString()?.let { addContext("bridge", it) }
    }
    private val xmppConnection = colibriSessionManager.xmppConnection
    val id = UUID.randomUUID().toString()

    /**
     * Save the relay ID locally since it is possible for the relay ID of the Bridge to change and we don't want it to
     * change in the context of a session. We maintain the invariant that whenever a conference has multiple sessions,
     * they all have non-null relay IDs.
     */
    override val relayId: String? = bridge.relayId

    /**
     * Whether the colibri2 conference has been created. It is created with the first endpoint allocation request
     * ([sendAllocationRequest]).
     */
    var created = false

    /**
     * The sources advertised by the bridge, read from the response of the initial request to create a conference.
     */
    internal var feedbackSources: ConferenceSourceMap = ConferenceSourceMap()

    /** The set of (octo) relays for the session, mapped by their ID (i.e. the relayId of the remote bridge). */
    override val relays = mutableMapOf<String, Relay>()

    /** Creates and sends a request to allocate a new endpoint. Returns a [StanzaCollector] for the response. */
    internal fun sendAllocationRequest(participant: ParticipantInfo): StanzaCollector {
        val request = createRequest(!created)
        val endpoint = participant.toEndpoint(create = true, expire = false).apply {
            if (participant.audioMuted || participant.videoMuted) {
                setForceMute(participant.audioMuted, participant.videoMuted)
            }
            if (participant.visitor) {
                setMucRole(MUCRole.visitor)
            }
            setTransport(
                Transport.getBuilder().apply {
                    // TODO: we're hard-coding the role here, and it must be consistent with the role signaled to the
                    //  client. Signaling inconsistent roles leads to hard to debug issues (e.g. sporadic ICE/DTLS
                    //  failures with firefox but not chrome).
                    setIceControlling(true)
                    if (participant.useSctp) {
                        setSctp(Sctp.Builder().build())
                    }
                }.build()
            )
        }
        participant.medias.forEach { endpoint.addMedia(it) }
        request.addEndpoint(endpoint.build())

        logger.trace { "Sending allocation request for ${participant.id}: ${request.build().toXML()}" }
        created = true
        return xmppConnection.createStanzaCollectorAndSend(request.build())
    }

    /** Updates the transport info and/or sources for an existing endpoint. */
    internal fun updateParticipant(
        participant: ParticipantInfo,
        /** The transport info to set for the colibri2 endpoint, or null if it is not to be modified. */
        transport: IceUdpTransportPacketExtension?,
        /** The sources to set for the colibri2 endpoint, or null if the sources are not to be modified. */
        sources: EndpointSourceSet?,
        initialLastN: InitialLastN?
    ) {
        if (transport == null && sources == null && initialLastN == null) {
            logger.info("Nothing to update.")
            return
        }

        val request = createRequest()
        val endpoint = Colibri2Endpoint.getBuilder().apply {
            setId(participant.id)
            setStatsId(participant.statsId)
        }

        if (transport != null) {
            endpoint.setTransport(Transport.getBuilder().setIceUdpExtension(transport).build())
        }

        if (sources != null) {
            endpoint.setSources(sources.toColibriMediaSources(participant.id))
        }

        initialLastN?.let {
            endpoint.setInitialLastN(it)
        }

        request.addEndpoint(endpoint.build())
        sendRequest(request.build(), "updateParticipant")
    }

    internal fun updateForceMute(participants: Set<ParticipantInfo>) {
        val request = createRequest()
        participants.forEach { participant ->
            request.addEndpoint(
                Colibri2Endpoint.getBuilder().apply {
                    setId(participant.id)
                    setForceMute(participant.audioMuted, participant.videoMuted)
                }.build()
            )
        }

        sendRequest(request.build(), "updateForceMute")
    }

    /** Expire the entire conference. */
    internal fun expire() {
        relays.clear()
        val request = createRequest().setExpire(true)
        sendRequest(request.build(), "expire")
    }

    /** Expire the colibri2 endpoint for a specific participant */
    internal fun expire(participantToExpire: ParticipantInfo) = expire(singletonList(participantToExpire))

    /** Expire the colibri2 endpoints for a set of participants. */
    internal fun expire(participantsToExpire: List<ParticipantInfo>) {
        if (participantsToExpire.isEmpty()) {
            logger.debug { "No participants to expire." }
            return
        }
        val request = createRequest()
        participantsToExpire.forEach { request.addExpire(it.id) }

        logger.debug { "Expiring endpoint: ${participantsToExpire.map { it.id }}" }
        sendRequest(request.build(), "expire(participantsToExpire)")
    }

    private fun createRequest(create: Boolean = false) = ConferenceModifyIQ.builder(xmppConnection).apply {
        to(bridge.jid)
        setMeetingId(colibriSessionManager.meetingId)
        if (create) {
            setCreate(true)
            setConferenceName(colibriSessionManager.conferenceName)
            setRtcstatsEnabled(colibriSessionManager.rtcStatsEnabled)
            transcriberUrl?.let {
                val url = resolveTranscriberUrl(it)
                logger.info("Adding connect for transcriber, url=$url")
                addConnect(createConnect(url))
            }
        }
    }

    private fun resolveTranscriberUrl(urlTemplate: TemplatedUrl): URI {
        return urlTemplate.resolve(TranscriptionConfig.REGION_TEMPLATE, bridge.region ?: "")
    }

    fun setTranscriberUrl(urlTemplate: TemplatedUrl?) {
        if (transcriberUrl != urlTemplate) {
            transcriberUrl = urlTemplate
            val request = createRequest(create = false)
            if (urlTemplate != null) {
                val url = resolveTranscriberUrl(urlTemplate)
                logger.info("Adding connect, url=$url")
                request.addConnect(createConnect(url))
            } else {
                logger.info("Removing connects")
                request.setEmptyConnects()
            }
            sendRequest(request.build(), "setTranscriberUrl")
        } else {
            logger.info("No change in audio record URL.")
        }
    }

    /**
     * Create a new colibri2 relay. This sends an allocation request and submits an IO task to wait for and handle the
     * response.
     * This assumes that the colibri2 conference has already been created, i.e. [createRelay] must be called after at
     * least one call to [sendAllocationRequest].
     */
    internal fun createRelay(
        /** The ID of the relay (i.e. the relayId of the remote [Bridge]). */
        relayId: String,
        /** Initial remote endpoints to be included in the relay. */
        initialParticipants: List<ParticipantInfo>,
        /**
         * The single flag used internally to determine the ICE/DTLS/WS roles of the relay. The two sides in a relay
         * connection should have different values for [initiator].
         */
        initiator: Boolean,
        /** The mesh ID of this relay connection */
        meshId: String?
    ) {
        logger.info(
            "Creating relay $relayId (initiator=$initiator), initial participants: ${initialParticipants.map { it.id }}"
        )
        if (relays.containsKey(relayId)) {
            throw IllegalStateException("Relay $relayId already exists (bridge=${this.relayId}")
        }

        val relay = Relay(relayId, initiator, meshId)
        relays[relayId] = relay
        relay.start(initialParticipants)
    }

    /**
     * Updates the sources for a specific endpoint on a specific relay. Also used to create a new remote endpoint in
     * the relay (when [create] is true).
     */
    internal fun updateRemoteParticipant(
        participantInfo: ParticipantInfo,
        /** The ID of the relay on which to add/update an endpoint. */
        relayId: String,
        /** Whether a new relay endpoint should be created, or an existing one updated. */
        create: Boolean
    ) {
        logger.debug { "Updating remote participant ${participantInfo.id} on $relayId" }
        relays[relayId]?.updateParticipant(participantInfo, create)
            ?: throw IllegalStateException("Relay $relayId doesn't exist (bridge=${this.relayId})")
    }

    /** Expires a set of endpoints on a specific relay. */
    internal fun expireRemoteParticipants(participants: List<ParticipantInfo>, relayId: String) {
        logger.debug { "Expiring remote participants on $relayId: ${participants.map { it.id }}" }
        relays[relayId]?.expireParticipants(participants)
            ?: throw IllegalStateException("Relay $relayId doesn't exist (bridge=${this.relayId})")
    }

    /** Sets the remote side transport information for a specific relay. */
    internal fun setRelayTransport(
        /** The transport information of the other bridge. */
        transport: IceUdpTransportPacketExtension,
        relayId: String
    ) {
        logger.info("Setting relay transport for $relayId")
        logger.debug { "Setting relay transport for $relayId: ${transport.toXML()}" }
        relays[relayId]?.setTransport(transport)
            ?: throw IllegalStateException("Relay $relayId doesn't exist (bridge=${this.relayId}")
    }

    internal fun expireAllRelays() = expireRelays(relays.keys.toList())
    internal fun expireRelay(relayId: String) = expireRelays(listOf(relayId))
    private fun expireRelays(relayIds: List<String>) {
        if (relayIds.isEmpty()) {
            logger.debug("No relays to expire.")
            return
        }
        logger.info("Expiring relays: $relayIds")
        val request = createRequest()
        relayIds.forEach {
            request.addRelay(
                Colibri2Relay.getBuilder().apply {
                    setId(it)
                    setExpire(true)
                }.build()
            )
        }

        relayIds.forEach { relays.remove(it) }

        sendRequest(request.build(), "expireRelays")
    }

    /**
     * Send an IQ async, and handle timeouts and errors: timeouts are just logged, while errors trigger a session
     * failure.
     */
    private fun sendRequest(iq: IQ, name: String) {
        logger.debug { "Sending $name request: ${iq.toXML()}" }
        xmppConnection.sendIqAndHandleResponseAsync(iq) { response ->
            if (response == null) {
                logger.info("$name request timed out. Ignoring.")
                return@sendIqAndHandleResponseAsync
            }

            response.error?.let { error ->
                val reason = error.getExtension<Colibri2Error>(
                    Colibri2Error.ELEMENT,
                    Colibri2Error.NAMESPACE
                )?.reason
                val endpointId = error.getExtension<Colibri2Endpoint>(
                    Colibri2Endpoint.ELEMENT,
                    Colibri2Endpoint.NAMESPACE
                )?.id
                // If colibri2 error extension is present then the message came from
                // a jitsi-videobridge instance. Otherwise, it might come from another component
                // (e.g. the XMPP server or MUC component).
                val reInvite = reason == Colibri2Error.Reason.UNKNOWN_ENDPOINT && endpointId != null
                if (reInvite) {
                    logger.warn(
                        "Endpoint [$endpointId] is not found, session failed: ${error.toXML()}, " +
                            "request was: ${iq.toXML()}"
                    )
                    colibriSessionManager.endpointFailed(endpointId!!)
                } else {
                    logger.error("Received error response for $name, session failed: ${error.toXML()}")
                    colibriSessionManager.sessionFailed(this@Colibri2Session)
                }
                return@sendIqAndHandleResponseAsync
            }

            if (response !is ConferenceModifiedIQ) {
                logger.error("Received response with unexpected type ${response.javaClass.name}")
                colibriSessionManager.sessionFailed(this@Colibri2Session)
            } else {
                logger.debug { "Received $name response: ${response.toXML()}" }
            }
        }
    }

    override fun toString() = "Colibri2Session[bridge=${bridge.jid.resourceOrNull}, id=$id]"

    fun toJson() = OrderedJsonObject().apply {
        put("bridge", bridge.debugState)
        put("id", id)
        put("feedback_sources", feedbackSources.toJson())
        put("created", created)
        put(
            "relays",
            OrderedJsonObject().apply {
                relays.values.forEach { put(it.relayId, it.toJson()) }
            }
        )
    }

    /**
     * Represents a colibri2 relay connection to another bridge.
     */
    inner class Relay(
        /** The relayId of the remote bridge. */
        override val relayId: String,
        /**
         * A flag used to determine the roles. The associated [Relay] for the remote side should have the opposite
         * value.
         */
        initiator: Boolean,
        override val meshId: String? = null
    ) : CascadeLink {
        // TODO: One of the ways to select the ICE/DTLS roles might save an RTT. Which one?
        private val useUniquePort = initiator
        private val iceControlling = initiator
        private val dtlsSetup = if (initiator) "active" else "passive"
        private val bridgeChannelActive = initiator
        private val sctpBridgeChannel = OctoConfig.config.sctpDatachannels

        private val logger = createChildLogger(this@Colibri2Session.logger).apply { addContext("relay", relayId) }

        /** Send a request to allocate a new relay, and submit a task to wait for a response. */
        internal fun start(initialParticipants: List<ParticipantInfo>) {
            val request = buildCreateRelayRequest(initialParticipants)
            logger.trace { "Sending create relay: ${request.toXML()}" }

            xmppConnection.sendIqAndHandleResponseAsync(request) { response ->
                // Wait for a response to the relay allocation request. When a response is received, parse the contained
                // transport and forward it to the associated [Relay] for the remote side via [colibriSessionManager]
                logger.trace { "Received response: ${response?.toXML()}" }
                if (response !is ConferenceModifiedIQ) {
                    logger.error("Received error: ${response?.toXML() ?: "timeout"}")
                    colibriSessionManager.sessionFailed(this@Colibri2Session)
                    return@sendIqAndHandleResponseAsync
                }

                // TODO: We just assume that the response has a single [Colibri2Relay].
                val transport = response.relays.firstOrNull()?.transport
                    ?: run {
                        logger.error("No transport in response: ${response.toXML()}")
                        colibriSessionManager.sessionFailed(this@Colibri2Session)
                        return@sendIqAndHandleResponseAsync
                    }
                val iceUdpTransport = transport.iceUdpTransport
                if (iceUdpTransport == null) {
                    logger.error("Response has no iceUdpTransport")
                    colibriSessionManager.sessionFailed(this@Colibri2Session)
                    return@sendIqAndHandleResponseAsync
                }

                // Forward the response to the corresponding [Colibri2Session]
                colibriSessionManager.setRelayTransport(this@Colibri2Session, iceUdpTransport, relayId)
            }
        }

        /** Sends a colibri2 message setting/updating the remote-side transport of this relay. */
        fun setTransport(
            /**
             * The transport info as received by the remote side. We update some of the fields as required based on the
             * ICE/DTLS/WS roles.
             */
            transport: IceUdpTransportPacketExtension
        ) {
            // We always expect the bridge to advertise its DTLS setup as "actpass". Here we override it to set one
            // side as "active" and the other as "passive".
            transport.getChildExtensionsOfType(DtlsFingerprintPacketExtension::class.java).forEach {
                if (it.setup != "actpass") {
                    logger.error("Response has an unexpected dtls setup field: ${it.setup}")
                    return
                }
                logger.info("Setting setup=$dtlsSetup for $relayId")
                it.setup = dtlsSetup
            }
            // The bridge always advertises a websocket if we're not using SCTP, but we want only one side to act as a
            // client.
            // The bridge will always act as a client if the signaled transport includes a websocket, so here we make
            // sure it is only included to one of the sides.
            if (sctpBridgeChannel || !bridgeChannelActive) {
                transport.getChildExtensionsOfType(WebSocketPacketExtension::class.java).forEach {
                    transport.removeChildExtension(it)
                }
            }

            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(relayId)
                setCreate(false)
            }
            relay.setTransport(Transport.getBuilder().apply { setIceUdpExtension(transport) }.build())
            request.addRelay(relay.build())

            sendRequest(request.build(), "Relay.setTransport")
        }

        fun toJson() = OrderedJsonObject().apply {
            put("id", relayId)
            put("use_unique_port", useUniquePort)
            put("ice_controlling", iceControlling)
            put("dtls_setup", dtlsSetup)
            put("bridge_channel_active", bridgeChannelActive)
            put("sctp_bridge_channel", sctpBridgeChannel)
            meshId?.let { put("mesh_id", it) }
        }

        /** Update or create a relay endpoint for a specific participant. */
        fun updateParticipant(participant: ParticipantInfo, create: Boolean) {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(relayId)
            }
            val endpoints = Endpoints.getBuilder()
            endpoints.addEndpoint(participant.toEndpoint(create = create, expire = false).build())
            relay.setEndpoints(endpoints.build())
            request.addRelay(relay.build())
            sendRequest(request.build(), "Relay.updateParticipant")
        }

        /** Expire relay endpoints for a set of participants. */
        fun expireParticipants(participants: List<ParticipantInfo>) {
            if (participants.all { it.visitor }) {
                return
            }
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply { setId(relayId) }
            val endpoints = Endpoints.getBuilder()

            participants.forEach {
                if (!it.visitor) {
                    endpoints.addEndpoint(it.toEndpoint(create = false, expire = true).build())
                }
            }

            relay.setEndpoints(endpoints.build())
            request.addRelay(relay.build())

            sendRequest(request.build(), "Relay.expireParticipants")
        }

        /** Create a request to create a relay (this is just the initial request). */
        private fun buildCreateRelayRequest(participants: Collection<ParticipantInfo>): ConferenceModifyIQ {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(relayId)
                meshId?.let { setMeshId(it) }
                setCreate(true)
            }

            relay.addMedia(
                Media.getBuilder().apply {
                    setType(MediaType.AUDIO)
                    CodecUtil.createAudioPayloadTypeExtensions().forEach { addPayloadType(it) }
                    CodecUtil.createAudioRtpHdrExtExtensions().forEach { addRtpHdrExt(it) }
                    if (Config.Companion.config.extmapAllowMixed) {
                        setExtmapAllowMixed(ExtmapAllowMixedPacketExtension())
                    }
                }.build()
            )
            relay.addMedia(
                Media.getBuilder().apply {
                    setType(MediaType.VIDEO)
                    CodecUtil.createVideoPayloadTypeExtensions().forEach { addPayloadType(it) }
                    CodecUtil.createVideoRtpHdrExtExtensions().forEach { addRtpHdrExt(it) }
                    if (Config.Companion.config.extmapAllowMixed) {
                        setExtmapAllowMixed(ExtmapAllowMixedPacketExtension())
                    }
                }.build()
            )

            val endpoints = Endpoints.getBuilder()
            participants.filter { !it.visitor }.forEach {
                endpoints.addEndpoint(it.toEndpoint(create = true, expire = false).build())
            }
            relay.setEndpoints(endpoints.build())

            relay.setTransport(
                Transport.getBuilder().apply {
                    setUseUniquePort(useUniquePort)
                    setIceControlling(iceControlling)
                    if (sctpBridgeChannel) {
                        val sctp = Sctp.Builder()
                        sctp.setRole(if (bridgeChannelActive) Sctp.Role.CLIENT else Sctp.Role.SERVER)
                        setSctp(sctp.build())
                    }
                }.build()
            )

            request.addRelay(relay.build())

            return request.build()
        }
    }
}

private fun ConferenceModifyIQ.Builder.addExpire(endpointId: String) = addEndpoint(
    Colibri2Endpoint.getBuilder().apply {
        setId(endpointId)
        setExpire(true)
    }.build()
)

private fun createConnect(url: URI) = Connect(
    url = url,
    type = Connect.Types.RECORDER,
    protocol = Connect.Protocols.MEDIAJSON,
    audio = true
)
