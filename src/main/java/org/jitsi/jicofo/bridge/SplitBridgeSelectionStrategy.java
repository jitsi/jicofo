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

import org.jitsi.utils.logging2.*;

import java.util.*;

/**
 * Implements a {@link BridgeSelectionStrategy} which "splits" a conference to as many bridges as possible (always
 * selects a bridge not in the conference or with the fewest endpoints from that conference). For testing purposes only.
 */
class SplitBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    private final static Logger logger = new LoggerImpl(SplitBridgeSelectionStrategy.class.getName());

    /**
     * Default constructor.
     */
    public SplitBridgeSelectionStrategy()
    {}

    /**
     * {@inheritDoc}
     * </p>
     * Always selects the bridge already used by the conference.
     */
    @Override
    public Bridge doSelect(
        List<Bridge> bridges,
        Map<Bridge, Integer> conferenceBridges,
        String participantRegion)
    {
        // If there's any bridge not yet in this conference, use that; otherwise
        // find the bridge with the fewest participants
        Optional<Bridge> bridgeNotYetInConf = bridges.stream()
            .filter(b -> !conferenceBridges.containsKey(b)).findFirst();
        return bridgeNotYetInConf.orElseGet(() -> conferenceBridges.entrySet().stream()
            .filter(b -> bridges.contains(b.getKey()))
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null));
    }

    @Override
    public Bridge select(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion,
            boolean allowMultiBridge)
    {
        if (!allowMultiBridge)
        {
            logger.warn("Force-enabling octo for SplitBridgeSelectionStrategy. To suppress this warning, enable octo"
                    + " in jicofo.conf.");
        }

        return super.select(bridges, conferenceBridges, participantRegion, true);
    }
}
