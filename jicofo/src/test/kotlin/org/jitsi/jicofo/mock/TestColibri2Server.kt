/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022-Present 8x8, Inc.
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

package org.jitsi.jicofo.mock

import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StanzaError.Condition.bad_request
import org.jivesoftware.smack.packet.StanzaError.Condition.conflict
import org.jivesoftware.smack.packet.StanzaError.Condition.feature_not_implemented
import org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found
import java.lang.Exception
import kotlin.jvm.Throws
import kotlin.random.Random

class TestColibri2Server {
    private val conferences = mutableMapOf<String, Conference>()

    fun stop() = conferences.clear()

    fun handleConferenceModifyIq(request: ConferenceModifyIQ): IQ {
        val conference: Conference = if (request.create) {
            if (conferences.containsKey(request.meetingId)) {
                return createError(request, conflict, "Conference already exists")
            }
            val newConference = Conference(request.meetingId)
            conferences[request.meetingId] = newConference
            newConference
        } else {
            conferences[request.meetingId]
                ?: return createError(request, item_not_found, "Conference not found")
        }

        return try {
            conference.handleRequest(request)
        } catch (e: IqProcessingException) {
            // Make sure to tag the error as coming from a bridge, otherwise item_not_found may be misiterpreted.
            return createError(request, e.condition, e.message, Colibri2Error())
        }
    }
    private fun expireConference(meetingId: String) = conferences.remove(meetingId)

    private inner class Conference(val meetingId: String) {
        val endpoints = mutableMapOf<String, Endpoint>()

        @Throws(IqProcessingException::class)
        fun handleRequest(request: ConferenceModifyIQ): IQ {
            val responseBuilder =
                ConferenceModifiedIQ.builder(ConferenceModifiedIQ.Builder.createResponse(request))

            if (request.create) {
                responseBuilder.setSources(buildFeedbackSources(Random.nextLong(), Random.nextLong()))
            }

            request.endpoints.forEach { responseBuilder.addEndpoint(handleEndpoint(it)) }
            if (request.relays.isNotEmpty()) {
                throw RuntimeException("Relays not implemented")
            }

            if (!request.create && endpoints.isEmpty()) {
                expireConference(meetingId)
            }

            return responseBuilder.build()
        }

        private fun expireEndpoint(id: String) = endpoints.remove(id)

        private fun handleEndpoint(c2endpoint: Colibri2Endpoint): Colibri2Endpoint {
            val respBuilder = Colibri2Endpoint.getBuilder().apply { setId(c2endpoint.id) }
            if (c2endpoint.expire) {
                expireEndpoint(c2endpoint.id)
                respBuilder.setExpire(true)
                return respBuilder.build()
            }

            val endpoint = if (c2endpoint.create) {
                if (endpoints.containsKey(c2endpoint.id)) {
                    throw IqProcessingException(conflict, "Endpoint with ID ${c2endpoint.id} already exists")
                }
                val transport = c2endpoint.transport ?: throw IqProcessingException(
                    bad_request,
                    "Attempt to create endpoint ${c2endpoint.id} with no <transport>"
                )
                val newEndpoint = Endpoint(c2endpoint.id).apply {
                    transport.sctp?.let { sctp ->
                        if (sctp.role != null && sctp.role != Sctp.Role.SERVER) {
                            throw IqProcessingException(
                                feature_not_implemented,
                                "Unsupported SCTP role: ${sctp.role}"
                            )
                        }
                        if (sctp.port != null) {
                            throw IqProcessingException(bad_request, "Specific SCTP port requested, not supported.")
                        }

                        useSctp = true
                    }
                }
                endpoints[c2endpoint.id] = newEndpoint
                newEndpoint
            } else {
                endpoints[c2endpoint.id] ?: throw IqProcessingException(
                    item_not_found,
                    "Unknown endpoint ${c2endpoint.id}"
                )
            }

            // c2endpoint.transport?.iceUdpTransport?.let { endpoint.setTransportInfo(it) }
            if (c2endpoint.create) {
                val transBuilder = Transport.getBuilder()
                transBuilder.setIceUdpExtension(endpoint.describeTransport())
                if (c2endpoint.transport?.sctp != null) {
                    transBuilder.setSctp(
                        Sctp.Builder()
                            .setPort(5000)
                            .setRole(Sctp.Role.SERVER)
                            .build()
                    )
                }
                respBuilder.setTransport(transBuilder.build())
            }

            c2endpoint.forceMute?.let {
                endpoint.forceMuteAudio = it.audio
                endpoint.forceMuteVideo = it.video
            }

            return respBuilder.build()
        }

        private inner class Endpoint(val id: String) {
            var useSctp = false
            var forceMuteAudio = false
            var forceMuteVideo = false

            fun describeTransport() = IceUdpTransportPacketExtension().apply {
                password = "password-$meetingId-$id"
                ufrag = "ufrag-$meetingId-$id"
                // TODO add some candidates?
                addChildExtension(
                    DtlsFingerprintPacketExtension().apply {
                        hash = "sha-256"
                        text = "AC:58:2D:03:40:89:87:30:6C:25:2C:50:17:5C:5C:2E:" +
                            "1F:A1:19:19:4D:74:A5:37:35:22:6E:8E:DF:55:13:8E"
                    }
                )
            }
        }
    }
}

private class IqProcessingException(
    val condition: StanzaError.Condition,
    message: String
) : Exception(message) {
    override fun toString() = "$condition $message"
}

private fun buildFeedbackSources(localAudioSsrc: Long, localVideoSsrc: Long): Sources = Sources.getBuilder().apply {
    addMediaSource(
        MediaSource.getBuilder()
            .setType(MediaType.AUDIO)
            .setId("jvb-a0")
            .addSource(
                SourcePacketExtension().apply {
                    ssrc = localAudioSsrc
                    name = "jvb-a0"
                }
            )
            .build()
    )
    addMediaSource(
        MediaSource.getBuilder()
            .setType(MediaType.VIDEO)
            .setId("jvb-v0")
            .addSource(
                SourcePacketExtension().apply {
                    ssrc = localVideoSsrc
                    name = "jvb-v0"
                }
            )
            .build()
    )
}.build()

fun createError(
    request: IQ,
    errorCondition: StanzaError.Condition,
    errorMessage: String? = null,
    extension: ExtensionElement? = null
): IQ {
    val error = StanzaError.getBuilder(errorCondition)
    errorMessage?.let { error.setDescriptiveEnText(it) }
    extension?.let {
        error.setExtensions(listOf(it))
    }

    return IQ.createErrorResponse(request, error.build())
}
