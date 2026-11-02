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
 * Tests that the "translation_language" participant property is parsed from presence into
 * [ChatRoomMember.translationLanguage], and that a participant cannot put an arbitrary string there.
 */
class ChatRoomMemberTranslationLanguageTest : ShouldSpec() {
    private val occupantJid: EntityFullJid = JidCreate.entityFullFrom("conference@example.com/member")
    private val conferenceJid = JidCreate.entityBareFrom("conference@example.com")
    private val chatRoom = ChatRoomImpl(mockk(relaxed = true), conferenceJid, Level.INFO) { }

    private fun member() = ChatRoomMemberImpl(occupantJid, chatRoom, mockk(relaxed = true))

    private fun presence(language: String?) = StanzaBuilder.buildPresence().from(occupantJid).apply {
        language?.let {
            addExtension(
                StandardExtensionElement
                    .builder("jitsi_participant_translation_language", "jabber:client")
                    .setText(it)
                    .build()
            )
        }
    }.build()

    private fun languageFor(language: String?) =
        member().apply { processPresence(presence(language)) }.translationLanguage

    init {
        context("Parsing the translation_language participant property") {
            should("be null when the element is absent") {
                languageFor(null) shouldBe null
            }
            should("read a two-letter language") {
                languageFor("fr") shouldBe "fr"
            }
            should("read a three-letter language") {
                languageFor("ceb") shouldBe "ceb"
            }
            should("keep a region subtag verbatim") {
                languageFor("zh-CN") shouldBe "zh-CN"
            }
            should("be null for a blank language") {
                languageFor("   ") shouldBe null
            }
            should("trim surrounding whitespace") {
                languageFor(" de ") shouldBe "de"
            }
        }

        context("Rejecting a value that is not a language code") {
            // The value is set by the participant. It reaches a colibri2 attribute, the bridge's logs and (later) a
            // translation provider, so anything that does not look like a language code is dropped here.
            should("reject a single letter") {
                languageFor("f") shouldBe null
            }
            should("reject an over-long primary subtag") {
                languageFor("abcd") shouldBe null
            }
            should("reject a very long value") {
                languageFor("a".repeat(4096)) shouldBe null
            }
            should("reject a value with a newline, which would otherwise forge a log line") {
                languageFor("fr\nINFO: forged log line") shouldBe null
            }
            should("reject a value with markup") {
                languageFor("fr\"/><evil/>") shouldBe null
            }
            should("reject a value with a space") {
                languageFor("fr de") shouldBe null
            }
            should("reject a non-language string") {
                languageFor("../../etc/passwd") shouldBe null
            }
        }

        context("Updating on a later presence") {
            should("follow the language from one presence to the next") {
                val member = member()
                member.processPresence(presence("fr"))
                member.translationLanguage shouldBe "fr"
                member.processPresence(presence("de"))
                member.translationLanguage shouldBe "de"
            }
            should("clear the language when the client clears the property") {
                // The client sets the property to the empty string when the user goes back to the original language.
                val member = member()
                member.processPresence(presence("fr"))
                member.processPresence(presence(""))
                member.translationLanguage shouldBe null
            }
            should("clear the language when the element goes away") {
                val member = member()
                member.processPresence(presence("fr"))
                member.processPresence(presence(null))
                member.translationLanguage shouldBe null
            }
        }
    }
}
