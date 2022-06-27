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
    val links: MutableMap<String, CascadeLink>
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
    bridges.values.stream().anyMatch { node -> node.links.values.any { it.meshId == meshId } }

fun Cascade.getMeshNodes(meshId: String): List<CascadeNode> =
    bridges.values.stream().filter { node -> node.links.values.any { it.meshId == meshId } }.toList()

fun CascadeNode.addBidirectionalLink(otherNode: CascadeNode, meshId: String) {
    require(!this.links.contains(otherNode.relayId)) {
        "$this already has a link to $otherNode"
    }
    require(!otherNode.links.contains(this.relayId)) {
        "$otherNode already has a link to $this"
    }
    otherNode.addLink(this, meshId)
    this.addLink(otherNode, meshId)
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

fun Cascade.removeNode(
    node: CascadeNode,
    repairFn: (Cascade, Set<String>) -> Set<Triple<CascadeNode, CascadeNode, String>>
) {
    if (!containsNode(node)) {
        return; /* Or should this be an exception. i.e. `require`? */
    }
    check(bridges[node.relayId] === node) {
        "Bridge entry for ${node.relayId} is not $node"
    }
    bridges.remove(node.relayId)

    node.links.entries.forEach { (key, value) ->
        val other = bridges[key]
        checkNotNull(other) {
            "Cascade does not contain node for $key"
        }
        val backLink = other.links[node.relayId]
        checkNotNull(backLink) {
            "Node $other does not have backlink to $node"
        }
        check(backLink.relayId == node.relayId) {
            "Backlink from $other to $node points to ${backLink.relayId}"
        }
        other.links.remove(node.relayId)
    }

    val meshes = node.links.values.map { it.meshId }.toSet()
    val remainingMeshes = meshes.filter { hasMesh(it) }.toSet()

    if (remainingMeshes.size > 1) {
        /* The removed node was a bridge between two or more meshes - we need to repair the cascade. */
        val newLinks = repairFn(this, remainingMeshes)
        newLinks.forEach { (node, other, mesh) ->
            node.addBidirectionalLink(other, mesh)
        }
        /* TODO: validate that the newly-added links have left us with a valid cascade? */
    }
}

/** Validate a node, or throw IllegalStateException. */
private fun Cascade.validateNode(node: CascadeNode) {
    node.links.entries.forEach { (key, link) ->
        check(key != node.relayId) {
            "$node has a link to itself"
        }
        check(key == link.relayId) {
            "$node link indexed by $key links to $link"
        }
        val other = bridges[key]
        checkNotNull(other) {
            "$node has link to $key not found in cascade"
        }
        val backLink = other.links[node.relayId]
        checkNotNull(backLink) {
            "$node has link to $other, but $other has no link to $node"
        }
        check(link.meshId == backLink.meshId) {
            "$node links to $other in mesh ${link.meshId}, but backlink has mesh ${backLink.meshId}"
        }
    }
}

/** Validate a mesh, or throw IllegalStateException. */
fun Cascade.validateMesh(meshId: String) {
    val meshNodes = getMeshNodes(meshId)

    meshNodes.forEach { node ->
        meshNodes.forEach { other ->
            if (other != node) {
                check(node.links.contains(other.relayId)) {
                    "$node has no link to $other in mesh $meshId"
                }
                val link = node.links[other.relayId]
                check(link!!.meshId == meshId) {
                    "link from $node to $other has meshId ${link.meshId}, not expected $meshId"
                }
            }
        }
    }
}

private fun Cascade.visitNode(
    node: CascadeNode,
    parent: CascadeNode?,
    visitedNodes: MutableSet<String>,
    validatedMeshes: MutableSet<String>
) {
    validateNode(node)
    visitedNodes.add(node.relayId)

    node.links.values.forEach {
        if (!visitedNodes.contains(it.relayId)) {
            if (!validatedMeshes.contains(it.meshId)) {
                validateMesh(it.meshId)
                validatedMeshes.add(it.meshId)
            }
            val linkedNode = bridges[it.relayId]
            checkNotNull(linkedNode) {
                "$node has link to node ${it.relayId} not found in cascade"
            }
            visitNode(linkedNode, node, visitedNodes, validatedMeshes)
        } else {
            check(it.relayId == parent?.relayId || validatedMeshes.contains(it.meshId)) {
                "Multiple paths found to ${bridges[it.relayId]}"
            }
        }
    }
}

/** Validate a cascade, or throw IllegalStateException */
fun Cascade.validate() {
    if (bridges.isEmpty()) {
        /* Empty cascade is trivially valid */
        return
    }
    val firstNode = bridges.values.first()

    val visitedNodes = HashSet<String>()
    val validatedMeshes = HashSet<String>()

    visitNode(firstNode, null, visitedNodes, validatedMeshes)

    check(visitedNodes.size == bridges.size) {
        val unvisitedNodes = bridges.keys.subtract(visitedNodes)
        "Nodes ${unvisitedNodes.joinToString()} not reachable from initial node $firstNode"
    }
}
