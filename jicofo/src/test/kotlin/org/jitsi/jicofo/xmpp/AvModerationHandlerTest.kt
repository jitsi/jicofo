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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.jicofo.MediaType
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.util.ListConferenceStore
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jxmpp.jid.impl.JidCreate

class AvModerationHandlerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val roomJid = JidCreate.entityBareFrom("conf1@conference.example.com")
    private val componentAddress = "avmoderation.example.com"

    private val chatRoom: ChatRoom = mockk(relaxed = true) {
        every { queueXmppTask(any()) } answers { firstArg<() -> Unit>().invoke() }
        every { isAvModerationEnabled(any()) } returns false
    }
    private val conference: JitsiMeetConference = mockk(relaxed = true) {
        every { roomName } returns roomJid
        every { chatRoom } returns this@AvModerationHandlerTest.chatRoom
    }
    private val conferenceStore = ListConferenceStore().apply { add(conference) }

    private val xmppProvider: XmppProvider = mockk(relaxed = true) {
        every { components } returns setOf(XmppProvider.Component("av_moderation", componentAddress))
    }

    private val handler = AvModerationHandler(xmppProvider, conferenceStore)

    private fun process(json: String, from: String = componentAddress) = handler.processStanza(
        StanzaBuilder.buildMessage()
            .from(JidCreate.from(from))
            .addExtension(JsonMessageExtension(json))
            .build()
    )

    init {
        context("Enabling moderation") {
            process(
                """
                {"type":"av_moderation", "room":"$roomJid", "enabled":true, "mediaType":"audio",
                 "actor":"$roomJid/actor"}
                """.trimIndent()
            )
            verify(exactly = 1) { chatRoom.setAvModerationEnabled(MediaType.AUDIO, true) }
            verify(exactly = 1) {
                conference.muteAllParticipants(MediaType.AUDIO, JidCreate.entityFullFrom("$roomJid/actor"))
            }
        }
        context("Enabling moderation when it is already enabled") {
            every { chatRoom.isAvModerationEnabled(MediaType.AUDIO) } returns true
            process("""{"type":"av_moderation", "room":"$roomJid", "enabled":true, "mediaType":"audio"}""")
            verify(exactly = 1) { chatRoom.setAvModerationEnabled(MediaType.AUDIO, true) }
            verify(exactly = 0) { conference.muteAllParticipants(any(), any()) }
        }
        context("Disabling moderation") {
            process("""{"type":"av_moderation", "room":"$roomJid", "enabled":false, "mediaType":"video"}""")
            verify(exactly = 1) { chatRoom.setAvModerationEnabled(MediaType.VIDEO, false) }
            verify(exactly = 0) { conference.muteAllParticipants(any(), any()) }
        }
        context("Setting whitelists") {
            process("""{"type":"av_moderation", "room":"$roomJid", "whitelists":{"audio":["a","b"],"video":["c"]}}""")
            verify(exactly = 1) { chatRoom.setAvModerationWhitelist(MediaType.AUDIO, listOf("a", "b")) }
            verify(exactly = 1) { chatRoom.setAvModerationWhitelist(MediaType.VIDEO, listOf("c")) }
            verify(exactly = 0) { chatRoom.setAvModerationEnabled(any(), any()) }
        }
        context("Messages that should be ignored") {
            should("ignore messages from another address") {
                process(
                    """{"type":"av_moderation", "room":"$roomJid", "enabled":true, "mediaType":"audio"}""",
                    from = "attacker.example.com"
                )
                verify(exactly = 0) { chatRoom.setAvModerationEnabled(any(), any()) }
            }
            should("ignore invalid JSON") {
                process("not json")
                process("""{"type":"unknown_type"}""")
                verify(exactly = 0) { chatRoom.setAvModerationEnabled(any(), any()) }
            }
            should("ignore messages for an unknown room") {
                process(
                    """
                    {"type":"av_moderation", "room":"other@conference.example.com", "enabled":true,
                     "mediaType":"audio"}
                    """.trimIndent()
                )
                verify(exactly = 0) { chatRoom.setAvModerationEnabled(any(), any()) }
            }
        }
    }
}
