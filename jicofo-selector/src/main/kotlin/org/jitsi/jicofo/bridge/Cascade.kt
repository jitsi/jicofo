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

import kotlin.collections.HashSet

/**
 * A representation of a cascade of bridges.
 */
interface Cascade<N : CascadeNode<N, L>, L : CascadeLink> {
    val sessions: MutableMap<String?, N>
    fun addLinkBetween(session: N, otherSession: N, meshId: String)
    fun removeLinkTo(session: N, otherSession: N)
}

/**
 * A representation of a single bridge in a cascade
 */
interface CascadeNode<N : CascadeNode<N, L>, L : CascadeLink> {
    val relayId: String?
    val relays: MutableMap<String, L>
}

/**
 * A representation of a link between bridges in a cascade
 */
interface CascadeLink {
    val relayId: String?
    val meshId: String?
}

data class CascadeRepair<N : CascadeNode<N, L>, L : CascadeLink>(
    val node: N,
    val other: N,
    val meshId: String
)

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.containsNode(node: N) = sessions[node.relayId] === node

fun <N : CascadeNode<N, L>, L : CascadeLink> N.hasMesh(meshId: String?): Boolean =
    relays.values.any { it.meshId == meshId }

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.hasMesh(meshId: String?): Boolean =
    sessions.values.any { it.hasMesh(meshId) }

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getMeshNodes(meshId: String?): List<N> =
    sessions.values.filter { it.hasMesh(meshId) }

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.addNodeToMesh(
    newNode: N,
    meshId: String,
    existingNode: N? = null
) {
    require(!containsNode(newNode)) {
        "Cascade $this already contains node $newNode"
    }

    if (sessions.isEmpty()) {
        sessions[newNode.relayId] = newNode
        return
    } else if (sessions.size == 1) {
        val onlyNode = sessions.values.first()
        if (existingNode != null) {
            require(existingNode == onlyNode) {
                "Cascade $this does not contain node $existingNode"
            }
        }
        addLinkBetween(onlyNode, newNode, meshId)
        sessions[newNode.relayId] = newNode
        return
    }

    val meshNodes = getMeshNodes(meshId)

    if (meshNodes.isEmpty()) {
        require(existingNode != null) {
            "An existing node must be specified (non-null) when adding a new mesh $meshId to cascade $this"
        }
        require(containsNode(existingNode)) {
            "Cascade $this does not contain node $existingNode"
        }
        addLinkBetween(existingNode, newNode, meshId)
        sessions[newNode.relayId] = newNode
    } else {
        // ?? Should we check if existingNode, if not null, is a member of meshNodes?
        meshNodes.forEach { node -> addLinkBetween(node, newNode, meshId) }
        sessions[newNode.relayId] = newNode
    }
}

fun <C : Cascade<N, L>, N : CascadeNode<N, L>, L : CascadeLink> C.removeNode(
    node: N,
    repairFn: (C, Set<Set<N>>) -> Set<CascadeRepair<N, L>>
) {
    if (!containsNode(node)) {
        return; // Or should this be an exception. i.e. `require`?
    }
    check(sessions[node.relayId] === node) {
        "Bridge entry for ${node.relayId} is not $node"
    }
    sessions.remove(node.relayId)

    node.relays.keys.forEach { key ->
        val other = sessions[key]
        checkNotNull(other) {
            "Cascade does not contain node for $key"
        }
        val backLink = other.relays[node.relayId]
        checkNotNull(backLink) {
            "Node $other does not have backlink to $node"
        }
        check(backLink.relayId == node.relayId) {
            "Backlink from $other to $node points to ${backLink.relayId}"
        }
        other.relays.remove(node.relayId)
        removeLinkTo(other, node)
        // The reverse-path links do not need to be removed, because the whole node is removed.
    }

    val meshes = node.relays.values.map { it.meshId }.toSet()

    if (meshes.size > 1) {
        /* The removed node was a bridge between two or more meshes - we need to repair the cascade. */
        val disconnected = node.relays.values.groupBy { it.meshId }.values.map {
            it.flatMap { getNodesBehind(node, it.relayId!!) }.toSet()
        }.toSet()
        val newLinks = repairFn(this, disconnected)
        newLinks.forEach { (node, other, mesh) ->
            addLinkBetween(node, other, mesh)
        }
        /* TODO: validate that the newly-added links have left us with a valid cascade? */
    }
}

/** Return a set of all nodes "behind" a given node link. */
/* TODO: would this be better as an iterator? */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getNodesBehind(from: N, toward: N): Set<N> {
    val nodes = HashSet<N>()
    val link = requireNotNull(from.relays[toward.relayId]) {
        "$from does not have a link to $toward"
    }
    getNodesBehind(link, toward, nodes)
    return nodes
}

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getNodesBehind(from: N, towardId: String): Set<N> {
    val toward = requireNotNull(sessions[towardId]) {
        "$towardId not found in sessions"
    }
    return getNodesBehind(from, toward)
}

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getNodesBehind(fromMesh: String, toward: N): Set<N> {
    val nodes = HashSet<N>()
    nodes.add(toward)
    toward.relays.values.filter { it.meshId != fromMesh }.forEach {
        val next = requireNotNull(sessions[it.relayId])
        getNodesBehind(it, next, nodes)
    }

    return nodes
}

private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getNodesBehind(
    link: L,
    toward: N,
    nodes: MutableSet<N>
) {
    nodes.add(toward)
    toward.relays.values.filter { it.meshId != link.meshId }.forEach {
        val next = checkNotNull(sessions[it.relayId])
        getNodesBehind(it, next, nodes)
    }
}

/* Traverse the graph from a node; for each other node, indicate the node from which it was reached. */
fun <C : Cascade<N, L>, N : CascadeNode<N, L>, L : CascadeLink> C.getPathsFrom(node: N, pathFn: (C, N, N?) -> Unit) {
    pathFn(this, node, null)
    node.relays.values.forEach {
        getPathsFrom(it, node, pathFn)
    }
}

private fun <C : Cascade<N, L>, N : CascadeNode<N, L>, L : CascadeLink> C.getPathsFrom(
    link: L,
    from: N,
    pathFn: (C, N, N?) -> Unit
) {
    val node = checkNotNull(sessions[link.relayId])
    pathFn(this, node, from)
    node.relays.values.filter { it.meshId != link.meshId }.forEach {
        getPathsFrom(it, node, pathFn)
    }
}

/** Validate a node, or throw IllegalStateException. */
private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.validateNode(node: N) {
    node.relays.entries.forEach { (key, link) ->
        check(key != node.relayId) {
            "$node has a link to itself"
        }
        check(key == link.relayId) {
            "$node link indexed by $key links to $link"
        }
        val other = sessions[key]
        checkNotNull(other) {
            "$node has link to $key not found in cascade"
        }
        val backLink = other.relays[node.relayId]
        checkNotNull(backLink) {
            "$node has link to $other, but $other has no link to $node"
        }
        check(link.meshId == backLink.meshId) {
            "$node links to $other in mesh ${link.meshId}, but backlink has mesh ${backLink.meshId}"
        }
    }
}

/** Validate a mesh, or throw IllegalStateException. */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.validateMesh(meshId: String?) {
    val meshNodes = getMeshNodes(meshId)

    meshNodes.forEach { node ->
        meshNodes.forEach { other ->
            if (other != node) {
                check(node.relays.contains(other.relayId)) {
                    "$node has no link to $other in mesh $meshId"
                }
                val link = node.relays[other.relayId]
                check(link!!.meshId == meshId) {
                    "link from $node to $other has meshId ${link.meshId}, not expected $meshId"
                }
            }
        }
    }
}

private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.visitNodeForValidation(
    node: N,
    parent: N?,
    visitedNodes: MutableSet<String?>,
    validatedMeshes: MutableSet<String?>
) {
    validateNode(node)
    visitedNodes.add(node.relayId)

    node.relays.values.forEach {
        if (!visitedNodes.contains(it.relayId)) {
            if (!validatedMeshes.contains(it.meshId)) {
                validateMesh(it.meshId)
                validatedMeshes.add(it.meshId)
            }
            val linkedNode = sessions[it.relayId]
            checkNotNull(linkedNode) {
                "$node has link to node ${it.relayId} not found in cascade"
            }
            visitNodeForValidation(linkedNode, node, visitedNodes, validatedMeshes)
        } else {
            check(it.relayId == parent?.relayId || validatedMeshes.contains(it.meshId)) {
                "Multiple paths found to ${sessions[it.relayId]}"
            }
        }
    }
}

/** Validate a cascade, or throw IllegalStateException */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.validate() {
    if (sessions.isEmpty()) {
        /* Empty cascade is trivially valid */
        return
    }
    val firstNode = sessions.values.first()

    val visitedNodes = HashSet<String?>()
    val validatedMeshes = HashSet<String?>()

    visitNodeForValidation(firstNode, null, visitedNodes, validatedMeshes)

    check(visitedNodes.size == sessions.size) {
        val unvisitedNodes = sessions.keys.subtract(visitedNodes)
        "Nodes ${unvisitedNodes.joinToString()} not reachable from initial node $firstNode"
    }
}

/* Get the distance, in hops in the cascade, from one node to some node satisfying a property;
 * return [Int.MAX_VALUE] if no path found.
 * Note this may not necessarily be the shortest such path, if more than one path is possible to a node satisfying
 * the predicate.
 */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getDistanceFrom(node: N, pred: (N) -> Boolean): Int {
    if (pred(node)) return 0

    node.relays.values.forEach {
        val distance = getDistanceFrom(it, pred)
        if (distance != Int.MAX_VALUE) return distance + 1
    }
    return Int.MAX_VALUE
}

private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getDistanceFrom(link: L, pred: (N) -> Boolean): Int {
    val node = checkNotNull(sessions[link.relayId])

    if (pred(node)) return 0

    node.relays.values.filter { it.meshId != link.meshId }.forEach {
        val distance = getDistanceFrom(it, pred)
        if (distance != Int.MAX_VALUE) return distance + 1
    }
    return Int.MAX_VALUE
}
