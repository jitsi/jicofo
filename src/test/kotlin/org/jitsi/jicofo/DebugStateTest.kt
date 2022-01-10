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
import org.jitsi.impl.protocol.xmpp.ChatMemberImpl
import org.jitsi.impl.protocol.xmpp.ChatRoomImpl
import org.jitsi.jicofo.JicofoHarnessTest
import org.jitsi.jicofo.conference.Participant
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.LoggerImpl
import org.json.simple.parser.JSONParser
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

            val participant = Participant(member, logger, conference)
            participant.debugState.shouldBeValidJson()
            print(participant.debugState.toJSONString())
        }
    }
}

fun OrderedJsonObject.shouldBeValidJson() = JSONParser().parse(this.toJSONString())
