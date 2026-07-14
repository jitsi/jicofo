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
package org.jitsi.jicofo.bridgeload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.ConferenceBridgeProperties
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.util.ListConferenceStore
import org.jxmpp.jid.impl.JidCreate

class LoadRedistributorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val bridgeJid = JidCreate.from("jvbbrewery@muc.example.com/jvb1")
    private val bridge: Bridge = mockk(relaxed = true) {
        every { jid } returns bridgeJid
    }
    private val bridgeSelector: BridgeSelector = mockk<BridgeSelector>().also { selector ->
        every { selector.get(any()) } answers { if (firstArg<Any>() == bridgeJid) bridge else null }
    }
    private val conferenceStore = ListConferenceStore()

    /** A conference with [participantCount] endpoints on [bridge]. Records the moves requested of it. */
    private fun addConference(name: String, participantCount: Int): JitsiMeetConference {
        val moveRequests = mutableListOf<Int>()
        val conference = mockk<JitsiMeetConference>(relaxed = true) {
            every { roomName } returns JidCreate.entityBareFrom(name)
            every { bridges } returns mapOf(
                bridge to ConferenceBridgeProperties(participantCount)
            )
            every { moveEndpoints(bridge, any()) } answers { secondArg<Int>().also { moveRequests.add(it) } }
        }
        conferenceStore.add(conference)
        return conference
    }

    private val loadRedistributor = LoadRedistributor(conferenceStore, bridgeSelector)

    init {
        context("moveEndpoint") {
            val conference = addConference("conf1@conference.example.com", 5)
            every { conference.moveEndpoint("ep1", any()) } returns true
            every { conference.moveEndpoint("ep2", any()) } returns false

            should("fail without required parameters") {
                shouldThrow<MissingParameterException> { loadRedistributor.moveEndpoint(null, "ep1", null) }
                shouldThrow<MissingParameterException> {
                    loadRedistributor.moveEndpoint("conf1@conference.example.com", null, null)
                }
            }
            should("fail with an invalid or unknown conference") {
                shouldThrow<InvalidParameterException> { loadRedistributor.moveEndpoint("invalid jid", "ep1", null) }
                shouldThrow<ConferenceNotFoundException> {
                    loadRedistributor.moveEndpoint("other@conference.example.com", "ep1", null)
                }
            }
            should("fail with an unknown bridge") {
                shouldThrow<BridgeNotFoundException> {
                    loadRedistributor.moveEndpoint("conf1@conference.example.com", "ep1", "unknown@example.com")
                }
            }
            should("move an endpoint") {
                loadRedistributor.moveEndpoint(
                    "conf1@conference.example.com",
                    "ep1",
                    bridgeJid.toString()
                ) shouldBe MoveResult(1, 1)
            }
            should("report failure to move an endpoint") {
                loadRedistributor.moveEndpoint(
                    "conf1@conference.example.com",
                    "ep2",
                    null
                ) shouldBe MoveResult(0, 0)
            }
        }
        context("moveEndpoints") {
            // Conferences sorted by size descending: big (10), medium (5), small (1)
            addConference("big@conference.example.com", 10)
            addConference("medium@conference.example.com", 5)
            addConference("small@conference.example.com", 1)

            should("fail without a bridge") {
                shouldThrow<MissingParameterException> { loadRedistributor.moveEndpoints(null, null, 1) }
            }
            should("move endpoints from the largest conference first") {
                loadRedistributor.moveEndpoints(bridgeJid.toString(), null, 8) shouldBe MoveResult(8, 1)
            }
            should("spill over to smaller conferences") {
                loadRedistributor.moveEndpoints(bridgeJid.toString(), null, 12) shouldBe MoveResult(12, 2)
            }
            should("move at most the total number of endpoints") {
                loadRedistributor.moveEndpoints(bridgeJid.toString(), null, 100) shouldBe MoveResult(16, 3)
            }
            should("only move from the given conference when specified") {
                loadRedistributor.moveEndpoints(
                    bridgeJid.toString(),
                    "medium@conference.example.com",
                    100
                ) shouldBe MoveResult(5, 1)
            }
        }
        context("moveFraction") {
            addConference("big@conference.example.com", 10)
            addConference("medium@conference.example.com", 5)
            addConference("small@conference.example.com", 1)

            should("fail without a bridge") {
                shouldThrow<MissingParameterException> { loadRedistributor.moveFraction(null, 0.5) }
            }
            should("move the given fraction of all endpoints") {
                // 0.5 of 16 = 8
                loadRedistributor.moveFraction(bridgeJid.toString(), 0.5) shouldBe MoveResult(8, 1)
            }
            should("move everything with fraction 1") {
                loadRedistributor.moveFraction(bridgeJid.toString(), 1.0) shouldBe MoveResult(16, 3)
            }
        }
    }
}
