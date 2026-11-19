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
package org.jitsi.jicofo.bridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.bridge.colibri.Colibri2Session
import org.jitsi.jicofo.bridge.colibri.ColibriV2SessionManager

class VisitorTopologyStrategyTest : ShouldSpec() {
    private fun node(name: String, isVisitor: Boolean, bridgeRegion: String) = mockk<Colibri2Session> {
        every { relayId } returns name
        every { visitor } returns isVisitor
        every { relays } returns mutableMapOf()
        every { bridge } returns mockk {
            every { region } returns bridgeRegion
            every { correctedStress } returns 0.0
            every { isOverloaded } returns false
        }
    }

    private fun cascade(vararg nodes: Colibri2Session) = mockk<ColibriV2SessionManager> {
        every { sessions } returns nodes.associateBy { it.relayId }.toMutableMap()
    }

    /** Connect [a] and [b] in the mesh [mesh]. */
    private fun link(a: Colibri2Session, b: Colibri2Session, mesh: String) {
        a.relays[b.relayId!!] = mockk {
            every { relayId } returns b.relayId
            every { meshId } returns mesh
        }
        b.relays[a.relayId!!] = mockk {
            every { relayId } returns a.relayId
            every { meshId } returns mesh
        }
    }

    init {
        context("Connecting a visitor node") {
            val strategy = VisitorTopologyStrategy()
            val newNode = node("new", isVisitor = true, bridgeRegion = "region1")

            context("When a node in the same region has no path to a core node") {
                val core = node("core", isVisitor = false, bridgeRegion = "region2")
                val disconnected = node("disconnected", isVisitor = true, bridgeRegion = "region1")

                should("connect to a node which has a path to a core node") {
                    strategy.connectNode(cascade(disconnected, core), newNode).existingNode shouldBe core
                }
            }
            context("When a node in the same region has a path to a core node") {
                val core = node("core", isVisitor = false, bridgeRegion = "region2")
                val visitor = node("visitor", isVisitor = true, bridgeRegion = "region1")
                link(core, visitor, "1")

                should("connect to the node in the same region") {
                    strategy.connectNode(cascade(core, visitor), newNode).existingNode shouldBe visitor
                }
            }
            context("When the conference has no core nodes") {
                val visitor1 = node("visitor1", isVisitor = true, bridgeRegion = "region2")
                val visitor2 = node("visitor2", isVisitor = true, bridgeRegion = "region1")
                link(visitor1, visitor2, "1")

                should("connect to the node in the same region") {
                    strategy.connectNode(cascade(visitor1, visitor2), newNode).existingNode shouldBe visitor2
                }
            }
        }
    }
}
