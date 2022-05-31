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
package org.jitsi.jicofo.conference.colibri.v2

import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.codec.CodecUtil
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.colibri2.Capability
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.IQ
import java.util.Collections.singletonList
import java.util.UUID

/** Represents a colibri2 session with one specific bridge. */
internal class Colibri2Session(
    val colibriSessionManager: ColibriV2SessionManager,
    val bridge: Bridge,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger).apply {
        bridge.jid.resourceOrNull?.toString()?.let { addContext("bridge", it) }
    }
    private val xmppConnection = colibriSessionManager.xmppConnection
    val id = UUID.randomUUID().toString()
    /**
     * Save the relay ID locally since it is possible for the relay ID of the Bridge to change and we don't want it to
     * change in the context of a session. We maintain the invariant that whenever a a conference has multiple sessions,
     * they all have non-null relay IDs.
     */
    val relayId: String? = bridge.relayId

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
    private val relays = mutableMapOf<String, Relay>()

    /** Creates and sends a request to allocate a new endpoint. Returns a [StanzaCollector] for the response. */
    internal fun sendAllocationRequest(
        participant: ParticipantInfo,
        /**
         * A list of Jingle [ContentPacketExtension]s, which describe the media types and RTP header extensions, i.e.
         * the information contained in colibri2 [Media] elements.
         */
        contents: List<ContentPacketExtension>,
        useSctp: Boolean
    ): StanzaCollector {

        val request = createRequest(!created)
        val endpoint = Colibri2Endpoint.getBuilder().apply {
            setId(participant.id)
            setCreate(true)
            if (participant.sources.isNotEmpty()) {
                setSources(participant.sources.toColibriMediaSources())
            }
            setStatsId(participant.statsId)
            if (participant.supportsSourceNames) {
                addCapability(Capability.CAP_SOURCE_NAME_SUPPORT)
            }
            if (participant.audioMuted || participant.videoMuted) {
                setForceMute(participant.audioMuted, participant.videoMuted)
            }
            setTransport(
                Transport.getBuilder().apply {
                    // TODO: we're hard-coding the role here, and it must be consistent with the role signaled to the
                    //  client. Signaling inconsistent roles leads to hard to debug issues (e.g. sporadic ICE/DTLS
                    //  failures with firefox but not chrome).
                    setIceControlling(true)
                    if (useSctp) {
                        setSctp(Sctp.Builder().build())
                    }
                }.build()
            )
        }
        contents.forEach { it.toMedia()?.let<Media, Unit> { media -> endpoint.addMedia(media) } }
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
        sources: ConferenceSourceMap?
    ) {
        if (transport == null && sources == null) {
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
            endpoint.setSources(sources.toColibriMediaSources())
        }

        request.addEndpoint(endpoint.build())
        logger.trace { "Sending endpoint update: ${request.build().toXML()}" }

        xmppConnection.sendIqAndLogResponse(request.build(), logger)
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

        xmppConnection.sendIqAndLogResponse(request.build(), logger)
    }

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
        logger.trace { "Expiring endpoints: ${request.build().toXML()}" }
        xmppConnection.sendIqAndLogResponse(request.build(), logger)
    }

    private fun createRequest(create: Boolean = false) = ConferenceModifyIQ.builder(xmppConnection).apply {
        to(bridge.jid)
        setMeetingId(colibriSessionManager.meetingId)
        if (create) {
            setCreate(true)
            setConferenceName(colibriSessionManager.conferenceName)
            setCallstatsEnabled(colibriSessionManager.callstatsEnabled)
            setRtcstatsEnabled(colibriSessionManager.rtcStatsEnabled)
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
        initiator: Boolean
    ) {
        logger.info(
            "Creating relay $relayId (initiator=$initiator), initial participants: ${initialParticipants.map { it.id }}"
        )
        if (relays.containsKey(relayId)) {
            throw IllegalStateException("Relay $relayId already exists")
        }

        val relay = Relay(relayId, initiator)
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
            ?: throw IllegalStateException("Relay $relayId doesn't exist.")
    }

    /** Expires a set of endpoints on a specific relay. */
    internal fun expireRemoteParticipants(participants: List<ParticipantInfo>, relayId: String) {
        logger.debug { "Expiring remote participants on $relayId: ${participants.map { it.id }}" }
        relays[relayId]?.expireParticipants(participants)
            ?: throw IllegalStateException("Relay $relayId doesn't exist.")
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
            ?: throw IllegalStateException("Relay $relayId doesn't exist.")
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

        logger.trace("Expiring relays $relayIds: ${request.build().toXML()}")
        xmppConnection.sendIqAndLogResponse(request.build(), logger)
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
                relays.values.forEach { put(it.id, it.toJson()) }
            }
        )
    }

    /**
     * Represents a colibri2 relay connection to another bridge.
     */
    private inner class Relay(
        /** The relayId of the remote bridge. */
        val id: String,
        /**
         * A flag used to determine the roles. The associated [Relay] for the remote side should have the opposite
         * value.
         */
        initiator: Boolean
    ) {
        // TODO: One of the ways to select the ICE/DTLS roles might save an RTT. Which one?
        private val useUniquePort = initiator
        private val iceControlling = initiator
        private val dtlsSetup = if (initiator) "active" else "passive"
        private val websocketActive = initiator

        private val logger = createChildLogger(this@Colibri2Session.logger).apply { addContext("relay", id) }

        /** Whether the transport has been updated with the remote side's candidates, DTLS fingerprints etc. */
        private var transportUpdated = false

        /** Send a request to allocate a new relay, and submit a task to wait for a response. */
        fun start(initialParticipants: List<ParticipantInfo>) {
            val request = buildCreateRelayRequest(initialParticipants)
            logger.trace { "Sending create relay: ${request.toXML()}" }
            val stanzaCollector = xmppConnection.createStanzaCollectorAndSend(request)
            TaskPools.ioPool.submit { waitForResponse(stanzaCollector) }
        }

        /**
         * Waits for a response to the relay allocation request. When a response is received, parse the contained
         * transport and forward it to the associated [Relay] for the remote side via [colibriSessionManager]
         * TODO: act on errors (remove both bridges?)
         */
        private fun waitForResponse(stanzaCollector: StanzaCollector) {
            val response: IQ?
            try {
                response = stanzaCollector.nextResult()
            } finally {
                logger.debug("Cancelling.")
                stanzaCollector.cancel()
            }
            logger.trace { "Received response: ${response?.toXML()}" }
            if (response !is ConferenceModifiedIQ) {
                logger.error("Received error: ${response?.toXML() ?: "timeout"}")
                return
            }

            // TODO: We just assume that the response has a single [Colibri2Relay].
            val transport = response.relays.firstOrNull()?.transport
                ?: run {
                    logger.error("No transport in response: ${response.toXML()}")
                    return
                }
            val iceUdpTransport = transport.iceUdpTransport
            if (iceUdpTransport == null) {
                logger.error("Response has no iceUdpTransport")
                return
            }

            // Forward the response to the corresponding [Colibri2Session]
            colibriSessionManager.setRelayTransport(this@Colibri2Session, iceUdpTransport, id)
        }

        /**
         * Sends a colibri2 message setting/updating the remote-side transport of this relay.
         *
         */
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
                logger.info("Setting setup=$dtlsSetup for $id")
                it.setup = dtlsSetup
            }
            // We always expect the bridge to advertise a websocket, but we want only one side to act as a client.
            // The bridge will always act as a client if the signaled transport includes a websocket, so here we make
            // sure it is only included to one of the sides.
            if (!websocketActive) {
                transport.getChildExtensionsOfType(WebSocketPacketExtension::class.java).forEach {
                    transport.removeChildExtension(it)
                }
            }

            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(id)
                setCreate(false)
            }
            relay.setTransport(Transport.getBuilder().apply { setIceUdpExtension(transport) }.build())
            request.addRelay(relay.build())

            logger.debug { "Setting transport: ${request.build().toXML()}" }
            xmppConnection.sendIqAndLogResponse(request.build(), logger)
            transportUpdated = true
        }

        fun toJson() = OrderedJsonObject().apply {
            put("id", id)
            put("use_unique_port", useUniquePort)
            put("ice_controlling", iceControlling)
            put("dtls_setup", dtlsSetup)
            put("websocket_active", websocketActive)
            put("transport_updated", transportUpdated)
        }

        /** Update or create a relay endpoint for a specific participant. */
        fun updateParticipant(participant: ParticipantInfo, create: Boolean) {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(id)
            }
            val endpoints = Endpoints.getBuilder()
            endpoints.addEndpoint(participant.toEndpoint(create = create, expire = false))
            relay.setEndpoints(endpoints.build())
            request.addRelay(relay.build())
            logger.debug { "${if (create) "Creating" else "Updating"} endpoint ${participant.id}" }
            logger.trace { "Sending ${request.build().toXML()}" }
            xmppConnection.sendIqAndLogResponse(request.build(), logger)
        }

        /** Expire relay endpoints for a set of participants. */
        fun expireParticipants(participants: List<ParticipantInfo>) {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply { setId(id) }
            val endpoints = Endpoints.getBuilder()

            participants.forEach { endpoints.addEndpoint(it.toEndpoint(create = false, expire = true)) }

            relay.setEndpoints(endpoints.build())
            request.addRelay(relay.build())

            logger.debug { "Expiring ${participants.map { it.id }}" }
            logger.trace { "Sending ${request.build().toXML()}" }
            xmppConnection.sendIqAndLogResponse(request.build(), logger)
        }

        /** Create a request to create a relay (this is just the initial request). */
        private fun buildCreateRelayRequest(participants: Collection<ParticipantInfo>): ConferenceModifyIQ {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(id)
                setCreate(true)
            }

            relay.addMedia(
                Media.getBuilder().apply {
                    setType(MediaType.AUDIO)
                    CodecUtil.createAudioPayloadTypeExtensions().forEach { addPayloadType(it) }
                    CodecUtil.createAudioRtpHdrExtExtensions().forEach { addRtpHdrExt(it) }
                }.build()
            )
            relay.addMedia(
                Media.getBuilder().apply {
                    setType(MediaType.VIDEO)
                    CodecUtil.createVideoPayloadTypeExtensions().forEach { addPayloadType(it) }
                    CodecUtil.createVideoRtpHdrExtExtensions().forEach { addRtpHdrExt(it) }
                }.build()
            )

            val endpoints = Endpoints.getBuilder()
            participants.forEach { endpoints.addEndpoint(it.toEndpoint(create = true, expire = false)) }
            relay.setEndpoints(endpoints.build())

            relay.setTransport(
                Transport.getBuilder().apply {
                    setUseUniquePort(useUniquePort)
                    setIceControlling(iceControlling)
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
