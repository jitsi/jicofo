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
