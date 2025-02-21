/*
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
package org.jitsi.jicofo.bridge

import org.jitsi.jicofo.bridge.colibri.Colibri2CascadeRepair
import org.jitsi.jicofo.bridge.colibri.Colibri2Session
import org.jitsi.jicofo.bridge.colibri.ColibriV2SessionManager

/** Put participant bridges in a core mesh, and visitor bridges in region-based satellite trees. */
class VisitorTopologyStrategy : TopologySelectionStrategy() {
    private var meshCounter = 1

    private fun nextMesh(): String = meshCounter.toString().also { meshCounter++ }

    /* Pick the best node to connect a node to from a set of existing nodes. */
    private fun pickConnectionNode(
        cascade: ColibriV2SessionManager,
        node: Colibri2Session,
        existingNodes: Collection<Colibri2Session>
    ): Colibri2Session {
        val nodesWithDistance = existingNodes.associateWith {
            cascade.getDistanceFrom(it) { node -> !node.visitor }
        }

        val sortedNodes = nodesWithDistance.entries.sortedWith(
            compareBy({ it.value }, { it.key.bridge.correctedStress })
        ).map { it.key }

        /* TODO: this logic looks a lot like bridge selection.  Do we want to try to share logic with that code? */
        val nonOverloadedInRegion = sortedNodes.filter {
            !it.bridge.isOverloaded && it.bridge.region == node.bridge.region
        }
        /* Is this the right tradeoff - or do we want to pick overloaded nodes in the region first? */
        val nonOverloaded = sortedNodes.filter {
            !it.bridge.isOverloaded
        }
        val inRegion = sortedNodes.filter { it.bridge.region == node.bridge.region }
        return nonOverloadedInRegion.firstOrNull()
            ?: nonOverloaded.firstOrNull()
            ?: inRegion.firstOrNull()
            ?: existingNodes.first()
    }

    override fun connectNode(cascade: ColibriV2SessionManager, node: Colibri2Session): TopologySelectionResult {
        val existingNodes = cascade.sessions.values
        if (!node.visitor) {
            return TopologySelectionResult(existingNodes.firstOrNull(), CORE_MESH)
        }

        if (existingNodes.isEmpty()) {
            /* This is the first bridge, the value doesn't matter. */
            /* TODO: if the first bridge is a visitor, do we want to add a core bridge too? */
            return TopologySelectionResult(null, nextMesh())
        }

        val best = pickConnectionNode(cascade, node, existingNodes)
        return TopologySelectionResult(best, nextMesh())
    }

    override fun repairMesh(
        cascade: ColibriV2SessionManager,
        disconnected: Set<Set<Colibri2Session>>
    ): Set<Colibri2CascadeRepair> {
        // Figure out which part of the disconnected topology contains the core.

        val ret = HashSet<Colibri2CascadeRepair>()

        val core = disconnected.firstOrNull { set -> set.any { !it.visitor } }
            /* We don't have a core.  TODO: add one? */
            /* Just treat the first disconnected block as though it were the core. */
            ?: disconnected.first()

        val others = disconnected.subtract(setOf(core))
        others.forEach {
            val connect = it.first() // Should I try to be smarter here?
            val best = pickConnectionNode(cascade, connect, core)
            ret.add(Colibri2CascadeRepair(connect, best, nextMesh()))
        }

        return ret
    }

    companion object {
        private const val CORE_MESH = "0"
    }
}
