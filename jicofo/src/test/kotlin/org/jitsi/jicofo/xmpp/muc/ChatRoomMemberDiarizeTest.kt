/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate
import java.util.logging.Level

/**
 * Tests that the "diarize" participant property is parsed from presence into [ChatRoomMember.diarize].
 */
class ChatRoomMemberDiarizeTest : ShouldSpec() {
    private val occupantJid: EntityFullJid = JidCreate.entityFullFrom("conference@example.com/member")
    private val conferenceJid = JidCreate.entityBareFrom("conference@example.com")
    private val chatRoom = ChatRoomImpl(mockk(relaxed = true), conferenceJid, Level.INFO) { }

    private fun member() = ChatRoomMemberImpl(occupantJid, chatRoom, mockk(relaxed = true))

    private fun presenceWithDiarize(value: String?) = StanzaBuilder.buildPresence().from(occupantJid).apply {
        value?.let {
            addExtension(
                StandardExtensionElement.builder("jitsi_participant_diarize", "jabber:client").setText(it).build()
            )
        }
    }.build()

    init {
        context("Parsing the diarize participant property") {
            should("default to false when the element is absent") {
                member().apply { processPresence(presenceWithDiarize(null)) }.diarize shouldBe false
            }
            should("parse 'true' as true") {
                member().apply { processPresence(presenceWithDiarize("true")) }.diarize shouldBe true
            }
            should("parse 'false' as false") {
                member().apply { processPresence(presenceWithDiarize("false")) }.diarize shouldBe false
            }
            should("parse a non-boolean value as false") {
                member().apply { processPresence(presenceWithDiarize("garbage")) }.diarize shouldBe false
            }
        }
    }
}
