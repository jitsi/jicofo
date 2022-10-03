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

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.utils.logging2.*;

import java.util.*;

/**
 * Implements a {@link BridgeSelectionStrategy} which attempts to select bridges in the participant's region.
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
public class RegionBasedBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    private final static Logger logger = new LoggerImpl(RegionBasedBridgeSelectionStrategy.class.getName());

    /**
     * Map a region to the set of all region in its group (including itself).
     */
    private final Map<String, Set<String>> regionGroups = new HashMap<>();

    public final String localRegion = JicofoConfig.config.localRegion();

    /**
     * Default constructor.
     */
    public RegionBasedBridgeSelectionStrategy()
    {
        BridgeConfig.config.getRegionGroups().forEach(
                regionGroup -> regionGroup.forEach(region -> regionGroups.put(region, regionGroup)));
    }

    @NotNull
    private Set<String> getRegionGroup(String region)
    {
        Set<String> regionGroup = regionGroups.get(region);
        return regionGroup != null ? regionGroup : Collections.singleton(region);
    }

    @Override
    public Bridge doSelect(
            List<Bridge> bridges,
            Map<Bridge, ConferenceBridgeProperties> conferenceBridges,
            String participantRegion)
    {
        if (bridges.isEmpty())
        {
            return null;
        }

        String r = participantRegion == null ? localRegion : participantRegion;
        if (localRegion != null)
        {
            Set<String> regionGroup = getRegionGroup(r);

            if (conferenceBridges.isEmpty() && !Objects.equals(r, JicofoConfig.config.localRegion()))
            {
                // Selecting an initial bridge for a participant not in the local region. This is most likely because
                // exactly one of the first two participants in the conference is not in the local region, and we're
                // selecting for it first. I.e. there is another participant in the local region which will be
                // subsequently invited.
                if (regionGroup.contains(localRegion))
                {
                    // With the above assumption, there are two participants in the local region group. Therefore,
                    // they will use the same bridge. Prefer to use a bridge in the local region.
                    r = localRegion;
                }
            }

            // If there are no bridges in the participant region or region group, select from the local region instead.
            if (bridges.stream().noneMatch(b -> regionGroup.contains(b.getRegion())))
            {
                r = localRegion;
            }
        }

        final String region = r;
        return notLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, region).orElseGet(
                () -> notLoadedAlreadyInConferenceInRegionGroup(
                        bridges, conferenceBridges, getRegionGroup(region)).orElseGet(
                () -> notLoadedInRegion(bridges, conferenceBridges, region).orElseGet(
                () -> notLoadedInRegionGroup(bridges, conferenceBridges, getRegionGroup(region)).orElseGet(
                () -> leastLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, region).orElseGet(
                () -> leastLoadedAlreadyInConferenceInRegionGroup(
                        bridges, conferenceBridges, getRegionGroup(region)).orElseGet(
                () -> leastLoadedInRegion(bridges, conferenceBridges, region).orElseGet(
                () -> leastLoadedInRegionGroup(bridges, conferenceBridges, getRegionGroup(region)).orElseGet(
                () -> nonLoadedAlreadyInConference(bridges, conferenceBridges, region).orElseGet(
                () -> leastLoaded(bridges, conferenceBridges, region).orElse(null))))))))));
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " with region groups:" + BridgeConfig.config.getRegionGroups();
    }

    @Override
    public Bridge select(
            List<Bridge> bridges,
            Map<Bridge, ConferenceBridgeProperties> conferenceBridges,
            String participantRegion,
            boolean allowMultiBridge)
    {
        if (!allowMultiBridge)
        {
            logger.warn("Octo is disabled, but the selection strategy is RegionBased. Enable octo in jicofo.conf to "
                    + "allow use of multiple bridges in a conference.");
        }

        return super.select(bridges, conferenceBridges, participantRegion, allowMultiBridge);
    }
}
