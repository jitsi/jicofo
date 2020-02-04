package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging.*;

import java.util.*;
import java.util.function.*;

/**
 * Represents an algorithm for bridge selection.
 */
abstract class BridgeSelectionStrategy
{
    /**
     * The logger.
     */
    private final static Logger logger
            = Logger.getLogger(BridgeSelectionStrategy.class);

    /**
     * Helper field to reduce allocations.
     */
    private static final int[] LOW_MEDIUM_HIGH
        = new int[] { StressLevel.LOW, StressLevel.MEDIUM, StressLevel.HIGH };

    /**
     * The local region of the jicofo instance.
     */
    private String localRegion = null;

    /**
     * Selects a bridge to be used for a new participant in a conference.
     *
     * @param bridges the list of bridges to select from.
     * @param conferenceBridges the bridges already in use by the conference
     * for which for which a bridge is to be selected.
     * @param participantRegion the region of the participant for which
     * a bridge is to be selected.
     * @return the selected bridge, or {@code null} if no bridge is
     * available.
     */
    public Bridge select(
            List<Bridge> bridges,
            List<Bridge> conferenceBridges,
            String participantRegion,
            boolean allowMultiBridge)
    {
        if (conferenceBridges.isEmpty())
        {
            Bridge bridge
                = doSelect(bridges, conferenceBridges, participantRegion);
            if (logger.isDebugEnabled())
            {
                logger.debug("Selected initial bridge " + bridge);
            }
            return bridge;
        }
        else
        {
            if (!allowMultiBridge
                || conferenceBridges.get(0).getRelayId() == null)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "Existing bridge does not have a relay, will not " +
                            "consider other bridges.");
                }

                return conferenceBridges.get(0);
            }

            return doSelect(
                    bridges, conferenceBridges, participantRegion);
        }
    }

    /**
     * Selects a bridge to be used for a new participant in a conference,
     * assuming that no other bridge is used by the conference (i.e. this
     * is the initial selection of a bridge for the conference).
     *
     * @param bridges the list of bridges to select from (ordered by the total
     * bitrate that they're handling, which is used an indirect load indicator).
     * @param conferenceBridges the bridges that are in the conference.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     * @return the selected bridge, or {@code null} if no bridge is
     * available.
     */
    public Bridge doSelect(List<Bridge> bridges,
                                 List<Bridge> conferenceBridges,
                                 String participantRegion)
    {
        Bridge bridge = null;
        for (int stressLevel : LOW_MEDIUM_HIGH)
        {
            // Try the first operational bridge in the participant region and
            // that is already in the conference.
            bridge = bridges.stream()
                .filter(notStressedOver(stressLevel))
                .filter(selectFrom(conferenceBridges))
                .filter(inRegion(participantRegion))
                .findFirst()
                // Otherwise, try to add a new bridge in the participant region.
                .orElse(bridges.stream()
                    .filter(notStressedOver(stressLevel))
                    .filter(inRegion(participantRegion))
                    .findFirst()
                    // Otherwise, try to add a new bridge that is already in the
                    // conference.
                    .orElse(bridges.stream()
                        .filter(notStressedOver(stressLevel))
                        .filter(selectFrom(conferenceBridges))
                        .findFirst()
                        // Otherwise, try to add a new bridge in the local
                        // region.
                        .orElse(bridges.stream()
                            .filter(notStressedOver(stressLevel))
                            .filter(inRegion(localRegion))
                            .findFirst()
                            // Otherwise, try to add the least loaded bridge
                            // that we know of.
                            // TODO: perhaps use a bridge in a nearby region (if
                            // we have data about the topology of the regions).
                            .orElse(bridges.stream()
                                .filter(notStressedOver(stressLevel))
                                .findFirst()
                                .orElse(null)))));

            if (bridge != null)
            {
                break;
            }
        }

        return bridge;
    }

    private static Predicate<Bridge> notStressedOver(int loadLevel)
    {
        return b -> b.getStressLevel() <= loadLevel;
    }

    private static Predicate<Bridge> selectFrom(List<Bridge> conferenceBridges)
    {
        return b -> conferenceBridges != null && conferenceBridges.contains(b);
    }

    private static Predicate<Bridge> inRegion(String region)
    {
        return b -> region != null && region.equalsIgnoreCase(b.getRegion());
    }

    String getLocalRegion()
    {
        return localRegion;
    }

    void setLocalRegion(String localRegion)
    {
        this.localRegion = localRegion;
    }
}
