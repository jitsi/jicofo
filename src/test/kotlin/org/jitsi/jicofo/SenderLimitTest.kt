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
package org.jitsi.jicofo

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import mock.MockParticipant
import mock.util.TestConference
import org.jitsi.config.withNewConfig
import org.jxmpp.jid.impl.JidCreate

/**
 * Test audio and video sender limits.
 */
class SenderLimitTest : JicofoHarnessTest() {
    override fun isolationMode(): IsolationMode? = IsolationMode.SingleInstance

    init {

        val names = arrayOf(
            "Tipsy_0", "Queasy_1", "Surly_2", "Sleazy_3", "Edgy_4", "Dizzy_5", "Remorseful_6"
        )

        context("sender limit test") {
            withNewConfig(
                "jicofo.conference.max-video-senders=5, jicofo.conference.max-audio-senders=5," +
                    " jicofo.colibri.enable-colibri2=true"
            ) {

                ConferenceConfig.config.maxVideoSenders shouldBe 5
                ConferenceConfig.config.maxAudioSenders shouldBe 5

                val roomName = JidCreate.entityBareFrom("test@example.com")
                val testConference = TestConference(harness, roomName)

                testConference.conference.start()

                val ps = Array(names.size) { i -> MockParticipant(names[i]).also { it.join(testConference.chatRoom) } }

                context("set it up") {
                    ps.forEach { it.acceptInvite(4000) }
                    ps.forEach { it.waitForAddSource(4000) }
                }

                context("video sender limit test") {
                    addVideoSource(ps[0]).shouldBeTrue()
                    addVideoSource(ps[1]).shouldBeTrue()
                    addVideoSource(ps[2]).shouldBeTrue()
                    addVideoSource(ps[3]).shouldBeTrue()
                    addVideoSource(ps[4]).shouldBeTrue()
                    addVideoSource(ps[5]).shouldBeFalse()
                    addVideoSource(ps[6]).shouldBeFalse()

                    ps[0].videoMute(true)
                    addVideoSource(ps[5]).shouldBeTrue()
                    addVideoSource(ps[6]).shouldBeFalse()

                    ps[1].videoMute(true)
                    addVideoSource(ps[6]).shouldBeTrue()
                }

                context("audio sender limit test") {
                    addAudioSource(ps[0]).shouldBeTrue()
                    addAudioSource(ps[1]).shouldBeTrue()
                    addAudioSource(ps[2]).shouldBeTrue()
                    addAudioSource(ps[3]).shouldBeTrue()
                    addAudioSource(ps[4]).shouldBeTrue()
                    addAudioSource(ps[5]).shouldBeFalse()
                    addAudioSource(ps[6]).shouldBeFalse()

                    ps[0].audioMute(true)
                    addAudioSource(ps[5]).shouldBeTrue()
                    addAudioSource(ps[6]).shouldBeFalse()

                    ps[1].audioMute(true)
                    addAudioSource(ps[6]).shouldBeTrue()
                }
            }
        }
    }
}

fun addVideoSource(p: MockParticipant): Boolean {
    val result = p.videoSourceAdd(longArrayOf(MockParticipant.nextSSRC()))
    if (result)
        p.videoMute(false)
    return result
}

fun addAudioSource(p: MockParticipant): Boolean {
    val result = p.audioSourceAdd(longArrayOf(MockParticipant.nextSSRC()))
    if (result)
        p.audioMute(false)
    return result
}
