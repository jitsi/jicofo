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

import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.colibri.BadColibriRequestException
import org.jitsi.jicofo.conference.colibri.ColibriAllocation
import org.jitsi.jicofo.conference.colibri.ColibriAllocationFailedException
import org.jitsi.jicofo.conference.colibri.ColibriParsingException
import org.jitsi.jicofo.conference.colibri.ColibriTimeoutException
import org.jitsi.jicofo.conference.colibri.GenericColibriAllocationFailedException
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import java.util.Collections.singletonList
import java.util.UUID

internal class Colibri2Session(
    private val xmppConnection: AbstractXMPPConnection,
    private val conferenceName: String,
    private val meetingId: String,
    val bridge: Bridge,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger).apply {
        bridge.jid.resourceOrNull?.toString()?.let { addContext("bridge", it) }
    }

    /**
     * The sources advertised by the bridge, read from the response of the initial request to create a conference.
     */
    var bridgeSources: ConferenceSourceMap = ConferenceSourceMap()

    val id = UUID.randomUUID().toString()

    /**
     * Creates and sends a request to allocate a new endpoint. Returns a [StanzaCollector] for the response.
     */
    internal fun sendAllocationRequest(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        create: Boolean
    ): StanzaCollector {
        val request = createRequest(create).apply { setCreate(create) }
        val endpoint = Colibri2Endpoint.getBuilder().apply {
            setId(participant.endpointId)
            setCreate(true)
            setStatsId(participant.statId)
            setTransport(Transport.getBuilder().build())
        }
        contents.forEach { it.toMedia()?.let<Media, Unit> { media -> endpoint.addMedia(media) } }
        request.addEndpoint(endpoint.build())

        logger.debug { "Sending allocation request for ${participant.endpointId}: ${request.build().toXML()}" }
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
            response.parseTransport(participantId)
                ?: throw ColibriParsingException("failed to parse transport"),
            bridge.region,
            id
        )
    }

    internal fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension?,
        sources: ConferenceSourceMap?
    ) {
        if (transport == null && sources == null) {
            logger.info("Nothing to update.")
            return
        }

        val request = createRequest()
        val endpoint = Colibri2Endpoint.getBuilder().apply {
            setId(participant.endpointId)
            setStatsId(participant.statId)
        }

        if (transport != null) {
            endpoint.setTransport(Transport.getBuilder().setIceUdpExtension(transport).build())
        }

        if (sources != null) {
            endpoint.setSources(sources.toColibriMediaSources())
        }

        request.addEndpoint(endpoint.build())

        val response = xmppConnection.sendIqAndGetResponse(request.build())

        // TODO improve error handling. Do we need to clean up? *Can* we clean up?
        if (response !is ConferenceModifiedIQ) {
            logger.error("Failed to update participant: ${response?.javaClass?.name}: ${response?.toXML()}")
        }
    }

    internal fun mute(endpointId: String, audio: Boolean, video: Boolean): StanzaCollector {
        val request = createRequest()
        request.addEndpoint(
            Colibri2Endpoint.getBuilder().apply {
                setForceMute(audio, video)
                setId(endpointId)
            }.build()
        )

        logger.debug { "Sending mute request for ${endpointId}: ${request.build().toXML()}" }
        return xmppConnection.createStanzaCollectorAndSend(request.build())
    }

    internal fun expire(endpointId: String) = expire(singletonList(endpointId))
    internal fun expire(endpointIds: List<String>) {
        val request = createRequest()
        endpointIds.forEach { request.addExpire(it) }

        val response = xmppConnection.sendIqAndGetResponse(request.build())

        if (response !is ConferenceModifiedIQ) {
            logger.error("Failed to expire: ${response?.javaClass?.name}: ${response?.toXML()}")
        }
    }

    private fun createRequest(create: Boolean = false) = ConferenceModifyIQ.builder(xmppConnection).apply {
        to(bridge.jid)
        setMeetingId(meetingId)
        if (create) setConferenceName(conferenceName)
    }
}

private fun ConferenceModifyIQ.Builder.addExpire(endpointId: String) = addEndpoint(
    Colibri2Endpoint.getBuilder().apply {
        setId(endpointId)
        setExpire(true)
    }.build()
)
