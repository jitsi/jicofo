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
import io.kotest.matchers.shouldBe
import mock.MockParticipant
import mock.util.TestConference
import org.jitsi.jicofo.JitsiMeetConferenceImpl.MuteResult.NOT_ALLOWED
import org.jitsi.jicofo.JitsiMeetConferenceImpl.MuteResult.SUCCESS
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.utils.MediaType
import org.jxmpp.jid.impl.JidCreate

/**
 * Test one participant muting another.
 */
class MuteTest : JicofoHarnessTest() {
    override fun isolationMode(): IsolationMode? = IsolationMode.SingleInstance

    init {
        val roomName = JidCreate.entityBareFrom("test@example.com")
        val testConference = TestConference(harness, roomName)

        testConference.conference.start()
        val muter = MockParticipant("muter").also { it.join(testConference.chatRoom) }
        val mutee = MockParticipant("mutee").also { it.join(testConference.chatRoom) }

        muter.waitForSessionInitiate()
        mutee.waitForSessionInitiate()

        val mute = {
            testConference.conference.handleMuteRequest(muter.myJid, mutee.myJid, true, MediaType.AUDIO)
        }

        // No one should be allowed to unmute someone else.
        val unmute = {
            testConference.conference.handleMuteRequest(muter.myJid, mutee.myJid, false, MediaType.VIDEO)
        }

        context("When the muter is an owner") {
            muter.chatMember.role = MemberRole.OWNER
            mute() shouldBe SUCCESS
            unmute() shouldBe NOT_ALLOWED
        }
        context("When the muter is a moderator") {
            muter.chatMember.role = MemberRole.MODERATOR
            mute() shouldBe SUCCESS
            unmute() shouldBe NOT_ALLOWED
        }
        context("When the muter is a guest") {
            muter.chatMember.role = MemberRole.GUEST
            mute() shouldBe NOT_ALLOWED
            unmute() shouldBe NOT_ALLOWED
        }
    }
}
