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
package org.jitsi.jicofo.xmpp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.util.ListConferenceStore
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jxmpp.jid.impl.JidCreate

class RoomMetadataHandlerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val roomJid = JidCreate.entityBareFrom("conf1@conference.example.com")
    private val componentAddress = "metadata.example.com"

    private val chatRoom: ChatRoom = mockk(relaxed = true)
    private val conference: JitsiMeetConference = mockk(relaxed = true) {
        every { roomName } returns roomJid
        every { chatRoom } returns this@RoomMetadataHandlerTest.chatRoom
    }
    private val conferenceStore = ListConferenceStore().apply { add(conference) }

    private val xmppProvider: XmppProvider = mockk(relaxed = true) {
        every { components } returns setOf(XmppProvider.Component("room_metadata", componentAddress))
    }

    private val handler = RoomMetadataHandler(xmppProvider, conferenceStore)

    private fun process(json: String, room: String? = roomJid.toString(), from: String = componentAddress) =
        handler.processStanza(
            StanzaBuilder.buildMessage()
                .from(JidCreate.from(from))
                .addExtension(JsonMessageExtension(json).apply { room?.let { setAttribute("room", it) } })
                .build()
        )

    init {
        context("A valid message") {
            val metadata = slot<RoomMetadata>()
            every { chatRoom.setRoomMetadata(capture(metadata)) } returns Unit

            process(
                """
                {"type":"room_metadata", "metadata": {"visitors": {"live": true}, "participantsSoftLimit": 50,
                 "moderators": ["m1"]}}
                """.trimIndent()
            )

            metadata.captured.metadata?.visitors?.live shouldBe true
            metadata.captured.metadata?.participantsSoftLimit shouldBe 50
            metadata.captured.metadata?.moderators shouldBe listOf("m1")
        }
        context("Messages that should be ignored") {
            should("ignore messages from another address") {
                process("""{"type":"room_metadata", "metadata": {}}""", from = "attacker.example.com")
                verify(exactly = 0) { chatRoom.setRoomMetadata(any()) }
            }
            should("ignore messages without a room attribute") {
                process("""{"type":"room_metadata", "metadata": {}}""", room = null)
                verify(exactly = 0) { chatRoom.setRoomMetadata(any()) }
            }
            should("ignore messages for an unknown room") {
                process("""{"type":"room_metadata", "metadata": {}}""", room = "other@conference.example.com")
                verify(exactly = 0) { chatRoom.setRoomMetadata(any()) }
            }
            should("ignore invalid JSON") {
                process("not json")
                process("""{"type":"av_moderation", "room":"$roomJid"}""")
                verify(exactly = 0) { chatRoom.setRoomMetadata(any()) }
            }
        }
    }
}
