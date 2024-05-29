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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jitsi.jicofo.bridge.colibri.ColibriAllocation
import org.jitsi.jicofo.bridge.colibri.ColibriSessionManager
import org.jitsi.jicofo.bridge.colibri.SSRC_OWNER_JVB
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.jingle.JingleSession
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jxmpp.jid.impl.JidCreate

class ParticipantInviteRunnableTest : ShouldSpec({
    context("Creating and sending an offer") {
        val feedbackSources = ConferenceSourceMap(
            SSRC_OWNER_JVB to EndpointSourceSet(
                setOf(
                    Source(1234L, MediaType.AUDIO, "audio-feedback"),
                    Source(5678L, MediaType.VIDEO, "video-feedback")
                )
            )
        )
        val jid1 = "p1"
        val jid2 = "p2"
        val conferenceSources = ConferenceSourceMap(
            jid1 to EndpointSourceSet(
                setOf(
                    Source(1L, MediaType.AUDIO, "audio-p1"),
                    Source(2L, MediaType.VIDEO, "video-p1"),
                )
            ),
            jid2 to EndpointSourceSet(
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
            every { allocate(any()) } returns ColibriAllocation(
                feedbackSources,
                IceUdpTransportPacketExtension(),
                null,
                null,
                null
            )
        }

        // Capture the jingle contents that the invite runnable will send via Jingle.
        val jingleContentsSlot = slot<List<ContentPacketExtension>>()
        val sourcesContentsSlot = slot<ConferenceSourceMap>()
        val conference = mockk<JitsiMeetConferenceImpl> {
            every { sources } returns conferenceSources
            every { chatRoom } returns mockk {
                every { hasMember(any()) } returns true
            }
            every { getSourcesForParticipant(any()) } returns EndpointSourceSet.EMPTY
        }
        listOf(true, false).forEach { supportsVideo ->
            val features = Features.defaultFeatures.toMutableSet().apply {
                remove(Features.SCTP)
                if (!supportsVideo) remove(Features.VIDEO)
            }

            val participant = object : Participant(
                mockk {
                    every { occupantJid } returns JidCreate.entityFullFrom("conference@example.com/participant")
                    every { name } returns "participant"
                    every { role } returns MemberRole.OWNER
                    every { sourceInfos } returns emptySet()
                    every { statsId } returns "statsId"
                    every { region } returns "region"
                    every { isJibri } returns false
                    every { isJigasi } returns false
                    every { isTranscriber } returns false
                },
                conference,
                mockk(),
                supportedFeatures = features,
            ) {
                override fun createNewJingleSession(): JingleSession = mockk {
                    every {
                        initiateSession(
                            capture(jingleContentsSlot),
                            any(),
                            capture(sourcesContentsSlot)
                        )
                    } returns true
                }
            }

            val participantInviteRunnable = ParticipantInviteRunnable(
                conference,
                colibriSessionManager,
                participant,
                false,
                false,
                false,
                LoggerImpl("test")
            )

            context("When the participant ${if (supportsVideo) "supports" else "does not support"} video") {
                participantInviteRunnable.run()

                jingleContentsSlot.isCaptured shouldBe true
                jingleContentsSlot.captured.apply {
                    size shouldBe if (supportsVideo) 2 else 1
                    any { it.name == "video" } shouldBe supportsVideo
                    any { it.name == "audio" } shouldBe true
                }

                sourcesContentsSlot.isCaptured shouldBe true
                val sources = sourcesContentsSlot.captured
                should("Have some audio sources") {
                    sources.values.all { it.hasAudio } shouldBe true
                }
                should("Have video sources iff video is supported") {
                    sources.values.all { it.hasVideo } shouldBe supportsVideo
                }

                should("Have the correct SSRC groups") {
                    // p1 has no groups
                    sources[jid1]!!.ssrcGroups.shouldBeEmpty()

                    // p2 has simulcast. It should be stripped, but RTX should be retained.
                    val p2ssrcGroups = sources[jid2]!!.ssrcGroups
                    if (!supportsVideo) {
                        p2ssrcGroups.shouldBeEmpty()
                    } else {
                        p2ssrcGroups.any { it.semantics == SsrcGroupSemantics.Sim } shouldBe false
                        p2ssrcGroups.any { it.semantics == SsrcGroupSemantics.Fid } shouldBe true
                    }
                }
            }
        }
    }
})
