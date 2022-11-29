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
package org.jitsi.jicofo.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.ConferenceConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidationFailedException
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.MockXmppProvider
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.xmpp.jingle.JingleSession
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
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.logging.Level

class ConferenceTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    init {
        val roomName = JidCreate.entityBareFrom("test@example.com")

        val xmppConnection = ColibriAndJingleXmppConnection()
        val jingleSessions = mutableListOf<JingleSession>()
        val xmppProvider = MockXmppProvider(
            xmppConnection.xmppConnection,
            mockk(relaxed = true) {
                every { registerSession(capture(jingleSessions)) } returns Unit
            }
        )
        val chatRoom = xmppProvider.getRoom(roomName)

        var ended = false
        val conference = JitsiMeetConferenceImpl(
            roomName,
            mockk {
                every { conferenceEnded(any()) } answers { ended = true }
            },
            mockk(relaxed = true),
            Level.INFO,
            null,
            false,
            mockk(relaxed = true) {
                every { xmppServices } returns mockk(relaxed = true) {
                    every { clientConnection } returns xmppProvider.xmppProvider
                    every { serviceConnection } returns xmppProvider.xmppProvider
                    every { bridgeSelector } returns mockk(relaxed = true) {
                        every { selectBridge(any(), any(), any()) } returns mockk(relaxed = true) {
                            every { jid } returns JidCreate.from("jvb@example.com/jvb1")
                        }
                    }
                }
            }
        ).apply { start() }

        context("Test inviting 2 participants initially") {
            // Simulate occupants entering the MUC
            val member1 = chatRoom.addMember("member1")
            val member2 = chatRoom.addMember("member2")

            conference.participantCount shouldBe 2

            val participant1 = conference.getParticipant(member1.occupantJid)!!
            val participant2 = conference.getParticipant(member2.occupantJid)!!

            val remoteParticipant1 = xmppConnection.remoteParticipants[member1.occupantJid]!!
            val remoteParticipant2 = xmppConnection.remoteParticipants[member2.occupantJid]!!

            val jingleSession1 = jingleSessions.find { it.sid == remoteParticipant1.sessionInitiate.sid }!!
            val jingleSession2 = jingleSessions.find { it.sid == remoteParticipant2.sessionInitiate.sid }!!

            jingleSession1.processIq(remoteParticipant1.createSessionAccept())
            jingleSession2.processIq(remoteParticipant2.createSessionAccept())

            participant1.jingleSession shouldNotBe null
            participant2.jingleSession shouldNotBe null

            // They should have 1 audio and 1 video source each
            participant1.sources.sources.size shouldBe 2
            participant2.sources.sources.size shouldBe 2

            context("And then inviting one more") {
                val member3 = chatRoom.addMember("member3")
                conference.participantCount shouldBe 3
                val remoteParticipant3 = xmppConnection.remoteParticipants[member3.occupantJid]!!
                val jingleSession3 = jingleSessions.find { it.sid == remoteParticipant3.sessionInitiate.sid }!!
                jingleSession3.processIq(remoteParticipant3.createSessionAccept())
            }
            context("And then leaving") {
                chatRoom.removeMember(member1)
                conference.participantCount shouldBe 1
                ended shouldBe false

                chatRoom.removeMember(member2)
                conference.participantCount shouldBe 0
                ended shouldBe true
            }
        }
        context("Test inviting more than 2 initially") {
            val members = buildList {
                repeat(5) { i ->
                    add(chatRoom.addMember("member-$i"))
                }
            }

            conference.participantCount shouldBe 5
            members.forEach { member ->
                val remoteParticipant = xmppConnection.remoteParticipants[member.occupantJid]!!
                val jingleSession = jingleSessions.find { it.sid == remoteParticipant.sessionInitiate.sid }!!
                jingleSession.processIq(remoteParticipant.createSessionAccept())
            }

            members.forEach { member ->
                val participant = conference.getParticipant(member.occupantJid)!!
                participant.jingleSession shouldNotBe null
                participant.sources.sources.size shouldBe 2
            }

            members.forEach { chatRoom.removeMember(it) }
            conference.participantCount shouldBe 0
            ended shouldBe true
        }
        context("Test participants leaving before accepting a session") {
            val members = buildList {
                repeat(5) { i ->
                    add(chatRoom.addMember("member-$i"))
                }
            }

            conference.participantCount shouldBe 5
            members.take(3).forEach { member ->
                val remoteParticipant = xmppConnection.remoteParticipants[member.occupantJid]!!
                val jingleSession = jingleSessions.find { it.sid == remoteParticipant.sessionInitiate.sid }!!
                jingleSession.processIq(remoteParticipant.createSessionAccept())
            }
            members.drop(3).forEach { chatRoom.removeMember(it) }

            conference.participantCount shouldBe 3

            members.take(3).forEach { member ->
                val participant = conference.getParticipant(member.occupantJid)!!
                participant.jingleSession shouldNotBe null
                participant.sources.sources.size shouldBe 2
            }

            members.take(3).forEach { chatRoom.removeMember(it) }
            conference.participantCount shouldBe 0
            ended shouldBe true
        }
        context("Sender limits") {
            withNewConfig("jicofo.conference.max-video-senders=5, jicofo.conference.max-audio-senders=5") {
                context("Configuration") {
                    ConferenceConfig.config.maxVideoSenders shouldBe 5
                    ConferenceConfig.config.maxAudioSenders shouldBe 5
                }
                chatRoom.chatRoom.apply { every { videoSendersCount } returns 0 }
                chatRoom.chatRoom.apply { every { audioSendersCount } returns 0 }
                var ssrcs = 1L
                fun nextSource(mediaType: MediaType) = EndpointSourceSet(Source(ssrcs++, mediaType))

                context("Video") {
                    val videoSource = nextSource(MediaType.VIDEO)
                    conference.addSource(mockk(relaxed = true), videoSource)
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), videoSource)
                    }
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), EndpointSourceSet(Source(-1, MediaType.VIDEO)))
                    }

                    chatRoom.chatRoom.apply { every { videoSendersCount } returns 10 }
                    shouldThrow<JitsiMeetConferenceImpl.SenderCountExceededException> {
                        conference.addSource(mockk(relaxed = true), nextSource(MediaType.VIDEO))
                    }

                    chatRoom.chatRoom.apply { every { videoSendersCount } returns 2 }
                    conference.addSource(mockk(relaxed = true), nextSource(MediaType.VIDEO))
                }
                context("Audio") {
                    val audioSource = nextSource(MediaType.AUDIO)
                    conference.addSource(mockk(relaxed = true), audioSource)
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), audioSource)
                    }
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), EndpointSourceSet(Source(-1, MediaType.AUDIO)))
                    }

                    chatRoom.chatRoom.apply { every { audioSendersCount } returns 10 }
                    shouldThrow<JitsiMeetConferenceImpl.SenderCountExceededException> {
                        conference.addSource(mockk(relaxed = true), nextSource(MediaType.AUDIO))
                    }

                    chatRoom.chatRoom.apply { every { audioSendersCount } returns 2 }
                    conference.addSource(mockk(relaxed = true), nextSource(MediaType.AUDIO))
                }
            }
        }
    }
}

// Execute tasks in place (in the current thread, blocking)
val inPlaceExecutor: ExecutorService = mockk {
    every { submit(any()) } answers {
        firstArg<Runnable>().run()
        CompletableFuture<Unit>()
    }
    every { execute(any()) } answers {
        firstArg<Runnable>().run()
    }
}

/**
 * Mocks an [AbstractXMPPConnection] which responds to colibri2 and Jingle IQs. Creates [RemoteParticipant]s that moodel
 * the remote side of a Jingle session.
 */
class ColibriAndJingleXmppConnection : MockXmppConnection() {
    var ssrcs = 1L
    val colibri2Server = TestColibri2Server()
    val remoteParticipants = mutableMapOf<Jid, RemoteParticipant>()

    override fun handleIq(iq: IQ): IQ? = when (iq) {
        is ConferenceModifyIQ -> colibri2Server.handleConferenceModifyIq(iq)
        is JingleIQ -> remoteParticipants.computeIfAbsent(iq.to) { RemoteParticipant(iq.to) }.handleJingleIq(iq)
        else -> {
            println("Not handling ${iq.toXML()}")
            null
        }
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
