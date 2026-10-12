/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp.muc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.jitsi.jicofo.MediaType
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.xmpp.RoomMetadata
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.PresenceBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.util.Consumer
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.Occupant
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.concurrent.thread

class ChatRoomImplTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val roomJid = JidCreate.entityBareFrom("room@conference.example.com")
    private val xmppConnection = MockXmppConnection().xmppConnection
    private val xmppProvider: XmppProvider = mockk(relaxed = true) {
        every { xmppConnection } returns this@ChatRoomImplTest.xmppConnection
        every { config } returns mockk(relaxed = true) {
            every { username } returns Resourcepart.from("focus")
            every { replyTimeout } returns Duration.ofSeconds(15)
        }
    }

    /** The role/affiliation of each occupant, as would be reported by the MUC. */
    private val occupantMap = mutableMapOf<EntityFullJid, Occupant>()
    private val muc: MultiUserChat = mockk(relaxed = true) {
        every { getOccupant(any()) } answers { occupantMap[firstArg<EntityFullJid>()] }
    }

    private var left = false
    private val chatRoom by lazy { newChatRoom() }

    /** Create a [ChatRoomImpl] which uses the mock [muc]. */
    private fun newChatRoom(previousInstanceLeaveTimeout: Duration? = null): ChatRoomImpl {
        mockkStatic(MultiUserChatManager::class)
        val manager = mockk<MultiUserChatManager> {
            every { getMultiUserChat(any()) } returns muc
        }
        every { MultiUserChatManager.getInstanceFor(any()) } returns manager
        try {
            return ChatRoomImpl(xmppProvider, roomJid, Level.INFO, previousInstanceLeaveTimeout) { left = true }.apply {
                addListener(
                    object : ChatRoomListener {
                        override fun memberJoined(member: ChatRoomMember) {
                            joined.add(member)
                        }
                        override fun memberLeft(member: ChatRoomMember) {
                            leftMembers.add(member)
                        }
                        override fun memberKicked(member: ChatRoomMember) {
                            kicked.add(member)
                        }
                    }
                )
            }
        } finally {
            unmockkStatic(MultiUserChatManager::class)
        }
    }

    private val joined = mutableListOf<ChatRoomMember>()
    private val leftMembers = mutableListOf<ChatRoomMember>()
    private val kicked = mutableListOf<ChatRoomMember>()

    private fun addOccupant(jid: EntityFullJid, mucRole: MUCRole, mucAffiliation: MUCAffiliation) {
        occupantMap[jid] = mockk<Occupant> {
            every { role } returns mucRole
            every { affiliation } returns mucAffiliation
            every { this@mockk.jid } returns JidCreate.from("user@example.com")
        }
    }

    private fun memberPresence(
        nick: String,
        type: Presence.Type = Presence.Type.available,
        vararg extensions: ExtensionElement
    ): Presence = StanzaBuilder.buildPresence()
        .from(JidCreate.entityFullFrom("$roomJid/$nick"))
        .ofType(type)
        .build().apply {
            addExtension(MUCUser())
            extensions.forEach { addExtension(it) }
        }

    private fun join(nick: String, role: MUCRole = MUCRole.participant, vararg extensions: ExtensionElement) {
        addOccupant(JidCreate.entityFullFrom("$roomJid/$nick"), role, MUCAffiliation.none)
        chatRoom.processPresence(memberPresence(nick, Presence.Type.available, *extensions))
    }

    /** Build a presence to our own occupant JID, as Smack would when joining or leaving. */
    private fun ownPresence(type: Presence.Type) = xmppConnection.stanzaFactory.buildPresenceStanza()
        .ofType(type)
        .to(JidCreate.entityFullFrom("$roomJid/focus"))

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        // Joining blocks the XMPP task queue until the join completes, so the "Joining" tests need a real executor.
        if (testCase.descriptor.path().value.contains("Joining")) {
            TaskPools.resetIoPool()
        } else {
            TaskPools.ioPool = inPlaceExecutor
        }
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    private fun sourceInfo(nick: String, audioMuted: Boolean) = StandardExtensionElement
        .builder("SourceInfo", "jabber:client")
        .setText("""{"$nick-a0": {"muted": $audioMuted}}""")
        .build()

    init {
        context("Joining") {
            val interceptor = slot<Consumer<PresenceBuilder>>()
            every { muc.addPresenceInterceptor(capture(interceptor)) } returns Unit

            should("join the MUC once") {
                chatRoom.join()
                verify(exactly = 1) { muc.createOrJoin(Resourcepart.from("focus")) }
                verify(exactly = 0) { muc.leave() }
            }
            should("refuse to join a second time") {
                chatRoom.join()
                shouldThrow<IllegalStateException> { chatRoom.join() }
                verify(exactly = 1) { muc.createOrJoin(any<Resourcepart>()) }
                verify(exactly = 0) { muc.leave() }
            }
            should("wait for a previous instance to leave the MUC") {
                val stillJoined = AtomicBoolean(true)
                every { muc.isJoined } answers { stillJoined.get() }
                thread {
                    Thread.sleep(200)
                    stillJoined.set(false)
                }
                chatRoom.join()
                verify(exactly = 1) { muc.createOrJoin(any<Resourcepart>()) }
                // It must never leave the MUC on behalf of the previous instance.
                verify(exactly = 0) { muc.leave() }
            }
            should("give up when a previous instance does not leave the MUC") {
                every { muc.isJoined } returns true
                val room = newChatRoom(previousInstanceLeaveTimeout = Duration.ofMillis(200))
                shouldThrow<IllegalStateException> { room.join() }
                verify(exactly = 0) { muc.createOrJoin(any<Resourcepart>()) }
                verify(exactly = 0) { muc.leave() }
            }
            should("not use an unavailable presence as the base for presence updates") {
                chatRoom.join()
                // The join presence intercepted by smack.
                interceptor.captured.accept(ownPresence(Presence.Type.available))
                // An unavailable presence, as smack sends when it leaves the MUC.
                interceptor.captured.accept(ownPresence(Presence.Type.unavailable))

                val extension = StandardExtensionElement.builder("test", "test:ns").build()
                chatRoom.addPresenceExtensions(listOf(extension))

                val sent = slot<Stanza>()
                verify(exactly = 1) { xmppConnection.sendStanza(capture(sent)) }
                val presence = sent.captured.shouldBeInstanceOf<Presence>()
                presence.type shouldBe Presence.Type.available
                presence.getExtension<StandardExtensionElement>("test", "test:ns") shouldNotBe null
            }
        }
        context("Members joining") {
            join("member1")
            chatRoom.memberCount shouldBe 1
            joined.size shouldBe 1
            val member = chatRoom.getChatMember(JidCreate.entityFullFrom("$roomJid/member1"))!!
            member.name shouldBe "member1"
            member.role shouldBe MemberRole.PARTICIPANT

            context("A second member") {
                join("member2")
                chatRoom.memberCount shouldBe 2
                joined.size shouldBe 2
            }
            context("Repeated presence from the same member") {
                chatRoom.processPresence(memberPresence("member1"))
                chatRoom.memberCount shouldBe 1
                joined.size shouldBe 1
            }
        }
        context("Members leaving") {
            join("member1")
            join("member2")

            context("Normally") {
                chatRoom.processPresence(memberPresence("member1", Presence.Type.unavailable))
                chatRoom.memberCount shouldBe 1
                leftMembers.map { it.name } shouldBe listOf("member1")
                kicked.size shouldBe 0
            }
            context("Kicked") {
                chatRoom.processPresence(
                    memberPresence("member1", Presence.Type.unavailable).apply {
                        getExtension(MUCUser::class.java).addStatusCode(MUCUser.Status.KICKED_307)
                    }
                )
                chatRoom.memberCount shouldBe 1
                kicked.map { it.name } shouldBe listOf("member1")
                leftMembers.size shouldBe 0
            }
        }
        context("Visitors") {
            join("visitor1", role = MUCRole.visitor)
            join("member1")

            chatRoom.memberCount shouldBe 2
            chatRoom.visitorCount shouldBe 1
            chatRoom.getChatMember(JidCreate.entityFullFrom("$roomJid/visitor1"))!!.role shouldBe MemberRole.VISITOR

            chatRoom.processPresence(memberPresence("visitor1", Presence.Type.unavailable))
            chatRoom.visitorCount shouldBe 0
        }
        context("Sender counts") {
            chatRoom.audioSendersCount shouldBe 0
            join("member1", MUCRole.participant, sourceInfo("member1", audioMuted = false))
            chatRoom.audioSendersCount shouldBe 1

            context("Muting") {
                chatRoom.processPresence(memberPresence("member1", extensions = arrayOf(sourceInfo("member1", true))))
                chatRoom.audioSendersCount shouldBe 0
            }
            context("Leaving while unmuted") {
                chatRoom.processPresence(memberPresence("member1", Presence.Type.unavailable))
                chatRoom.audioSendersCount shouldBe 0
            }
        }
        context("AV moderation") {
            val jid = JidCreate.from("someone@example.com")
            chatRoom.isAvModerationEnabled(MediaType.AUDIO) shouldBe false
            chatRoom.isMemberAllowedToUnmute(jid, MediaType.AUDIO) shouldBe true

            chatRoom.setAvModerationEnabled(MediaType.AUDIO, true)
            chatRoom.isAvModerationEnabled(MediaType.AUDIO) shouldBe true
            chatRoom.isAvModerationEnabled(MediaType.VIDEO) shouldBe false
            chatRoom.isMemberAllowedToUnmute(jid, MediaType.AUDIO) shouldBe false

            chatRoom.setAvModerationWhitelist(MediaType.AUDIO, listOf(jid.toString()))
            chatRoom.isMemberAllowedToUnmute(jid, MediaType.AUDIO) shouldBe true
            chatRoom.isMemberAllowedToUnmute(JidCreate.from("other@example.com"), MediaType.AUDIO) shouldBe false
        }
        context("Leaving the room") {
            chatRoom.leave()
            left shouldBe true
            // Not joined, so a single unavailable presence is sent to avoid leaking the MUC on the server.
            verify(exactly = 1) { xmppConnection.sendStanza(any()) }

            should("have no effect when called again") {
                chatRoom.leave()
                verify(exactly = 1) { xmppConnection.sendStanza(any()) }
            }
        }
        context("Room metadata") {
            chatRoom.setRoomMetadata(
                RoomMetadata(
                    metadata = RoomMetadata.Metadata(
                        visitors = RoomMetadata.Metadata.Visitors(live = true),
                        startMuted = null,
                        moderators = null,
                        participants = null,
                        recording = null,
                        participantsSoftLimit = 30
                    )
                )
            )
            chatRoom.visitorsLive shouldBe true
            chatRoom.participantsSoftLimit shouldBe 30
        }
    }
}
