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
import io.kotest.core.spec.style.ShouldSpec
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.jibri.JibriChatRoomMember
import org.jitsi.jicofo.jibri.JibriDetector
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.xmpp.muc.ChatRoomImpl
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.jicofo.xmpp.muc.ChatRoomMemberImpl
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate
import java.util.logging.Level

/**
 * All debugState interfaces should produce valid JSON.
 */
class DebugStateTest : ShouldSpec() {
    init {
        context("BridgeSelector") {
            val bridgeSelector = BridgeSelector().apply { addJvbAddress(JidCreate.from("jvb")) }
            bridgeSelector.debugState.shouldBeValidJson()
        }
        context("FocusManager") {
            val conferenceJid = JidCreate.entityBareFrom("conference@example.com")

            val focusManager = FocusManager(jicofoServices = mockk(relaxed = true)).apply {
                conferenceRequest(conferenceJid, emptyMap())
            }
            focusManager.getDebugState(true).shouldBeValidJson()
        }
        context("ChatRoomImpl and members") {
            val conferenceJid = JidCreate.entityBareFrom("conference@example.com")

            val chatRoom = ChatRoomImpl(mockk(relaxed = true), conferenceJid, Level.INFO) { }
            chatRoom.debugState.shouldBeValidJson()

            val member = ChatRoomMemberImpl(
                JidCreate.entityFullFrom("conference@example.com/member"),
                chatRoom,
                mockk(relaxed = true)
            )
            member.debugState.shouldBeValidJson()
        }
        context("Jigasi detector") {
            val jigasiDetector = JigasiDetector(
                mockk(relaxed = true),
                JidCreate.entityBareFrom("JigasiBrewery@example.com")
            )
            jigasiDetector.processMemberPresence(
                jigasiChatMember(JidCreate.entityFullFrom("JigasiBrewery@example.com/jigasi-1"))
            )
            jigasiDetector.debugState.shouldBeValidJson()
            println(jigasiDetector.debugState.toJSONString())
        }
        context("Jibri detector") {
            val jibriDetector = JibriDetector(
                mockk(relaxed = true),
                JidCreate.entityBareFrom("JibriBrewery@example.com"),
                false
            )

            // This registers with the detector internally.
            JibriChatRoomMember(
                JidCreate.entityFullFrom("JibriBrewery@example.com/jibri-1"),
                jibriDetector
            )
            jibriDetector.debugState.shouldBeValidJson()
            println(jibriDetector.debugState.toJSONString())
        }
    }
}

fun OrderedJsonObject.shouldBeValidJson() {
    JSONParser().parse(this.toJSONString())
}

private fun jigasiChatMember(jid: EntityFullJid) = mockk<ChatRoomMember> {
    every { occupantJid } returns jid
    every { presence } returns mockk {
        every {
            getExtensionElement(ColibriStatsExtension.ELEMENT, ColibriStatsExtension.NAMESPACE)
        } answers {
            ColibriStatsExtension()
        }
    }
}
