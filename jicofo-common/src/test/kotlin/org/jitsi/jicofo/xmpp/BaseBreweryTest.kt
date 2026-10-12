/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
package org.jitsi.jicofo.xmpp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomInfo
import org.jitsi.jicofo.xmpp.muc.ChatRoomListener
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.utils.logging2.LoggerImpl
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class BaseBreweryTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val breweryJid = JidCreate.entityBareFrom("brewery@internal.example.com")

    /** The rooms created through [xmppProvider], in order. */
    private val rooms = mutableListOf<TestRoom>()

    /** The occupants that the next room created through [xmppProvider] has. */
    private var nextRoomMembers = emptySet<EntityFullJid>()

    private var registered = true
    private val xmppProvider: XmppProvider = mockk(relaxed = true) {
        every { registered } answers { this@BaseBreweryTest.registered }
        every { createRoom(any(), any()) } answers {
            TestRoom(breweryJid, nextRoomMembers).also { rooms.add(it) }.chatRoom
        }
    }

    private val brewery = TestBrewery(xmppProvider, breweryJid)

    private fun occupantJid(nick: String) = JidCreate.entityFullFrom("$breweryJid/$nick")

    init {
        afterAny { brewery.shutdown() }

        context("Joining") {
            brewery.init()
            rooms.size shouldBe 1
            rooms[0].listeners.size shouldBe 1

            rooms[0].addMember("instance1")
            rooms[0].addMember("instance2")
            brewery.instanceCount shouldBe 2
            brewery.isJoined shouldBe true

            should("not re-join while the room is joined") {
                brewery.checkJoined()
                rooms.size shouldBe 1
                brewery.instanceCount shouldBe 2
            }
            should("not re-join while not registered") {
                rooms[0].joined = false
                registered = false
                brewery.checkJoined()
                rooms.size shouldBe 1
            }
            should("re-join when the room is no longer joined") {
                rooms[0].joined = false
                brewery.isJoined shouldBe false
                // instance1 is still in the room when we re-join, instance2 is not.
                nextRoomMembers = setOf(occupantJid("instance1"))

                brewery.checkJoined()

                rooms.size shouldBe 2
                rooms[0].left shouldBe true
                rooms[0].listeners.size shouldBe 0
                rooms[1].listeners.size shouldBe 1
                brewery.isJoined shouldBe true
                brewery.instanceCount shouldBe 1
                brewery.offline shouldContainExactly listOf(occupantJid("instance2"))
            }
        }
    }

    private class TestBrewery(xmppProvider: XmppProvider, breweryJid: EntityBareJid) :
        BaseBrewery<StandardExtensionElement>(
            xmppProvider,
            breweryJid,
            EXT_ELEMENT,
            EXT_NAMESPACE,
            LoggerImpl("test")
        ) {
        val offline = mutableListOf<Jid>()
        override fun onInstanceStatusChanged(jid: EntityFullJid, status: StandardExtensionElement) {}
        override fun notifyInstanceOffline(jid: Jid) {
            offline.add(jid)
        }
    }

    /** A mock [ChatRoom] with the state that [BaseBrewery] uses. */
    private class TestRoom(val roomJid: EntityBareJid, members: Set<EntityFullJid>) {
        var joined = true
        var left = false
        val listeners = mutableListOf<ChatRoomListener>()
        private val members = members.associateWith { createMember(it) }.toMutableMap()
        val chatRoom: ChatRoom = mockk(relaxed = true) {
            every { roomJid } returns this@TestRoom.roomJid
            every { isJoined } answers { joined }
            every { join() } returns ChatRoomInfo(meetingId = null, mainRoomJid = null)
            every { leave() } answers { left = true }
            every { addListener(capture(listeners)) } returns Unit
            every { removeListener(any()) } answers {
                listeners.remove(firstArg<ChatRoomListener>())
                Unit
            }
            every { getChatMember(any()) } answers { this@TestRoom.members[firstArg<EntityFullJid>()] }
        }

        /** Add a member to the room and notify the listeners, as when its presence is received. */
        fun addMember(nick: String) {
            val member = createMember(JidCreate.entityFullFrom("$roomJid/$nick"))
            members[member.occupantJid] = member
            listeners.toList().forEach { it.memberJoined(member) }
        }

        private fun createMember(jid: EntityFullJid): ChatRoomMember {
            val memberPresence = StanzaBuilder.buildPresence().from(jid).build().apply {
                addExtension(StandardExtensionElement.builder(EXT_ELEMENT, EXT_NAMESPACE).build())
            }
            return mockk(relaxed = true) {
                every { occupantJid } returns jid
                every { presence } returns memberPresence
            }
        }
    }

    companion object {
        const val EXT_ELEMENT = "status"
        const val EXT_NAMESPACE = "test:ns"
    }
}
