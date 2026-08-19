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
package org.jitsi.jicofo.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.verify
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.mock.ConferenceHarness
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.jicofo.xmpp.muc.ChatRoomInfo
import org.jitsi.jicofo.xmpp.muc.MemberRole

/**
 * Tests the way a conference handles the visitor node that it uses, in particular the node restarting.
 */
class ConferenceVisitorsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
    }

    init {
        context("A conference with a visitor node") {
            withNewConfig(
                """
                jicofo.visitors {
                    enabled = true
                    max-participants = 1
                    max-visitors-per-node = 100
                    require-muc-config-flag = false
                    auto-enable-broadcast = true
                }
                jicofo.xmpp.visitors.v1 {
                    hostname = "v1.example.com"
                    xmpp-domain = "v1.example.com"
                    conference-service = "conference.v1.example.com"
                }
                """
            ) {
                val harness = ConferenceHarness(visitorNodeNames = listOf("v1"))
                val conference = harness.conference
                val visitorRoom = harness.visitorRoom("v1")
                every { harness.chatRoom.chatRoom.visitorsEnabled } returns true

                // Two participants in the main room, so that the conference starts inviting.
                harness.addParticipants(2)

                context("Redirecting a visitor") {
                    conference.redirectVisitor(true, null, null) shouldBe "v1"

                    should("join the visitor room and connect the node") {
                        verify(exactly = 1) { visitorRoom.chatRoom.join() }
                        harness.connectedVnodes() shouldContain "v1"
                        conference.visitorRoomsJids shouldContainExactly listOf(visitorRoom.roomJid)
                    }
                    should("invite the visitors that join the room") {
                        val visitor = visitorRoom.addMember("visitor-1", MemberRole.VISITOR)
                        conference.getParticipant(visitor.occupantJid) shouldNotBe null
                    }
                }

                context("When the visitor node is restarted") {
                    conference.redirectVisitor(true, null, null) shouldBe "v1"
                    val visitor = visitorRoom.addMember("visitor-1", MemberRole.VISITOR)
                    conference.getParticipant(visitor.occupantJid) shouldNotBe null

                    // The node lost the state of the MUC, so jicofo is not an occupant of it anymore.
                    conference.visitorConnectionReset("v1")

                    should("leave the stale visitor room") {
                        verify { visitorRoom.chatRoom.leave() }
                        conference.visitorRoomsJids.shouldBeEmpty()
                    }
                    should("disconnect the node") {
                        harness.disconnectedVnodes() shouldContain "v1"
                    }
                    // We do not receive presence for these visitors leaving, so without this each one keeps a
                    // Participant and an endpoint on a bridge for the rest of the conference.
                    should("terminate the visitors that were in the stale room") {
                        conference.getParticipant(visitor.occupantJid) shouldBe null
                    }
                    should("join the room again when the next visitor is redirected") {
                        conference.redirectVisitor(true, null, null) shouldBe "v1"
                        verify(exactly = 2) { visitorRoom.chatRoom.join() }
                        conference.visitorRoomsJids shouldContainExactly listOf(visitorRoom.roomJid)
                        harness.connectedVnodes() shouldContainExactly listOf("v1", "v1")
                    }
                }

                context("When a node that the conference does not use is restarted") {
                    conference.redirectVisitor(true, null, null) shouldBe "v1"

                    should("keep the visitor room") {
                        conference.visitorConnectionReset("v2")

                        harness.disconnectedVnodes().shouldBeEmpty()
                        conference.visitorRoomsJids shouldContainExactly listOf(visitorRoom.roomJid)
                    }
                }

                context("When joining the visitor room fails") {
                    every { visitorRoom.chatRoom.join() } throws Exception("Failed to join")
                    shouldThrow<Exception> { conference.redirectVisitor(true, null, null) }

                    // Otherwise the conference keeps sending visitors to a room that it never joined.
                    should("not keep the room that it failed to join") {
                        conference.visitorRoomsJids.shouldBeEmpty()
                    }
                    should("try to join again for the next visitor") {
                        every { visitorRoom.chatRoom.join() } returns ChatRoomInfo(null, null)

                        conference.redirectVisitor(true, null, null) shouldBe "v1"
                        verify(exactly = 2) { visitorRoom.chatRoom.join() }
                        conference.visitorRoomsJids shouldContainExactly listOf(visitorRoom.roomJid)
                    }
                }
            }
        }
    }
}
