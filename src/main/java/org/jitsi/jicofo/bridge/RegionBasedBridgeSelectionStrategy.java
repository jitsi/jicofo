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
package org.jitsi.jicofo.bridge;

import java.util.*;

/**
 * Implements a {@link BridgeSelectionStrategy} which selects attempts to
 * select bridges in the participant's region.
 *
 * Specifically, it implements the following algorithm:
 *
 * (Happy case 1): If there is a non-overloaded bridge in the conference
 * and in the region: use the least loaded of them.
 * (Happy case 2): If there is a non-overloaded bridge in the region,
 * and the conference has no bridges in the region: use the least loaded bridge
 * in the region.
 *
 * (Split case 1): If there is a non-overloaded bridge in the region, the
 * conference has bridges in the region but all are overloaded: Use the least
 * loaded of the bridges in the region.
 *
 * (Overload case 1): If all bridges in the region are overloaded, and the
 * conference has a bridge in the region: use the least loaded conference
 * bridge in the region.
 * (Overload case 2): If all bridges in the region are overloaded, and the
 * conference has no bridges in the region: use the least loaded bridge in
 * the region.
 *
 * (No-region-match case 1): If there are no bridges in the region, and the
 * conference has a non-overloaded bridge: use the least loaded conference bridge.
 * (No-region-match case 2): If there are no bridges in the region and all
 * conference bridges are overloaded: use the least loaded bridge.
 *
 */
class RegionBasedBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    /**
     * Default constructor.
     */
    public RegionBasedBridgeSelectionStrategy()
    {}

    @Override
    public Bridge doSelect(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        if (bridges.isEmpty())
        {
            return null;
        }

        return notLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                () -> notLoadedInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                () -> leastLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                () -> leastLoadedInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                () -> nonLoadedAlreadyInConference(bridges, conferenceBridges, participantRegion).orElseGet(
                () -> leastLoaded(bridges, conferenceBridges, participantRegion).orElse(null))))));
    }
}
