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
 * Implements a {@link BridgeSelectionStrategy} which selects bridges in a single
 * region, but uses multiple bridges for load balancing.
 */
class IntraRegionBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    /**
     * Default constructor.
     */
    public IntraRegionBridgeSelectionStrategy()
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

        if (conferenceBridges.isEmpty())
        {
            // Try to match the participant region for the initial selection
            return notLoadedInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                    () -> leastLoaded(bridges, conferenceBridges, participantRegion).orElse(null));
        }

        String conferenceRegion = conferenceBridges.keySet().stream().findFirst().get().getRegion();

        return notLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, conferenceRegion).orElseGet(
                () -> notLoadedInRegion(bridges, conferenceBridges, conferenceRegion).orElseGet(
                    () -> leastLoadedAlreadyInConferenceInRegion(
                        bridges,
                        conferenceBridges,
                        conferenceRegion).orElse(null)));
    }
}
