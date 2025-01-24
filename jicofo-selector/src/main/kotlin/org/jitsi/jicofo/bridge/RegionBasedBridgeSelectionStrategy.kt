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

import org.jitsi.jicofo.JicofoConfig
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl

/**
 * Implements a [BridgeSelectionStrategy] which attempts to select bridges in the participant's region.
 *
 * Specifically, it implements the following algorithm:
 *
 * (Happy case 1): If there is a non-overloaded bridge in the conference
 * and in the region: use the least loaded of them.
 * (Happy case 1A): If there is a non-overloaded bridge in the conference
 * and in the region's group: use the least loaded of them.
 * (Happy case 2): If there is a non-overloaded bridge in the region,
 * and the conference has no bridges in the region: use the least loaded bridge
 * in the region.
 * (Happy case 2A): If there is a non-overloaded bridge in the region's group,
 * and the conference has no bridges in the region's group: use the least loaded bridge
 * in the region's group.
 *
 * (Split case 1): If there is a non-overloaded bridge in the region, the
 * conference has bridges in the region but all are overloaded: Use the least
 * loaded of the bridges in the region.
 * (Split case 1A): If there is a non-overloaded bridge in the region's group, the
 * conference has bridges in the region's group but all are overloaded: Use the least
 * loaded of the bridges in the region.
 *
 * (Overload case 1): If all bridges in the region's group are overloaded, and the
 * conference has a bridge in the region's group: use the least loaded conference
 * bridge in the region's group.
 * (Overload case 2): If all bridges in the region's group are overloaded, and the
 * conference has no bridges in the region's group: use the least loaded bridge in
 * the region's group.
 *
 * (No-region-match case 1): If there are no bridges in the region's group, and the
 * conference has a non-overloaded bridge: use the least loaded conference bridge.
 * (No-region-match case 2): If there are no bridges in the region's group and all
 * conference bridges are overloaded: use the least loaded bridge.
 *
 */
class RegionBasedBridgeSelectionStrategy : BridgeSelectionStrategy() {
    /**
     * Map a region to the set of all regions in its group (including itself).
     */
    val localRegion = JicofoConfig.config.localRegion()

    override fun doSelect(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties
    ): Bridge? {
        if (bridges.isEmpty()) {
            return null
        }
        val participantRegion = participantProperties.region
        var region = participantRegion ?: localRegion
        if (localRegion != null) {
            val regionGroup = BridgeConfig.config.getRegionGroup(region)
            if (conferenceBridges.isEmpty() && region != localRegion) {
                // Selecting an initial bridge for a participant not in the local region. This is most likely because
                // exactly one of the first two participants in the conference is not in the local region, and we're
                // selecting for it first. I.e. there is another participant in the local region which will be
                // subsequently invited.
                if (regionGroup.contains(localRegion)) {
                    // With the above assumption, there are two participants in the local region group. Therefore,
                    // they will use the same bridge. Prefer to use a bridge in the local region.
                    region = localRegion
                }
            }

            // If there are no bridges in the participant region or region group, select from the local region instead.
            if (bridges.none { regionGroup.contains(it.region) }) {
                region = localRegion
            }
        }
        return notLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, participantProperties, region)
            ?: notLoadedAlreadyInConferenceInRegionGroup(bridges, conferenceBridges, participantProperties, region)
            ?: notLoadedInRegion(bridges, conferenceBridges, participantProperties, region)
            ?: notLoadedInRegionGroup(bridges, conferenceBridges, participantProperties, region)
            ?: notLoadedAlreadyInConference(bridges, conferenceBridges, participantProperties)
            ?: notLoaded(bridges, conferenceBridges, participantProperties)
            ?: leastLoadedNotMaxedAlreadyInConference(bridges, conferenceBridges, participantProperties)
            ?: leastLoaded(bridges, conferenceBridges, participantProperties)
    }

    override fun toString(): String {
        return "${javaClass.simpleName} with region groups: ${BridgeConfig.config.regionGroups}"
    }

    override fun select(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        allowMultiBridge: Boolean
    ): Bridge? {
        if (!allowMultiBridge) {
            logger.warn(
                "Octo is disabled, but the selection strategy is RegionBased. Enable octo in jicofo.conf to " +
                    "allow use of multiple bridges in a conference."
            )
        }
        return super.select(bridges, conferenceBridges, participantProperties, allowMultiBridge)
    }

    companion object {
        private val logger: Logger = LoggerImpl(RegionBasedBridgeSelectionStrategy::class.java.name)
    }
}
