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
package org.jitsi.jicofo.conference

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.ClientRequirementsConfig
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.clientrequirements.ClientRequirementsIq
import org.jitsi.xmpp.extensions.clientrequirements.RequirementLevel
import org.jitsi.xmpp.extensions.clientrequirements.RequirementsAction
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.impl.JidCreate

/** The features of a client which supports everything that the tests below require. */
private val allFeatures = Features.defaultFeatures + Features.SSRC_REWRITING_V1 + Features.CLIENT_REQUIREMENTS_1

private fun member(
    features: Set<Features>,
    discovered: Boolean = true,
    jibri: Boolean = false,
    jigasi: Boolean = false,
    transcriber: Boolean = false,
    id: String = "abcdabcd"
) = mockk<ChatRoomMember>(relaxed = true) {
    every { name } returns id
    every { occupantJid } returns JidCreate.entityFullFrom("room@conference.example.com/$id")
    every { this@mockk.features } returns features
    every { featuresDiscovered } returns discovered
    every { isJibri } returns jibri
    every { isJigasi } returns jigasi
    every { isTranscriber } returns transcriber
    every { clientVersion } returns "abc1234"
}

class ClientRequirementsHandlerTest : ShouldSpec() {
    private val sent = mutableListOf<Stanza>()

    private fun handler() = ClientRequirementsHandler(
        LoggerImpl(javaClass.name),
        ClientRequirementsConfig(),
        { _, stanza -> sent.add(stanza) }
    )

    init {
        beforeTest { sent.clear() }

        context("With no requirements configured") {
            withNewConfig(configWithRequirements("")) {
                should("Invite and not notify") {
                    handler().checkAndNotify(member(Features.defaultFeatures)) shouldBe true
                    sent.size shouldBe 0
                }
            }
        }

        context("With the check disabled") {
            withNewConfig(
                """
                jicofo.conference.client-requirements {
                  enabled = false
                  enforce = true
                  requirements { SSRC_REWRITING_V1 { level = "hard" } }
                }
                """.trimIndent()
            ) {
                should("Invite and not notify") {
                    handler().checkAndNotify(member(Features.defaultFeatures)) shouldBe true
                    sent.size shouldBe 0
                }
            }
        }

        context("With a hard requirement") {
            withNewConfig(
                configWithRequirements(
                    """SSRC_REWRITING_V1 { level = "hard", details = "Update.", url = "https://example.com" }"""
                )
            ) {
                should("Invite a client which advertises the feature") {
                    handler().checkAndNotify(member(allFeatures)) shouldBe true
                    sent.size shouldBe 0
                }
                should("Not invite a client which does not advertise the feature") {
                    val m = member(Features.defaultFeatures + Features.CLIENT_REQUIREMENTS_1)
                    handler().checkAndNotify(m) shouldBe false

                    sent.size shouldBe 1
                    val iq = sent[0].shouldBeInstanceOf<ClientRequirementsIq>()
                    iq.to shouldBe m.occupantJid
                    iq.action shouldBe RequirementsAction.REJECT
                    iq.missingFeatures.size shouldBe 1
                    iq.missingFeatures[0].let {
                        it.feature shouldBe Features.SSRC_REWRITING_V1.value
                        it.name shouldBe "SSRC_REWRITING_V1"
                        it.level shouldBe RequirementLevel.HARD
                        it.details shouldBe "Update."
                        it.url shouldBe "https://example.com"
                    }
                }
                should("Send a message to a client which does not support the IQ") {
                    val m = member(Features.defaultFeatures)
                    handler().checkAndNotify(m) shouldBe false

                    sent.size shouldBe 1
                    val message = sent[0].shouldBeInstanceOf<Message>()
                    message.to shouldBe m.occupantJid
                    message.type shouldBe Message.Type.chat
                    message.body!!.contains("SSRC_REWRITING_V1") shouldBe true
                    message.body!!.contains("Update.") shouldBe true
                    message.body!!.contains("https://example.com") shouldBe true
                }
                should("Not notify again when a member is checked twice") {
                    val h = handler()
                    val m = member(Features.defaultFeatures + Features.CLIENT_REQUIREMENTS_1)
                    h.checkAndNotify(m) shouldBe false
                    h.checkAndNotify(m) shouldBe false
                    sent.size shouldBe 1
                    h.rejectedCount shouldBe 1

                    // Once the member leaves its state is cleared.
                    h.memberLeft(m) shouldBe true
                    h.rejectedCount shouldBe 0
                    h.checkAndNotify(m) shouldBe false
                    sent.size shouldBe 2
                }
                should("Not check a member whose features were not discovered") {
                    handler().checkAndNotify(member(Features.defaultFeatures, discovered = false)) shouldBe true
                    sent.size shouldBe 0
                }
                should("Not check jibri, jigasi or the transcriber") {
                    val h = handler()
                    h.checkAndNotify(member(Features.defaultFeatures, jibri = true)) shouldBe true
                    h.checkAndNotify(member(Features.defaultFeatures, jigasi = true)) shouldBe true
                    h.checkAndNotify(member(Features.defaultFeatures, transcriber = true)) shouldBe true
                    sent.size shouldBe 0
                }
            }
        }

        context("With a hard requirement and enforce disabled") {
            withNewConfig(
                """
                jicofo.conference.client-requirements {
                  enabled = true
                  enforce = false
                  requirements { SSRC_REWRITING_V1 { level = "hard" } }
                }
                """.trimIndent()
            ) {
                should("Invite, and notify with a warning") {
                    val h = handler()
                    h.checkAndNotify(member(Features.defaultFeatures + Features.CLIENT_REQUIREMENTS_1)) shouldBe true
                    h.rejectedCount shouldBe 0

                    sent.size shouldBe 1
                    val iq = sent[0].shouldBeInstanceOf<ClientRequirementsIq>()
                    iq.action shouldBe RequirementsAction.WARN
                    iq.missingFeatures[0].level shouldBe RequirementLevel.SOFT
                }
            }
        }

        context("With a soft requirement") {
            withNewConfig(configWithRequirements("""SSRC_REWRITING_V1 { level = "soft" }""")) {
                should("Invite, and notify with a warning") {
                    handler().checkAndNotify(
                        member(Features.defaultFeatures + Features.CLIENT_REQUIREMENTS_1)
                    ) shouldBe true

                    sent.size shouldBe 1
                    val iq = sent[0].shouldBeInstanceOf<ClientRequirementsIq>()
                    iq.action shouldBe RequirementsAction.WARN
                    iq.missingFeatures[0].level shouldBe RequirementLevel.SOFT
                }
            }
        }

        context("With a requirement disabled in configuration") {
            withNewConfig(configWithRequirements("""SSRC_REWRITING_V1 { level = "off" }""")) {
                should("Invite and not notify") {
                    handler().checkAndNotify(member(Features.defaultFeatures)) shouldBe true
                    sent.size shouldBe 0
                }
            }
        }

        context("With multiple missing requirements") {
            withNewConfig(
                configWithRequirements(
                    """
                    SSRC_REWRITING_V1 { level = "hard" }
                    START_MUTED_RMD { level = "soft" }
                    """.trimIndent()
                )
            ) {
                should("Include all of them in a single IQ") {
                    handler().checkAndNotify(
                        member(Features.defaultFeatures + Features.CLIENT_REQUIREMENTS_1)
                    ) shouldBe false

                    sent.size shouldBe 1
                    val iq = sent[0].shouldBeInstanceOf<ClientRequirementsIq>()
                    iq.action shouldBe RequirementsAction.REJECT
                    iq.missingFeatures.map { it.name }.toSet() shouldBe setOf("SSRC_REWRITING_V1", "START_MUTED_RMD")
                }
                should("Send one message per requirement to a client which does not support the IQ") {
                    handler().checkAndNotify(member(Features.defaultFeatures)) shouldBe false
                    sent.size shouldBe 2
                }
            }
        }

        context("With a requirement for an unknown feature") {
            withNewConfig(configWithRequirements("""NOT_A_FEATURE { level = "hard" }""")) {
                should("Ignore it") {
                    handler().checkAndNotify(member(Features.defaultFeatures)) shouldBe true
                    sent.size shouldBe 0
                }
            }
        }
    }
}

private fun configWithRequirements(requirements: String) = """
    jicofo.conference.client-requirements {
      enabled = true
      enforce = true
      requirements {
        $requirements
      }
    }
""".trimIndent()
