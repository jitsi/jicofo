/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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

package org.jitsi.jicofo.cascade

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class TestCascade : Cascade {
    override val bridges = HashMap<String, CascadeNode>()
}

class TestCascadeNode(override val relayId: String) : CascadeNode {
    override val links = HashMap<String, CascadeLink>()
    override fun addLink(node: CascadeNode, meshId: String) {
        links[node.relayId] = TestCascadeLink(node.relayId, meshId)
    }

    override fun toString(): String = "$relayId: [${links.values.joinToString()}]"
}

class TestCascadeLink(override val relayId: String, override val meshId: String) : CascadeLink {
    override fun toString(): String = "$meshId->$relayId"
}

class CascadeTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val numNodes = 5
    init {
        context("creating a cascade with a single mesh") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }
            nodes.iterator().forEach { cascade.addNodeToMesh(it, "A") }

            should("cause all the nodes to be connected") {
                nodes.forEach { node ->
                    cascade.containsNode(node) shouldBe true
                    node.links.size shouldBe numNodes - 1

                    nodes.forEach { other ->
                        if (other === node) {
                            node.links.contains(other.relayId) shouldBe false
                        } else {
                            node.links[other.relayId]?.relayId shouldBe other.relayId
                        }
                    }
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("enumerate only one node behind each link") {
                nodes.forEach { node ->
                    nodes.forEach { other ->
                        if (node != other) {
                            cascade.getNodesBehind(node, other).shouldContainExactly(other)
                        }
                    }
                }
            }
            should("not call a callback when removing any node") {
                nodes.forEach {
                    var called = false
                    cascade.removeNode(it) { _, _ ->
                        called = true
                        setOf()
                    }
                    called shouldBe false
                }
                cascade.validate()
            }
        }
        context("creating a cascade with two meshes") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }
            cascade.addNodeToMesh(nodes[0], "A")
            cascade.addNodeToMesh(nodes[1], "A")
            cascade.addNodeToMesh(nodes[2], "A")

            cascade.addMesh(nodes[0], nodes[3], "B")
            cascade.addNodeToMesh(nodes[4], "B")

            should("create the correct links for each node") {
                val aNodes = cascade.getMeshNodes("A")
                val bNodes = cascade.getMeshNodes("B")

                aNodes.map { it.relayId }.shouldContainExactly("0", "1", "2")
                bNodes.map { it.relayId }.shouldContainExactly("0", "3", "4")

                nodes[0].links.size shouldBe 4
                for (i in 1 until numNodes) {
                    nodes[i].links.size shouldBe 2
                }

                arrayOf(aNodes, bNodes).forEach { set ->
                    set.forEach { node ->
                        set.forEach { other ->
                            if (other === node) {
                                node.links.contains(other.relayId) shouldBe false
                            } else {
                                node.links[other.relayId]?.relayId shouldBe other.relayId
                            }
                        }
                    }
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("enumerate only one node behind each leaf node from the core node") {
                for (i in 1 until numNodes) {
                    cascade.getNodesBehind(nodes[0], nodes[i]).shouldContainExactly(nodes[i])
                }
            }
            should("enumerate the nodes of the far mesh behind the core node from each leaf node") {
                cascade.getNodesBehind(nodes[1], nodes[0]).shouldContainExactly(nodes[0], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[2], nodes[0]).shouldContainExactly(nodes[0], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[3], nodes[0]).shouldContainExactly(nodes[0], nodes[1], nodes[2])
                cascade.getNodesBehind(nodes[4], nodes[0]).shouldContainExactly(nodes[0], nodes[1], nodes[2])
            }
            should("not call a callback when removing a leaf node") {
                for (i in 1 until numNodes) {
                    var called = false
                    cascade.removeNode(nodes[i]) { _, _ ->
                        called = true
                        setOf()
                    }
                    called shouldBe false
                }
                cascade.validate()
            }
            should("call the callback when removing the core node") {
                var called = true
                cascade.removeNode(nodes[0]) { _, _ ->
                    called = true
                    setOf()
                }
                called shouldBe true
            }
            should("fail validation if not repaired after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ -> setOf() }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
            should("validate if repaired correctly (1) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(Triple(nodes[1], nodes[3], "C"))
                }
                cascade.validate()
            }
            should("validate if repaired correctly (2) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(
                        Triple(nodes[1], nodes[3], "A"),
                        Triple(nodes[2], nodes[3], "A")
                    )
                }
                cascade.validate()
            }
            should("not validate if repaired incorrectly (1) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(Triple(nodes[1], nodes[3], "A"))
                }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
            should("not validate if repaired incorrectly (2) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(
                        Triple(nodes[1], nodes[3], "C"),
                        Triple(nodes[2], nodes[4], "D")
                    )
                }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
        }

        context("creating a star topology") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }

            cascade.addNodeToMesh(nodes[0], "A")
            for (i in 1 until numNodes) {
                cascade.addMesh(nodes[0], nodes[i], ('A'.code + i).toChar().toString())
            }

            should("create the correct links for each node") {
                cascade.getMeshNodes("A").size shouldBe 0
                for (m in 'B'..'E') {
                    cascade.getMeshNodes(m.toString()).size shouldBe 2
                }
                nodes[0].links.size shouldBe 4
                for (i in 1 until numNodes) {
                    nodes[i].links.size shouldBe 1
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("enumerate only one node behind each leaf node from the core node") {
                for (i in 1 until numNodes) {
                    cascade.getNodesBehind(nodes[0], nodes[i]).shouldContainExactly(nodes[i])
                }
            }
            should("enumerate all the other nodes behind the core node from each leaf node") {
                cascade.getNodesBehind(nodes[1], nodes[0]).shouldContainExactly(nodes[0], nodes[2], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[2], nodes[0]).shouldContainExactly(nodes[0], nodes[1], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[3], nodes[0]).shouldContainExactly(nodes[0], nodes[1], nodes[2], nodes[4])
                cascade.getNodesBehind(nodes[4], nodes[0]).shouldContainExactly(nodes[0], nodes[1], nodes[2], nodes[3])
            }
            should("not call a callback when removing a leaf node") {
                for (i in 1 until numNodes) {
                    var called = false
                    cascade.removeNode(nodes[i]) { _, _ ->
                        called = true
                        setOf()
                    }
                    called shouldBe false
                }
            }
            should("call the callback when removing the core node") {
                var called = false
                cascade.removeNode(nodes[0]) { _, _ ->
                    called = true
                    setOf()
                }
                called shouldBe true
            }
            should("fail validation if not repaired after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ -> setOf() }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
            should("validate when repaired correctly after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(
                        Triple(nodes[1], nodes[2], "B"),
                        Triple(nodes[1], nodes[3], "C"),
                        Triple(nodes[1], nodes[4], "D")
                    )
                }
                cascade.validate()
            }
        }
    }
}
