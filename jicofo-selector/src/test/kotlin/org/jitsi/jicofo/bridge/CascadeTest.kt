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

package org.jitsi.jicofo.bridge

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class TestCascade : Cascade<TestCascadeNode, TestCascadeLink> {
    override val sessions = HashMap<String?, TestCascadeNode>()

    var linksRemoved = 0

    override fun addLinkBetween(session: TestCascadeNode, otherSession: TestCascadeNode, meshId: String) {
        require(!session.relays.contains(otherSession.relayId)) {
            "$this already has a link to $otherSession"
        }
        require(!otherSession.relays.contains(session.relayId)) {
            "$otherSession already has a link to $this"
        }
        session.addLink(otherSession, meshId)
        otherSession.addLink(session, meshId)
    }

    override fun removeLinkTo(session: TestCascadeNode, otherSession: TestCascadeNode) {
        linksRemoved++
    }
}

class TestCascadeNode(override val relayId: String) : CascadeNode<TestCascadeNode, TestCascadeLink> {
    override val relays = HashMap<String, TestCascadeLink>()
    internal fun addLink(node: TestCascadeNode, meshId: String) {
        relays[node.relayId] = TestCascadeLink(node.relayId, meshId)
    }

    override fun toString(): String = "$relayId: [${relays.values.joinToString()}]"
}

class TestCascadeLink(override val relayId: String, override val meshId: String?) : CascadeLink {
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
                    node.relays.size shouldBe numNodes - 1

                    nodes.forEach { other ->
                        if (other === node) {
                            node.relays.contains(other.relayId) shouldBe false
                        } else {
                            node.relays[other.relayId]?.relayId shouldBe other.relayId
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
                            cascade.getNodesBehind(node, other).shouldContainExactlyInAnyOrder(other)
                        }
                    }
                    cascade.getNodesBehind("A", node).shouldContainExactlyInAnyOrder(node)
                }
            }
            should("have paths directly from each node") {
                nodes.forEach { node ->
                    var pathsVisited = 0
                    cascade.getPathsFrom(node) { c, n, from ->
                        c shouldBe cascade
                        if (n == node) {
                            from shouldBe null
                        } else {
                            from shouldBe node
                        }
                        pathsVisited++
                    }
                    pathsVisited shouldBe numNodes
                }
            }
            should("have the correct distance from each node to another") {
                nodes.forEach { first ->
                    nodes.forEach { other ->
                        cascade.getDistanceFrom(first) { it == other } shouldBe if (first == other) 0 else 1
                    }
                }
                cascade.getDistanceFrom(nodes[1]) { false } shouldBe Int.MAX_VALUE
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
                cascade.linksRemoved shouldBe 4 + 3 + 2 + 1
            }
        }
        context("creating a cascade with two meshes") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }
            cascade.addNodeToMesh(nodes[0], "A")
            cascade.addNodeToMesh(nodes[1], "A")
            cascade.addNodeToMesh(nodes[2], "A")

            cascade.addNodeToMesh(nodes[3], "B", nodes[0])
            cascade.addNodeToMesh(nodes[4], "B")

            should("create the correct links for each node") {
                val aNodes = cascade.getMeshNodes("A")
                val bNodes = cascade.getMeshNodes("B")

                aNodes.map { it.relayId }.shouldContainExactly("0", "1", "2")
                bNodes.map { it.relayId }.shouldContainExactly("0", "3", "4")

                nodes[0].relays.size shouldBe 4
                for (i in 1 until numNodes) {
                    nodes[i].relays.size shouldBe 2
                }

                arrayOf(aNodes, bNodes).forEach { set ->
                    set.forEach { node ->
                        set.forEach { other ->
                            if (other === node) {
                                node.relays.contains(other.relayId) shouldBe false
                            } else {
                                node.relays[other.relayId]?.relayId shouldBe other.relayId
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
                    cascade.getNodesBehind(nodes[0], nodes[i]).shouldContainExactlyInAnyOrder(nodes[i])
                }
            }
            should("enumerate the nodes of the far mesh behind the core node from each leaf node") {
                cascade.getNodesBehind(nodes[1], nodes[0]).shouldContainExactlyInAnyOrder(nodes[0], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[2], nodes[0]).shouldContainExactlyInAnyOrder(nodes[0], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[3], nodes[0]).shouldContainExactlyInAnyOrder(nodes[0], nodes[1], nodes[2])
                cascade.getNodesBehind(nodes[4], nodes[0]).shouldContainExactlyInAnyOrder(nodes[0], nodes[1], nodes[2])

                cascade.getNodesBehind("A", nodes[0]).shouldContainExactlyInAnyOrder(nodes[0], nodes[3], nodes[4])
                cascade.getNodesBehind("B", nodes[0]).shouldContainExactlyInAnyOrder(nodes[0], nodes[1], nodes[2])
            }
            should("have paths directly from the core node") {
                var pathsVisited = 0
                cascade.getPathsFrom(nodes[0]) { c, n, from ->
                    c shouldBe cascade
                    if (n == nodes[0]) {
                        from shouldBe null
                    } else {
                        from shouldBe nodes[0]
                    }
                    pathsVisited++
                }
                pathsVisited shouldBe numNodes
            }
            should("have non-local paths from other nodes through the core node") {
                for (i in 1 until numNodes) {
                    val locals = when (i) {
                        1, 2 -> setOf(nodes[0], nodes[1], nodes[2])
                        3, 4 -> setOf(nodes[0], nodes[3], nodes[4])
                        else -> setOf()
                    }
                    var pathsVisited = 0
                    cascade.getPathsFrom(nodes[i]) { c, n, from ->
                        c shouldBe cascade
                        when {
                            n == nodes[i] -> from shouldBe null
                            locals.contains(n) -> from shouldBe nodes[i]
                            else -> from shouldBe nodes[0]
                        }
                        pathsVisited++
                    }
                    pathsVisited shouldBe numNodes
                }
            }
            should("have the correct distance from each node to another") {
                for (i in 1 until numNodes) {
                    cascade.getDistanceFrom(nodes[i]) { it == nodes[0] } shouldBe 1
                    cascade.getDistanceFrom(nodes[0]) { it == nodes[i] } shouldBe 1
                }
                // Just spot-check these rather than enumerating all of them
                cascade.getDistanceFrom(nodes[1]) { it == nodes[2] } shouldBe 1
                cascade.getDistanceFrom(nodes[1]) { it == nodes[3] } shouldBe 2
                cascade.getDistanceFrom(nodes[1]) { false } shouldBe Int.MAX_VALUE
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
                cascade.linksRemoved shouldBe 3 + 2 + 1
            }
            should("call the callback when removing the core node") {
                var called = true
                cascade.removeNode(nodes[0]) { _, disconnectedMeshes ->
                    called = true
                    disconnectedMeshes.size shouldBe 2
                    disconnectedMeshes.shouldContainExactlyInAnyOrder(
                        setOf(nodes[1], nodes[2]),
                        setOf(nodes[3], nodes[4])
                    )
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
                    setOf(CascadeRepair(nodes[1], nodes[3], "C"))
                }
                cascade.validate()
            }
            should("validate if repaired correctly (2) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(
                        CascadeRepair(nodes[1], nodes[3], "A"),
                        CascadeRepair(nodes[2], nodes[3], "A")
                    )
                }
                cascade.validate()
            }
            should("not validate if repaired incorrectly (1) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(CascadeRepair(nodes[1], nodes[3], "A"))
                }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
            should("not validate if repaired incorrectly (2) after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ ->
                    setOf(
                        CascadeRepair(nodes[1], nodes[3], "C"),
                        CascadeRepair(nodes[2], nodes[4], "D")
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
                cascade.addNodeToMesh(nodes[i], ('A'.code + i).toChar().toString(), nodes[0])
            }

            should("create the correct links for each node") {
                cascade.getMeshNodes("A").size shouldBe 0
                for (m in 'B'..'E') {
                    cascade.getMeshNodes(m.toString()).size shouldBe 2
                }
                nodes[0].relays.size shouldBe 4
                for (i in 1 until numNodes) {
                    nodes[i].relays.size shouldBe 1
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("enumerate only one node behind each leaf node from the core node") {
                for (i in 1 until numNodes) {
                    cascade.getNodesBehind(nodes[0], nodes[i]).shouldContainExactlyInAnyOrder(nodes[i])
                }
            }
            should("enumerate all the other nodes behind the core node from each leaf node") {
                cascade.getNodesBehind(nodes[1], nodes[0])
                    .shouldContainExactlyInAnyOrder(nodes[0], nodes[2], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[2], nodes[0])
                    .shouldContainExactlyInAnyOrder(nodes[0], nodes[1], nodes[3], nodes[4])
                cascade.getNodesBehind(nodes[3], nodes[0])
                    .shouldContainExactlyInAnyOrder(nodes[0], nodes[1], nodes[2], nodes[4])
                cascade.getNodesBehind(nodes[4], nodes[0])
                    .shouldContainExactlyInAnyOrder(nodes[0], nodes[1], nodes[2], nodes[3])
            }
            should("have paths directly from the core node") {
                var pathsVisited = 0
                cascade.getPathsFrom(nodes[0]) { c, n, from ->
                    c shouldBe cascade
                    if (n == nodes[0]) {
                        from shouldBe null
                    } else {
                        from shouldBe nodes[0]
                    }
                    pathsVisited++
                }
                pathsVisited shouldBe numNodes
            }
            should("have paths from other nodes through the core node") {
                for (i in 1 until numNodes) {
                    var pathsVisited = 0
                    cascade.getPathsFrom(nodes[i]) { c, n, from ->
                        c shouldBe cascade
                        when (n) {
                            nodes[i] -> from shouldBe null
                            nodes[0] -> from shouldBe nodes[i]
                            else -> from shouldBe nodes[0]
                        }
                        pathsVisited++
                    }
                    pathsVisited shouldBe numNodes
                }
            }
            should("have the correct distance from each node to another") {
                for (i in 1 until numNodes) {
                    cascade.getDistanceFrom(nodes[i]) { it == nodes[0] } shouldBe 1
                    cascade.getDistanceFrom(nodes[0]) { it == nodes[i] } shouldBe 1
                    for (j in 1 until numNodes) {
                        cascade.getDistanceFrom(nodes[i]) { it == nodes[j] } shouldBe if (i == j) 0 else 2
                    }
                    cascade.getDistanceFrom(nodes[i]) { false } shouldBe Int.MAX_VALUE
                }
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
                cascade.removeNode(nodes[0]) { _, disconnectedMeshes ->
                    called = true
                    disconnectedMeshes.size shouldBe 4
                    disconnectedMeshes.shouldContainExactlyInAnyOrder(
                        setOf(nodes[1]),
                        setOf(nodes[2]),
                        setOf(nodes[3]),
                        setOf(nodes[4])
                    )
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
                        CascadeRepair(nodes[1], nodes[2], "B"),
                        CascadeRepair(nodes[1], nodes[3], "C"),
                        CascadeRepair(nodes[1], nodes[4], "D")
                    )
                }
                cascade.validate()
            }
        }
    }
}
