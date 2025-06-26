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
import org.jitsi.jicofo.conference.source.EndpointSourceSet.Companion.fromJingle
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidationFailedException
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.MockXmppProvider
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.xmpp.jingle.JingleSession
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
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
import shouldBeValidJson
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.logging.Level

class ConferenceTest : ShouldSpec() {
    private val roomName = JidCreate.entityBareFrom("test@example.com")
    private val xmppConnection = ColibriAndJingleXmppConnection()
    private val jingleSessions = mutableListOf<JingleSession>()
    private val xmppProvider = MockXmppProvider(xmppConnection.xmppConnection)
    private val chatRoom = xmppProvider.getRoom(roomName)

    private var memberCounter = 1
    private fun nextMemberId() = "member-${memberCounter++}"

    private var ended = false
    private val conference = JitsiMeetConferenceImpl(
        roomName,
        mockk {
            every { conferenceEnded(any()) } answers { ended = true }
            every { meetingIdSet(any(), any()) } returns true
        },
        HashMap(),
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
                        every { debugState } returns OrderedJsonObject()
                    }
                }
                every { jingleHandler } returns mockk(relaxed = true) {
                    every { registerSession(capture(jingleSessions)) } returns Unit
                }
            }
        }
    ).apply { start() }

    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    private fun addParticipants(n: Int): List<ChatRoomMember> {
        val members = buildList { repeat(n) { add(chatRoom.addMember(nextMemberId())) } }

        members.forEach { member ->
            val remoteParticipant = xmppConnection.remoteParticipants[member.occupantJid]!!
            val jingleSession = jingleSessions.find { it.sid == remoteParticipant.sessionInitiate.sid }!!
            jingleSession.processIq(remoteParticipant.createSessionAccept())
        }
        return members
    }

    private fun ChatRoomMember.getParticipant() = conference.getParticipant(occupantJid)
    private fun ChatRoomMember.getRemoteParticipant() = xmppConnection.remoteParticipants[occupantJid]

    init {
        context("Test inviting 2 participants initially") {
            // Simulate occupants entering the MUC
            val member1 = chatRoom.addMember("member1")
            val member2 = chatRoom.addMember("member2")

            conference.participantCount shouldBe 2

            val participant1 = member1.getParticipant()!!
            val participant2 = member2.getParticipant()!!

            val remoteParticipant1 = member1.getRemoteParticipant()!!
            val remoteParticipant2 = member2.getRemoteParticipant()!!

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
                addParticipants(1)
                conference.participantCount shouldBe 3
            }
            context("Single participant timeout") {
                chatRoom.removeMember(member1)
                // The single-participant timeout should execute immediately and end the jingle session
                conference.participantCount shouldBe 0
                ended shouldBe false

                chatRoom.removeMember(member2)
                conference.participantCount shouldBe 0
                ended shouldBe true
            }
        }
        context("Test inviting more than 2 initially") {
            val members = addParticipants(5)
            conference.participantCount shouldBe 5

            members.forEach { member ->
                member.getParticipant().let {
                    it shouldNotBe null
                    it!!.jingleSession shouldNotBe null
                    it.sources.sources.size shouldBe 2
                }
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
        context("Muting") {
            val members = addParticipants(2)
            val muter = members[0].getParticipant()!!
            val mutee = members[1].getParticipant()!!
            fun mute() = conference.handleMuteRequest(
                muter.mucJid,
                mutee.mucJid,
                true,
                org.jitsi.jicofo.MediaType.AUDIO
            )
            fun unmute() = conference.handleMuteRequest(
                muter.mucJid,
                mutee.mucJid,
                false,
                org.jitsi.jicofo.MediaType.VIDEO
            )

            context("When the muter is an owner") {
                every { muter.chatMember.role } returns MemberRole.OWNER
                mute() shouldBe MuteResult.SUCCESS
                unmute() shouldBe MuteResult.NOT_ALLOWED
            }
            context("When the muter is a moderator") {
                every { muter.chatMember.role } returns MemberRole.MODERATOR
                mute() shouldBe MuteResult.SUCCESS
                unmute() shouldBe MuteResult.NOT_ALLOWED
            }
            context("When the muter is a participant") {
                every { muter.chatMember.role } returns MemberRole.PARTICIPANT
                mute() shouldBe MuteResult.NOT_ALLOWED
                unmute() shouldBe MuteResult.NOT_ALLOWED
            }
            context("When the muter is a visitor") {
                every { muter.chatMember.role } returns MemberRole.VISITOR
                mute() shouldBe MuteResult.NOT_ALLOWED
                unmute() shouldBe MuteResult.NOT_ALLOWED
            }
        }
        context("Signaling sources") {
            val members = addParticipants(2)
            val participants = members.map { it.getParticipant()!! }
            val remoteParticipants = members.map { it.getRemoteParticipant()!! }
            participants.forEach { it.sources.sources.size shouldBe 2 }

            val member3 = addParticipants(1)[0]
            val participant3 = member3.getParticipant()!!
            val remoteParticipant3 = member3.getRemoteParticipant()!!
            val jingleSession3 = participant3.jingleSession!!
            participant3.sources.sources.size shouldBe 2

            remoteParticipants.forEach {
                val lastJingleMessageSent = it.requests.last()
                lastJingleMessageSent.action shouldBe JingleAction.SOURCEADD
                fromJingle(lastJingleMessageSent.contentList) shouldBe participant3.sources
            }

            // Add an additional source
            val newSource = EndpointSourceSet(remoteParticipant3.nextSource(MediaType.VIDEO))
            jingleSession3.processIq(remoteParticipant3.createSourceAdd(newSource))
            remoteParticipants.forEach {
                val lastJingleMessageSent = it.requests.last()
                lastJingleMessageSent.action shouldBe JingleAction.SOURCEADD
                fromJingle(lastJingleMessageSent.contentList) shouldBe newSource
            }

            // Now remove it
            jingleSession3.processIq(remoteParticipant3.createSourceRemove(newSource))
            remoteParticipants.forEach {
                val lastJingleMessageSent = it.requests.last()
                lastJingleMessageSent.action shouldBe JingleAction.SOURCEREMOVE
                fromJingle(lastJingleMessageSent.contentList) shouldBe newSource
            }

            context("Adding sources already signaled") {
                val sources = remoteParticipant3.sources
                jingleSession3.processIq(remoteParticipant3.createSourceAdd(sources))

                xmppConnection.requests.last().type shouldBe IQ.Type.error
            }
            context("Adding sources used by another participant") {
                val sources = remoteParticipants[1].sources
                jingleSession3.processIq(remoteParticipant3.createSourceAdd(sources))

                xmppConnection.requests.last().type shouldBe IQ.Type.error
            }
            context("Adding invalid sources") {
                jingleSession3.processIq(remoteParticipant3.createSourceAdd(remoteParticipant3.sources))

                xmppConnection.requests.last().type shouldBe IQ.Type.error
            }
            context("A participant leaving") {
                val newSource2 = EndpointSourceSet(remoteParticipant3.nextSource(MediaType.AUDIO))
                jingleSession3.processIq(remoteParticipant3.createSourceAdd(newSource2))
                remoteParticipants.forEach {
                    val lastJingleMessageSent = it.requests.last()
                    lastJingleMessageSent.action shouldBe JingleAction.SOURCEADD
                    fromJingle(lastJingleMessageSent.contentList) shouldBe newSource2
                }

                chatRoom.removeMember(member3)
                remoteParticipants.forEach {
                    val lastJingleMessageSent = it.requests.last()
                    // A source-remove should NOT have been sent on leave.
                    lastJingleMessageSent.action shouldBe JingleAction.SOURCEADD
                }
            }
        }
        context("Debug state") {
            conference.debugState.shouldBeValidJson()

            val members = addParticipants(5)

            conference.debugState.shouldBeValidJson()
            members.map { it.getParticipant()!! }.forEach {
                it.getDebugState(true).shouldBeValidJson()
            }
        }
    }
}

// Execute tasks in place (in the current thread, blocking)
val inPlaceExecutor: ExecutorService = mockk {
    every { submit(any<Runnable>()) } answers {
        firstArg<Runnable>().run()
        CompletableFuture<Unit>().apply {
            complete(Unit)
        }
    }
    every { execute(any()) } answers {
        firstArg<Runnable>().run()
    }
}

val inPlaceScheduledExecutor: ScheduledExecutorService = mockk {
    every { schedule(any(), any(), any()) } answers {
        firstArg<Runnable>().run()
        mockk(relaxed = true) {
            every { isDone } returns true
        }
    }
}

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
