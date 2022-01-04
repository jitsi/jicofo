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
import org.jitsi.jicofo.codec.JingleOfferFactory
import org.jitsi.jicofo.codec.OctoOptions
import org.jitsi.jicofo.conference.colibri.BadColibriRequestException
import org.jitsi.jicofo.conference.colibri.ColibriAllocation
import org.jitsi.jicofo.conference.colibri.ColibriAllocationFailedException
import org.jitsi.jicofo.conference.colibri.ColibriParsingException
import org.jitsi.jicofo.conference.colibri.ColibriTimeoutException
import org.jitsi.jicofo.conference.colibri.GenericColibriAllocationFailedException
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import java.util.Collections.singletonList
import java.util.UUID

internal class Colibri2Session(
    val colibriSessionManager: ColibriV2SessionManager,
    val bridge: Bridge,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger).apply {
        bridge.jid.resourceOrNull?.toString()?.let { addContext("bridge", it) }
    }

    /**
     * The sources advertised by the bridge, read from the response of the initial request to create a conference.
     */
    private var bridgeSources: ConferenceSourceMap = ConferenceSourceMap()
    private val xmppConnection = colibriSessionManager.xmppConnection
    val id = UUID.randomUUID().toString()

    private val relays = mutableMapOf<String, Relay>()
    internal val participants = mutableListOf<ParticipantInfo>()

    /**
     * Creates and sends a request to allocate a new endpoint. Returns a [StanzaCollector] for the response.
     */
    internal fun sendAllocationRequest(
        participant: ParticipantInfo,
        contents: List<ContentPacketExtension>,
        create: Boolean
    ): StanzaCollector {
        val request = createRequest(create).apply { setCreate(create) }
        val endpoint = Colibri2Endpoint.getBuilder().apply {
            setId(participant.id)
            setCreate(true)
            setStatsId(participant.statsId)
            setTransport(Transport.getBuilder().build())
        }
        contents.forEach { it.toMedia()?.let<Media, Unit> { media -> endpoint.addMedia(media) } }
        request.addEndpoint(endpoint.build())

        logger.debug { "Sending allocation request for ${participant.id}: ${request.build().toXML()}" }
        return xmppConnection.createStanzaCollectorAndSend(request.build())
    }

    @Throws(ColibriAllocationFailedException::class)
    internal fun processAllocationResponse(response: IQ?, participantId: String, create: Boolean): ColibriAllocation {
        logger.debug {
            "Received response of type ${response?.let { it::class.java } ?: "null" }: ${response?.toXML()}"
        }

        if (response == null)
            throw ColibriTimeoutException(bridge.jid)
        else if (response is ErrorIQ) {
            // TODO proper error handling
            throw GenericColibriAllocationFailedException(response.toXML().toString())
        } else if (response !is ConferenceModifiedIQ)
            throw BadColibriRequestException("response of wrong type: ${response::class.java.name }")

        if (create) {
            bridgeSources = response.parseSources()
        }

        return ColibriAllocation(
            bridgeSources,
            response.parseTransport(participantId) ?: throw ColibriParsingException("failed to parse transport"),
            bridge.region,
            id
        )
    }

    internal fun updateParticipant(
        participant: ParticipantInfo,
        transport: IceUdpTransportPacketExtension?,
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

        xmppConnection.tryToSendStanza(request.build())
    }

    internal fun mute(participant: ParticipantInfo, audio: Boolean, video: Boolean): StanzaCollector {
        val request = createRequest()
        request.addEndpoint(
            Colibri2Endpoint.getBuilder().apply {
                setForceMute(audio, video)
                setId(participant.id)
            }.build()
        )

        logger.debug { "Sending mute request for $participant: ${request.build().toXML()}" }
        return xmppConnection.createStanzaCollectorAndSend(request.build())
    }

    internal fun expire(participantToExpire: ParticipantInfo) = expire(singletonList(participantToExpire))
    internal fun expire(participantsToExpire: List<ParticipantInfo>) {
        val request = createRequest()
        participantsToExpire.forEach { request.addExpire(it.id) }

        val response = xmppConnection.sendIqAndGetResponse(request.build())

        if (response !is ConferenceModifiedIQ) {
            logger.error("Failed to expire: ${response?.javaClass?.name}: ${response?.toXML()}")
        }
    }

    private fun createRequest(create: Boolean = false) = ConferenceModifyIQ.builder(xmppConnection).apply {
        to(bridge.jid)
        setMeetingId(colibriSessionManager.meetingId)
        if (create) setConferenceName(colibriSessionManager.conferenceName)
    }

    internal fun createRelay(relayId: String, initialParticipants: List<ParticipantInfo>, initiator: Boolean) {
        if (relays.containsKey(relayId)) {
            throw IllegalStateException("Relay $relayId already exists")
        }

        val relay = Relay(relayId, initiator)
        relays[relayId] = relay
        relay.start(initialParticipants)
    }

    internal fun updateRemoteParticipant(participantInfo: ParticipantInfo, relayId: String, create: Boolean) {
        relays[relayId]?.updateParticipant(participantInfo, create)
            ?: throw IllegalStateException("Relay $relayId doesn't exist.")
    }

    internal fun setRelayTransport(transport: IceUdpTransportPacketExtension, relayId: String) {
        relays[relayId]?.setTransport(transport)
            ?: throw IllegalStateException("Relay $relayId doesn't exist.")
    }

    private inner class Relay(
        val id: String,
        initiator: Boolean
    ) {
        private val useUniquePort = initiator
        private val iceControlling = initiator
        private val dtlsSetup = if (initiator) "active" else "passive"
        private val websocketActive = initiator

        fun start(initialParticipants: List<ParticipantInfo>) {
            val request = buildCreateRelayRequest(initialParticipants)
            val stanzaCollector = xmppConnection.createStanzaCollectorAndSend(request)
            TaskPools.ioPool.submit { waitForResponse(stanzaCollector) }
        }

        private fun waitForResponse(stanzaCollector: StanzaCollector) {
            val response: IQ?
            try {
                response = stanzaCollector.nextResult()
            } finally {
                logger.info("Cancel.")
                stanzaCollector.cancel()
            }
            if (response !is ConferenceModifiedIQ) {
                logger.error("Received error: ${response?.toXML() ?: "timeout"}")
                return
            }

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
            // TODO indicate that the octo connection failed somehow.

            // Forward the response to the corresponding [Colibri2Session]
            colibriSessionManager.setRelayTransport(this@Colibri2Session, iceUdpTransport, id)
        }

        fun setTransport(transport: IceUdpTransportPacketExtension) {
            transport.getChildExtensionsOfType(DtlsFingerprintPacketExtension::class.java).forEach {
                if (it.setup != "actpass") {
                    logger.error("Response has an unexpected dtls setup field: ${it.setup}")
                    return
                }
                logger.info("Setting setup=$dtlsSetup for $id")
                it.setup = dtlsSetup
            }
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

            logger.warn("Updating transport: ${request.build().toXML()}")
            xmppConnection.trySendStanza(request.build())
        }

        fun updateParticipant(participant: ParticipantInfo, create: Boolean) {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(id)
            }
            val endpoints = Endpoints.getBuilder()
            endpoints.addEndpoint(participant.toEndpoint(create))
            relay.setEndpoints(endpoints.build())
            request.addRelay(relay.build())
            logger.warn("Creating new octo endpoiont: ${request.build().toXML()}")
            xmppConnection.trySendStanza(request.build())
        }

        private fun buildCreateRelayRequest(participants: Collection<ParticipantInfo>): ConferenceModifyIQ {
            val request = createRequest()
            val relay = Colibri2Relay.getBuilder().apply {
                setId(id)
                setCreate(true)
            }

            val contents = JingleOfferFactory.INSTANCE.createOffer(OctoOptions)
            contents.forEach { it.toMedia()?.let<Media, Unit> { media -> relay.addMedia(media) } }

            val endpoints = Endpoints.getBuilder()
            participants.forEach { endpoints.addEndpoint(it.toEndpoint(true)) }
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
