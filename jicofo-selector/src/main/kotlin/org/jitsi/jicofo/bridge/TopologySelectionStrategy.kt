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

/**
 * Represents a strategy for selecting bridge topologies.
 */
abstract class TopologySelectionStrategy {
    abstract fun connectNode(cascade: ColibriV2SessionManager, node: Colibri2Session): TopologySelectionResult
    abstract fun repairMesh(
        cascade: ColibriV2SessionManager,
        disconnected: Set<Set<Colibri2Session>>
    ): Set<Colibri2CascadeRepair>
}

data class TopologySelectionResult(
    val existingNode: Colibri2Session?,
    val meshId: String
)
