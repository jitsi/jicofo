package org.jitsi.jicofo.bridge;

import java.util.*;
import java.util.stream.*;

/**
 * Implements a {@link BridgeSelectionStrategy} which
 */
class RegionBasedBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    /**
     * Default constructor.
     */
    RegionBasedBridgeSelectionStrategy()
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
        if (participantRegion == null)
        {
            // We don't know the participant's region. Use the least loaded
            // existing bridge in the conference.
            return findFirst(conferenceBridges, bridges);
        }

        // We know the participant's region.
        List<Bridge> conferenceBridgesInRegion
            = conferenceBridges.stream()
                .filter(
                    bridge -> participantRegion.equals(bridge.getRegion()))
                .collect(Collectors.toList());
        if (!conferenceBridgesInRegion.isEmpty())
        {
            return findFirst(conferenceBridgesInRegion, bridges);
        }

        // The conference has no bridges in the participant region. Try
        // to add a new bridge in that region.
        Bridge bridgeInRegion
            = bridges.stream()
                .filter(bridge -> participantRegion.equals(bridge.getRegion()))
                .findFirst().orElse(null);
        if (bridgeInRegion != null)
        {
            return bridgeInRegion;
        }

        // We couldn't find a bridge in the participant's region. Use the
        // least loaded of the existing conference bridges.
        // TODO: perhaps use a bridge in a nearby region (if we have data
        // about the topology of the regions).
        return findFirst(conferenceBridges, bridges);
    }

    /**
     * Selects the bridge from {@code selectFrom} which occurs first in
     * {@code order}.
     * @param order A list which dictates the order.
     * @param selectFrom A list of bridges to select from. Assumed non-null
     * and non-empty.
     * @return the bridge from {@code selectFrom} which occurs first in
     * {@code order}.
     */
    private Bridge findFirst(
        List<Bridge> selectFrom, List<Bridge> order)
    {
        return order.stream()
            .filter(selectFrom::contains)
            .findFirst()
            .orElse(selectFrom.get(0));
    }
}
