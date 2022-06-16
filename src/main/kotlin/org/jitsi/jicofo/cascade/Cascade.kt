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

import kotlin.streams.toList

/**
 * A representation of a cascade of bridges.
 */
interface Cascade {
    val bridges: MutableMap<String, CascadeNode>
}

/**
 * A representation of a single bridge in a cascade
 */
interface CascadeNode {
    val relayId: String
    val links: MutableSet<CascadeLink>
    fun addLink(node: CascadeNode, meshId: String)
}

/**
 * A representation of a link between bridges in a cascade
 */
interface CascadeLink {
    val relayId: String
    val meshId: String
}

fun Cascade.containsNode(node: CascadeNode) =
    bridges[node.relayId] === node

fun Cascade.hasMesh(meshId: String): Boolean =
    bridges.values.stream().anyMatch { node -> node.links.any { it.meshId == meshId } }

fun Cascade.getMeshNodes(meshId: String): List<CascadeNode> =
    bridges.values.stream().filter { node -> node.links.any { it.meshId == meshId } }.toList()

fun CascadeNode.addBidirectionalLink(newNode: CascadeNode, meshId: String) {
    newNode.addLink(this, meshId)
    addLink(newNode, meshId)
}

fun Cascade.addNodeToMesh(newNode: CascadeNode, meshId: String) {
    require(!containsNode(newNode)) {
        "Cascade $this already contains node $newNode"
    }

    if (bridges.isEmpty()) {
        bridges[newNode.relayId] = newNode
        return
    } else if (bridges.size == 1) {
        val onlyNode = bridges.values.first()
        onlyNode.addBidirectionalLink(newNode, meshId)
        bridges[newNode.relayId] = newNode
        return
    }

    val meshNodes = getMeshNodes(meshId)
    require(meshNodes.isNotEmpty()) {
        "meshId $meshId must correspond to an existing mesh ID when size ${bridges.size} > 1"
    }

    meshNodes.forEach { node -> node.addBidirectionalLink(newNode, meshId) }
    bridges[newNode.relayId] = newNode
}

fun Cascade.addMesh(existingNode: CascadeNode, newNode: CascadeNode, meshId: String) {
    require(containsNode(existingNode)) {
        "Cascade $this does not contain node $existingNode"
    }

    require(!containsNode(newNode)) {
        "Cascade $this already contains node $newNode"
    }

    require(!hasMesh(meshId)) {
        "Cascade $this already contains mesh $meshId"
    }

    existingNode.addBidirectionalLink(newNode, meshId)
    bridges[newNode.relayId] = newNode
}
