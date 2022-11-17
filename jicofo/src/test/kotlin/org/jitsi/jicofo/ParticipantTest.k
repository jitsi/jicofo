/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import mock.muc.MockRoomMember
import org.jitsi.jicofo.conference.Participant
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jxmpp.jid.impl.JidCreate

class ParticipantTest : ShouldSpec() {
    init {
        context("testRestartReqRateLimiting") {
            val clock = FakeClock()
            val p = Participant(
                MockRoomMember(JidCreate.entityFullFrom("something@server.com/1234"), null),
                conference = mockk(),
                clock = clock
            )

            should("allow 1st request") {
                p.incrementAndCheckRestartRequests() shouldBe true
            }
            should("not allow next request immediately") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(5.secs)
            should("not allow next request after 5 seconds") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(6.secs)
            should("allow 2nd request after 11 seconds") {
                p.incrementAndCheckRestartRequests() shouldBe true
            }
            should("not allow 3rd request after 11 seconds") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(10.secs)
            should("allow 3rd request after 21 seconds") {
                p.incrementAndCheckRestartRequests() shouldBe true
            }
            clock.elapse(11.secs)
            should("not allow more than 3 request within the last minute (31 second)") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(10.secs)
            should("not allow more than 3 request within the last minute (41 second)") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(10.secs)
            should("not allow more than 3 request within the last minute (51 second)") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(10.secs)
            should("allow the 4th request after 60 seconds have passed since the 1st (61 second)") {
                p.incrementAndCheckRestartRequests() shouldBe true
            }
            clock.elapse(5.secs)
            should("not allow the 5th request in 66th second") {
                p.incrementAndCheckRestartRequests() shouldBe false
            }
            clock.elapse(5.secs)
            should("allow the 5th request in 71st second") {
                p.incrementAndCheckRestartRequests() shouldBe true
            }
        }
    }
}