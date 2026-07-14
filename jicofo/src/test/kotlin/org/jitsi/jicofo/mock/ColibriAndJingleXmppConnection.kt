/*
 * Copyright @ 2021 - present 8x8, Inc.
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

import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceRtcpmuxPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid

/**
 * Mocks an [AbstractXMPPConnection] which responds to colibri2 and Jingle IQs. Creates [RemoteParticipant]s that model
 * the remote side of a Jingle session.
 */
class ColibriAndJingleXmppConnection : MockXmppConnection() {
    var ssrcs = 1L
    val colibri2Server = TestColibri2Server()
    val remoteParticipants = mutableMapOf<Jid, RemoteParticipant>()

    // IQs sent by jicofo
    val requests = mutableListOf<IQ>()

    override fun handleIq(iq: IQ): IQ? = when (iq) {
        is ConferenceModifyIQ -> colibri2Server.handleConferenceModifyIq(iq)
        is JingleIQ -> remoteParticipants.computeIfAbsent(iq.to) { RemoteParticipant(iq.to) }.handleJingleIq(iq)
        else -> {
            println("Not handling ${iq.toXML()}")
            null
        }
    }.also {
        requests.add(iq)
    }

    private fun nextSource(mediaType: MediaType) = Source(ssrcs++, mediaType)

    /**
     *  Model the remote side of a [Participant], i.e. the entity that would respond to Jingle requests sent from
     *  jicofo.
     */
    inner class RemoteParticipant(jid: Jid) {
        var sources = EndpointSourceSet(setOf(nextSource(MediaType.AUDIO), nextSource(MediaType.VIDEO)))
        val requests = mutableListOf<JingleIQ>()
        fun handleJingleIq(iq: JingleIQ) = IQ.createResultIQ(iq).also { requests.add(iq) }

        val sessionInitiate: JingleIQ
            get() = requests.find { it.action == JingleAction.SESSION_INITIATE }
                ?: throw IllegalStateException("session-initiate not received")

        fun createSourceAdd(sources: EndpointSourceSet) = JingleIQ(JingleAction.SOURCEADD, sessionInitiate.sid).apply {
            from = sessionInitiate.to
            type = IQ.Type.set
            to = sessionInitiate.from
            sources.toJingle().forEach { addContent(it) }
        }
        fun createSourceRemove(sources: EndpointSourceSet) =
            JingleIQ(JingleAction.SOURCEREMOVE, sessionInitiate.sid).apply {
                from = sessionInitiate.to
                type = IQ.Type.set
                to = sessionInitiate.from
                sources.toJingle().forEach { addContent(it) }
            }

        fun nextSource(mediaType: MediaType) = this@ColibriAndJingleXmppConnection.nextSource(mediaType)

        fun createSessionAccept(): JingleIQ {
            val accept = JingleIQ(JingleAction.SESSION_ACCEPT, sessionInitiate.sid).apply {
                type = IQ.Type.set
                from = sessionInitiate.to
                to = sessionInitiate.from
            }

            val audioContent = ContentPacketExtension().apply {
                name = "audio"
                creator = ContentPacketExtension.CreatorEnum.responder // xxx
                addChildExtension(RtpDescriptionPacketExtension().apply { media = "audio" })
                sources.sources.filter { it.mediaType == MediaType.AUDIO }.forEach {
                    addChildExtension(it.toPacketExtension())
                }
            }

            val videoContent = ContentPacketExtension().apply {
                name = "video"
                creator = ContentPacketExtension.CreatorEnum.responder // xxx
                addChildExtension(RtpDescriptionPacketExtension().apply { media = "video" })
                sources.sources.filter { it.mediaType == MediaType.VIDEO }.forEach {
                    addChildExtension(it.toPacketExtension())
                }
            }

            videoContent.addChildExtension(createTransport(sessionInitiate))

            accept.addContent(audioContent)
            accept.addContent(videoContent)

            return accept
        }
    }
}

private fun createTransport(sessionInitiate: JingleIQ) = IceUdpTransportPacketExtension().apply {
    addChildExtension(IceRtcpmuxPacketExtension())
    addChildExtension(
        DtlsFingerprintPacketExtension().apply {
            val offerFp = sessionInitiate.contentList.firstNotNullOf {
                it.getFirstChildOfType(IceUdpTransportPacketExtension::class.java)
            }.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java)

            hash = offerFp.hash
            fingerprint = offerFp.fingerprint
                .replace("A", "B")
                .replace("1", "2")
                .replace("C", "D")
        }
    )
}
