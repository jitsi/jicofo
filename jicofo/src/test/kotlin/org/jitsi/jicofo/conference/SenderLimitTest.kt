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
package org.jitsi.jicofo.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.config.withNewConfig
import org.jitsi.impl.protocol.xmpp.ChatRoom
import org.jitsi.jicofo.ConferenceConfig
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidationFailedException
import org.jitsi.utils.MediaType
import org.jxmpp.jid.impl.JidCreate
import java.util.logging.Level

/**
 * Test audio and video sender limits.
 */
class SenderLimitTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance

    init {
        var ssrcs = 1L
        fun nextSource(mediaType: MediaType) = EndpointSourceSet(Source(ssrcs++, mediaType))
        var videoSenders = 0
        var audioSenders = 0
        val chatRoom: ChatRoom = mockk(relaxed = true) {
            every { videoSendersCount } answers { videoSenders }
            every { audioSendersCount } answers { audioSenders }
        }
        val conference = JitsiMeetConferenceImpl(
            JidCreate.entityBareFrom("test@example.com"),
            mockk(),
            mockk(relaxed = true),
            Level.INFO,
            null,
            false,
            mockk(relaxed = true) {
                every { xmppServices } returns mockk(relaxed = true) {
                    every { clientConnection } returns mockk {
                        every { isRegistered } returns true
                        every { findOrCreateRoom(any()) } returns chatRoom
                    }
                }
                every { authenticationAuthority } returns null
            }
        ).apply { start() }

        context("Sender limits") {
            withNewConfig("jicofo.conference.max-video-senders=5, jicofo.conference.max-audio-senders=5") {
                context("Configuration") {
                    ConferenceConfig.config.maxVideoSenders shouldBe 5
                    ConferenceConfig.config.maxAudioSenders shouldBe 5
                }

                context("Video") {
                    val videoSource = nextSource(MediaType.VIDEO)
                    conference.addSource(mockk(relaxed = true), videoSource)
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), videoSource)
                    }
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), EndpointSourceSet(Source(-1, MediaType.VIDEO)))
                    }

                    videoSenders = 10
                    shouldThrow<JitsiMeetConferenceImpl.SenderCountExceededException> {
                        conference.addSource(mockk(relaxed = true), nextSource(MediaType.VIDEO))
                    }

                    videoSenders = 2
                    conference.addSource(mockk(relaxed = true), nextSource(MediaType.VIDEO))
                }
                context("Audio") {
                    val audioSource = nextSource(MediaType.AUDIO)
                    conference.addSource(mockk(relaxed = true), audioSource)
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), audioSource)
                    }
                    shouldThrow<ValidationFailedException> {
                        conference.addSource(mockk(relaxed = true), EndpointSourceSet(Source(-1, MediaType.AUDIO)))
                    }

                    audioSenders = 10
                    shouldThrow<JitsiMeetConferenceImpl.SenderCountExceededException> {
                        conference.addSource(mockk(relaxed = true), nextSource(MediaType.AUDIO))
                    }

                    audioSenders = 2
                    conference.addSource(mockk(relaxed = true), nextSource(MediaType.AUDIO))
                }
            }
        }
    }
}
