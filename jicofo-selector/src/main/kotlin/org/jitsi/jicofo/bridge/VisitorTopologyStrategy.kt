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

/** Put participant bridges in a core mesh, and visitor bridges in region-based satellite trees. */
class VisitorTopologyStrategy : TopologySelectionStrategy() {
    private var meshCounter = 1

    private fun nextMesh(): String = meshCounter.toString().also { meshCounter++ }

    /* Pick the best node to connect a node to from a set of existing nodes. */
    private fun pickConnectionNode(node: Colibri2Session, existingNodes: Set<Colibri2Session>): Colibri2Session {
        // TODO
        return existingNodes.first()
    }

    override fun connectNode(node: Colibri2Session, existingNodes: Set<Colibri2Session>): TopologySelectionResult {
        if (!node.visitor)
            return TopologySelectionResult(existingNodes.firstOrNull(), coreMesh)

        val best = pickConnectionNode(node, existingNodes)
        return TopologySelectionResult(best, nextMesh())
    }

    override fun repairMesh(disconnected: Set<Set<Colibri2Session>>): Set<Colibri2CascadeRepair> {
        // Figure out which part of the disconnected topology contains the core.

        val ret = HashSet<Colibri2CascadeRepair>()

        val core = disconnected.firstOrNull { set -> set.any { !it.visitor } } ?:
            /* We don't have a core.  TODO: add one? */
            /* Just treat the first disconnected block as though it were the core. */
            disconnected.first()

        val others = disconnected.subtract(setOf(core))
        others.forEach {
            val connect = it.first() // Should I try to be smarter here?
            val best = pickConnectionNode(connect, core)
            ret.add(Colibri2CascadeRepair(connect, best, nextMesh()))
        }

        return ret
    }

    companion object {
        private const val coreMesh = "0"
    }
}
