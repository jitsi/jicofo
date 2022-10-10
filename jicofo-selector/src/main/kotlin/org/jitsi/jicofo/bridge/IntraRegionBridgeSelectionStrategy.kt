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

/**
 * Implements a [BridgeSelectionStrategy] which selects bridges in a single
 * region, but uses multiple bridges for load balancing.
 */
class IntraRegionBridgeSelectionStrategy : BridgeSelectionStrategy() {
    override fun doSelect(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
    ): Bridge? {
        val participantRegion = participantProperties.region
        if (bridges.isEmpty()) {
            return null
        }
        if (conferenceBridges.isEmpty()) {
            // Try to match the participant region for the initial selection
            return notLoadedInRegion(bridges, conferenceBridges, participantProperties, participantRegion)
                ?: leastLoaded(bridges, conferenceBridges, participantProperties)
        }
        val conferenceRegion = conferenceBridges.keys.first().region
        return notLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, participantProperties, conferenceRegion)
            ?: notLoadedInRegion(bridges, conferenceBridges, participantProperties, conferenceRegion)
            ?: leastLoadedAlreadyInConferenceInRegion(
                bridges,
                conferenceBridges,
                participantProperties,
                conferenceRegion
            )
    }
}
