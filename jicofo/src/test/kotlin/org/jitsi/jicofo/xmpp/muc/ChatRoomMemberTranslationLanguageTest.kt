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
 * [ChatRoomMember.translationLanguage], and that it is only honoured while the member also requests transcription.
 */
class ChatRoomMemberTranslationLanguageTest : ShouldSpec() {
    private val occupantJid: EntityFullJid = JidCreate.entityFullFrom("conference@example.com/member")
    private val conferenceJid = JidCreate.entityBareFrom("conference@example.com")
    private val chatRoom = ChatRoomImpl(mockk(relaxed = true), conferenceJid, Level.INFO) { }

    private fun member() = ChatRoomMemberImpl(occupantJid, chatRoom, mockk(relaxed = true))

    private fun presence(requestingTranscription: String?, language: String?) =
        StanzaBuilder.buildPresence().from(occupantJid).apply {
            requestingTranscription?.let {
                addExtension(
                    StandardExtensionElement
                        .builder("jitsi_participant_requestingTranscription", "jabber:client")
                        .setText(it)
                        .build()
                )
            }
            language?.let {
                addExtension(
                    StandardExtensionElement
                        .builder("jitsi_participant_translation_language", "jabber:client")
                        .setText(it)
                        .build()
                )
            }
        }.build()

    private fun languageFor(requestingTranscription: String?, language: String?) =
        member().apply { processPresence(presence(requestingTranscription, language)) }.translationLanguage

    init {
        context("Parsing the translation_language participant property") {
            should("be null when neither element is present") {
                languageFor(null, null) shouldBe null
            }
            should("read the language when transcription is requested") {
                languageFor("true", "fr") shouldBe "fr"
            }
            should("keep a region subtag verbatim") {
                languageFor("true", "zh-CN") shouldBe "zh-CN"
            }
            should("be null when transcription is requested but no language is set") {
                languageFor("true", null) shouldBe null
            }
            should("be null for a blank language") {
                languageFor("true", "   ") shouldBe null
            }
            should("trim surrounding whitespace") {
                languageFor("true", " de ") shouldBe "de"
            }
        }

        context("Ignoring a stale language") {
            // Clients set translation_language but do not always clear it when the user switches back to the
            // original language or turns subtitles off. Requiring requestingTranscription stops a stale value from
            // keeping a language requested for the rest of the conference.
            should("be null when transcription is not requested") {
                languageFor("false", "fr") shouldBe null
            }
            should("be null when the requestingTranscription element is absent") {
                languageFor(null, "fr") shouldBe null
            }
            should("be null for a non-boolean requestingTranscription value") {
                languageFor("garbage", "fr") shouldBe null
            }
        }

        context("Updating on a later presence") {
            should("follow the language from one presence to the next") {
                val member = member()
                member.processPresence(presence("true", "fr"))
                member.translationLanguage shouldBe "fr"
                member.processPresence(presence("true", "de"))
                member.translationLanguage shouldBe "de"
            }
            should("clear the language when the member stops requesting transcription") {
                val member = member()
                member.processPresence(presence("true", "fr"))
                member.processPresence(presence("false", "fr"))
                member.translationLanguage shouldBe null
            }
        }
    }
}
