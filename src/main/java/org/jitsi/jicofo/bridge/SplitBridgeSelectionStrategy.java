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
 * Implements a {@link BridgeSelectionStrategy} which tries to split each
 * conference to different bridges (without regard for the "region"). For
 * testing purposes only.
 */
class SplitBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    /**
     * Default constructor.
     */
    SplitBridgeSelectionStrategy()
    {}

    /**
     * {@inheritDoc}
     * </p>
     * Always selects the bridge already used by the conference.
     */
    @Override
    public Bridge doSelect(
        List<Bridge> bridges,
        List<Bridge> conferenceBridges,
        String participantRegion)
    {
        for (Bridge bridge : bridges)
        {
            // If there's an available bridge, which isn't yet used in the
            // conference, use it.
            if (!conferenceBridges.contains(bridge))
            {
                return bridge;
            }
        }

        // Otherwise, select one of the existing bridges in the conference
        // at random.
        if (!bridges.isEmpty())
        {
            return
                bridges.get(
                    Math.abs(new Random().nextInt()) % bridges.size());
        }

        return null;
    }
}
