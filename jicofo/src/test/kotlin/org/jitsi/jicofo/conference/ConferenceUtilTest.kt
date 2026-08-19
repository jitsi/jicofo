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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.mock.MockXmppProvider
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jxmpp.jid.impl.JidCreate

class ConferenceUtilTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val v1 = MockXmppProvider(name = "v1", xmppDomain = "v1.example.com")
    private val v2 = MockXmppProvider(name = "v2", xmppDomain = "v2.example.com")
    private val allNodes = listOf(v1.xmppProvider, v2.xmppProvider)

    /** Create a room on [provider] with [visitors] visitors in it. */
    private fun room(provider: MockXmppProvider, visitors: Int): ChatRoom =
        provider.getRoom(JidCreate.entityBareFrom("room@conference.${provider.name}"))
            .also { it.visitors = visitors }.chatRoom

    init {
        context("selectVisitorNode") {
            withNewConfig("jicofo.visitors.max-visitors-per-node = 10") {
                context("With no nodes in use") {
                    should("select one of the registered nodes") {
                        listOf("v1", "v2") shouldContain selectVisitorNode(emptyMap(), allNodes)
                    }
                    should("not select a node whose connection is down") {
                        v1.registered = false
                        selectVisitorNode(emptyMap(), allNodes) shouldBe "v2"
                    }
                }
                context("With a node already in use") {
                    val existingNodes = mapOf("v1" to room(v1, 1))

                    should("re-use it while it has capacity") {
                        selectVisitorNode(existingNodes, allNodes) shouldBe "v1"
                    }
                    should("select another node once it is full") {
                        selectVisitorNode(mapOf("v1" to room(v1, 10)), allNodes) shouldBe "v2"
                    }
                    // Without this, jicofo keeps sending visitors to a node that it can not signal to, which is what
                    // happens while the node is restarting.
                    should("not re-use it while its connection is down") {
                        v1.registered = false
                        selectVisitorNode(existingNodes, allNodes) shouldBe "v2"
                    }
                    should("re-use it once its connection is back up") {
                        v1.registered = false
                        selectVisitorNode(existingNodes, allNodes) shouldBe "v2"
                        v1.registered = true
                        selectVisitorNode(existingNodes, allNodes) shouldBe "v1"
                    }
                    should("fall back to it when it is the only node, even if its connection is down") {
                        v1.registered = false
                        selectVisitorNode(existingNodes, listOf(v1.xmppProvider)) shouldBe "v1"
                    }
                }
                context("With all nodes in use and down") {
                    should("still select a node") {
                        v1.registered = false
                        v2.registered = false
                        val existingNodes = mapOf("v1" to room(v1, 1), "v2" to room(v2, 1))
                        listOf("v1", "v2") shouldContain selectVisitorNode(existingNodes, allNodes)
                    }
                }
                context("With no nodes configured") {
                    should("return null") {
                        selectVisitorNode(emptyMap(), emptyList()) shouldBe null
                    }
                }
            }
        }
    }
}
