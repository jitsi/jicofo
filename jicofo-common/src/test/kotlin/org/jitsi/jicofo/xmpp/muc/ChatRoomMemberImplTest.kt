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
package org.jitsi.jicofo.xmpp.muc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jitsimeet.AudioMutedExtension
import org.jitsi.xmpp.extensions.jitsimeet.FeatureExtension
import org.jitsi.xmpp.extensions.jitsimeet.FeaturesExtension
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantCodecList
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantRegionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.StatsId
import org.jitsi.xmpp.extensions.jitsimeet.VideoMutedExtension
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole
import org.jivesoftware.smackx.muc.Occupant
import org.jxmpp.jid.impl.JidCreate

class ChatRoomMemberImplTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val occupantJid = JidCreate.entityFullFrom("room@conference.example.com/nick")

    private var occupant: Occupant? = mockk<Occupant> {
        every { role } returns MUCRole.participant
        every { affiliation } returns MUCAffiliation.none
    }

    private val chatRoom: ChatRoomImpl = mockk(relaxed = true) {
        every { getOccupant(any()) } answers { occupant }
        every { getJid(any()) } returns JidCreate.from("user@example.com")
    }

    private val member = ChatRoomMemberImpl(occupantJid, chatRoom, LoggerImpl("test"))

    private fun presence(vararg extensions: ExtensionElement): Presence =
        StanzaBuilder.buildPresence().from(occupantJid).build().apply {
            extensions.forEach { addExtension(it) }
        }

    private fun sourceInfo(json: String) =
        StandardExtensionElement.builder("SourceInfo", "jabber:client").setText(json).build()

    init {
        context("Presence from another occupant") {
            shouldThrow<IllegalArgumentException> {
                member.processPresence(
                    StanzaBuilder.buildPresence().from(JidCreate.from("room@conference.example.com/other")).build()
                )
            }
        }
        context("Defaults") {
            member.processPresence(presence())
            member.role shouldBe MemberRole.PARTICIPANT
            member.isAudioMuted shouldBe true
            member.isVideoMuted shouldBe true
            member.isJigasi shouldBe false
            member.isJibri shouldBe false
            member.sourceInfos shouldBe emptySet()
            member.region shouldBe null
            member.statsId shouldBe null
        }
        context("Mute state") {
            context("Via SourceInfo") {
                member.processPresence(
                    presence(sourceInfo("""{"nick-a0": {"muted": false}, "nick-v0": {"muted": false}}"""))
                )
                member.isAudioMuted shouldBe false
                member.isVideoMuted shouldBe false
                member.sourceInfos.size shouldBe 2

                member.processPresence(presence(sourceInfo("""{"nick-a0": {"muted": true}}""")))
                member.isAudioMuted shouldBe true
                member.isVideoMuted shouldBe true
            }
            context("Via the legacy extensions") {
                member.processPresence(
                    presence(
                        AudioMutedExtension().apply { setAudioMuted(false) },
                        VideoMutedExtension().apply { setVideoMuted(false) }
                    )
                )
                member.isAudioMuted shouldBe false
                member.isVideoMuted shouldBe false
            }
            context("With invalid SourceInfo JSON") {
                member.processPresence(presence(sourceInfo("invalid json")))
                member.isAudioMuted shouldBe true
                member.sourceInfos shouldBe emptySet()
            }
        }
        context("Region and stats ID") {
            member.processPresence(
                presence(
                    JitsiParticipantRegionPacketExtension().apply { regionId = "us-east" },
                    StatsId("stats-id-123")
                )
            )
            member.region shouldBe "us-east"
            member.statsId shouldBe "stats-id-123"
        }
        context("Video codecs") {
            should("use the signaled codec list") {
                member.processPresence(
                    presence(JitsiParticipantCodecList().apply { codecs = listOf("av1", "vp9", "vp8") })
                )
                member.videoCodecs shouldBe listOf("av1", "vp9", "vp8")
            }
            should("add vp8 when missing from the codec list") {
                member.processPresence(presence(JitsiParticipantCodecList().apply { codecs = listOf("h264") }))
                member.videoCodecs shouldBe listOf("h264", "vp8")
            }
            should("support the legacy codecType extension") {
                member.processPresence(
                    presence(
                        StandardExtensionElement.builder("jitsi_participant_codecType", "jabber:client")
                            .setText("h264").build()
                    )
                )
                member.videoCodecs shouldBe listOf("h264", "vp8")
            }
        }
        context("Jigasi and jibri detection") {
            val jigasiFeature = FeaturesExtension().apply {
                addChildExtension(
                    FeatureExtension().apply { setAttribute("var", "http://jitsi.org/protocol/jigasi") }
                )
            }
            context("From a trusted domain") {
                withNewConfig("jicofo.xmpp.trusted-domains = [ \"example.com\" ]") {
                    member.processPresence(presence(jigasiFeature))
                    member.isJigasi shouldBe true
                    member.isJibri shouldBe false

                    // A presence without the extension resets the flag.
                    member.processPresence(presence())
                    member.isJigasi shouldBe false
                }
            }
            context("From an untrusted domain") {
                member.processPresence(presence(jigasiFeature))
                member.isJigasi shouldBe false
            }
        }
        context("Role changes") {
            member.role shouldBe MemberRole.PARTICIPANT
            member.processPresence(presence())

            context("To another non-visitor role") {
                occupant = mockk<Occupant> {
                    every { role } returns MUCRole.moderator
                    every { affiliation } returns MUCAffiliation.owner
                }
                member.processPresence(presence())
                member.role shouldBe MemberRole.OWNER
            }
            context("To visitor (not supported)") {
                occupant = null
                member.processPresence(presence())
                member.role shouldBe MemberRole.PARTICIPANT
            }
        }
    }
}
