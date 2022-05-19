/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jitsi.jicofo.JitsiMeetConfig
import org.jitsi.jicofo.conference.colibri.ColibriAllocation
import org.jitsi.jicofo.conference.colibri.ColibriSessionManager
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.jicofo.discovery.DiscoveryUtil
import org.jitsi.jicofo.discovery.DiscoveryUtil.FEATURE_SCTP
import org.jitsi.jicofo.discovery.DiscoveryUtil.FEATURE_VIDEO
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jxmpp.jid.impl.JidCreate

class ParticipantInviteRunnableTest : ShouldSpec({
    context("Creating and sending an offer") {
        val feedbackSources = ConferenceSourceMap(
            ParticipantInviteRunnable.SSRC_OWNER_JVB to EndpointSourceSet(
                setOf(
                    Source(1234L, MediaType.AUDIO, "audio-feedback"),
                    Source(5678L, MediaType.VIDEO, "video-feedback")
                )
            )
        )
        val conferenceSources = ConferenceSourceMap(
            JidCreate.entityFullFrom("conference@example.com/p1") to EndpointSourceSet(
                setOf(
                    Source(1L, MediaType.AUDIO, "audio-p1"),
                    Source(2L, MediaType.VIDEO, "video-p1"),
                )
            ),
            JidCreate.entityFullFrom("conference@example.com/p2") to EndpointSourceSet(
                setOf(
                    Source(11L, MediaType.AUDIO, "audio-p2"),
                    Source(12L, MediaType.VIDEO, "video-p2"),
                    Source(13L, MediaType.VIDEO, "video-p2"),
                    Source(14L, MediaType.VIDEO, "video-p2"),
                    Source(15L, MediaType.VIDEO, "video-p2"),
                    Source(16L, MediaType.VIDEO, "video-p2"),
                    Source(17L, MediaType.VIDEO, "video-p2"),
                ),
                setOf(
                    SsrcGroup(SsrcGroupSemantics.Sim, listOf(12L, 14L, 16L), MediaType.VIDEO),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(12L, 13L), MediaType.VIDEO),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(14L, 15L), MediaType.VIDEO),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(16L, 17L), MediaType.VIDEO)
                )
            )
        )
        val colibriSessionManager = mockk<ColibriSessionManager> {
            every { allocate(any(), any(), any(), any()) } returns ColibriAllocation(
                feedbackSources,
                IceUdpTransportPacketExtension(),
                null,
                null,
                null
            )
        }

        val features = DiscoveryUtil.getDefaultParticipantFeatureSet().toMutableList().apply {
            remove(FEATURE_SCTP)
        }

        // Capture the jingle contents that the invite runnable will send via Jingle.
        val jingleContentsSlot = slot<List<ContentPacketExtension>>()
        val sourcesContentsSlot = slot<ConferenceSourceMap>()
        val conference = mockk<JitsiMeetConferenceImpl> {
            every { config } returns JitsiMeetConfig(HashMap())
            every { sources } returns conferenceSources
            every { chatRoom } returns mockk {
                every { hasMember(any()) } returns true
            }
            every { jingle } returns mockk {
                every {
                    initiateSession(
                        any(),
                        capture(jingleContentsSlot),
                        any(),
                        any(),
                        capture(sourcesContentsSlot),
                        any()
                    )
                } returns true
            }
        }
        val participant = Participant(
            mockk {
                every { occupantJid } returns JidCreate.entityFullFrom("conference@example.com/participant")
                every { name } returns "participant"
                every { role } returns MemberRole.OWNER
            },
            features,
            LoggerImpl("test"),
            conference
        )
        val participantInviteRunnable = ParticipantInviteRunnable(
            conference,
            mockk(),
            colibriSessionManager,
            participant,
            false,
            false,
            false,
            LoggerImpl("test")
        )

        context("When the participant supports audio and video") {
            participant.supportedMediaTypes shouldBe setOf(MediaType.AUDIO, MediaType.VIDEO)

            participantInviteRunnable.run()

            jingleContentsSlot.isCaptured shouldBe true
            jingleContentsSlot.captured.apply {
                size shouldBe 2
                any { it.name == "video" } shouldBe true
                any { it.name == "audio" } shouldBe true
            }

            sourcesContentsSlot.isCaptured shouldBe true
            sourcesContentsSlot.captured.values.apply {
                should("Have some audio sources") {
                    any { it.hasAudio } shouldBe true
                }
                should("Have some video sources") {
                    any { it.hasVideo } shouldBe true
                }
                should("Have some source groups") {
                    // TODO: check if simulcast is properly stripped?
                    any { it.ssrcGroups.isNotEmpty() } shouldBe true
                }
            }
        }
        context("When the participant supports only audio") {
            features.remove(FEATURE_VIDEO)
            participant.supportedMediaTypes shouldBe setOf(MediaType.AUDIO)

            participantInviteRunnable.run()


            jingleContentsSlot.isCaptured shouldBe true
            jingleContentsSlot.captured.apply {
                size shouldBe 1
                any { it.name == "audio" } shouldBe true
            }

            sourcesContentsSlot.isCaptured shouldBe true
            sourcesContentsSlot.captured.values.apply {
                should("have some audio sources") {
                    any { it.hasAudio } shouldBe true
                }
                should("have no video sources") {
                    any { it.hasVideo } shouldBe false
                }
                should("have no source groups") {
                    any { it.ssrcGroups.isNotEmpty() } shouldBe false
                }
            }
        }
    }
})
