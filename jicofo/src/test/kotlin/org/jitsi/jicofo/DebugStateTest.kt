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
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.impl.protocol.xmpp.ChatMemberImpl
import org.jitsi.impl.protocol.xmpp.ChatRoomImpl
import org.jitsi.impl.protocol.xmpp.ChatRoomMember
import org.jitsi.jicofo.JicofoHarnessTest
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.jibri.JibriChatRoomMember
import org.jitsi.jicofo.jibri.JibriDetector
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate

/**
 * All debugState interfaces should produce valid JSON.
 */
class DebugStateTest : JicofoHarnessTest() {
    init {
        context("JicofoServices and JitsiMeetConference") {
            val conferenceJid = JidCreate.entityBareFrom("conference@example.com")
            val logger = LoggerImpl("test")

            harness.jicofoServices.bridgeSelector.addJvbAddress(JidCreate.from("jvb"))
            harness.jicofoServices.focusManager.conferenceRequest(conferenceJid, emptyMap())

            harness.jicofoServices.getDebugState(true).shouldBeValidJson()

            val conference = harness.jicofoServices.focusManager.getConference(conferenceJid)
            conference shouldNotBe null
            conference!!.debugState.shouldBeValidJson()

            val chatRoom = ChatRoomImpl(harness.xmppProvider, conferenceJid) { }
            chatRoom.debugState.shouldBeValidJson()

            val member = ChatMemberImpl(JidCreate.entityFullFrom("conference@example.com/member"), chatRoom, logger, 0)
            member.debugState.shouldBeValidJson()

            val participant = Participant(member, conference)
            participant.debugState.shouldBeValidJson()
        }
        context("Jigasi detector") {
            val jigasiDetector = JigasiDetector(
                harness.jicofoServices.xmppServices.clientConnection,
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
                harness.jicofoServices.xmppServices.clientConnection,
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

fun OrderedJsonObject.shouldBeValidJson() = JSONParser().parse(this.toJSONString())

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
