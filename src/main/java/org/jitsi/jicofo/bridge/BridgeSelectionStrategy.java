package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

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
     * Find the "max stress level" above which a bridge won't be considered for
     * participant allocation.
     *
     * It works by splitting the available bridges into groups of bridges
     * with similar stress level and allocate participants to a bridge that
     * is in the least stressed group.
     *
     * Suppose for example that we have 6 bridges, each loaded from 1 to 6 that
     * we want to split into 3 groups (imagine the groups names are "low-stressed",
     * "medium-stressed" and "high-stressed" bridges):
     *
     * min = 1, max = 6, step = ceil(5 / 3)  = cel(1.66) = 2
     * group 1: stress <= 3
     * group 2: stress <= 5
     * group 3: stress <= 7
     *
     * This method returns 3 and the bridge selector will consider bridges that
     * are stressed <= 3.
     *
     * @param bridges
     * @return
     */
    private double findStressLimit(Collection<Bridge> bridges)
    {
        List<Double> bridgesStress = bridges
            .stream()
            .map(Bridge::getStress)
            .sorted()
            .collect(Collectors.toList());

        // We consider three groups, namely: "low-stressed", "medium-stressed"
        // and "high-stressed" bridges.
        int numberOfGroups = 3;

        double min = bridgesStress.get(0);
        double max = bridgesStress.get(bridgesStress.size() - 1);
        double step = Math.ceil((max - min) / numberOfGroups);
        return min + step;
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
        if (bridges.isEmpty())
        {
            return null;
        }

        double maxStress = findStressLimit(bridges);

        // Try the first operational bridge in the participant region and
        // that is already in the conference.
        return bridges.stream()
            .filter(stressIsLessThanOrEqual(maxStress))
            .filter(selectFrom(conferenceBridges))
            .filter(inRegion(participantRegion))
            .findFirst()
            // Otherwise, try to add a new bridge in the participant region.
            .orElse(bridges.stream()
                .filter(stressIsLessThanOrEqual(maxStress))
                .filter(inRegion(participantRegion))
                .findFirst()
                // Otherwise, try to add a new bridge that is already in the
                // conference.
                .orElse(bridges.stream()
                    .filter(stressIsLessThanOrEqual(maxStress))
                    .filter(selectFrom(conferenceBridges))
                    .findFirst()
                    // Otherwise, try to add a new bridge in the local
                    // region.
                    .orElse(bridges.stream()
                        .filter(stressIsLessThanOrEqual(maxStress))
                        .filter(inRegion(localRegion))
                        .findFirst()
                        // Otherwise, try to add the least loaded bridge
                        // that we know of.
                        // TODO: perhaps use a bridge in a nearby region (if
                        // we have data about the topology of the regions).
                        .orElse(bridges.stream()
                            .filter(stressIsLessThanOrEqual(maxStress))
                            .findFirst()
                            .orElse(null)))));
    }

    private static Predicate<Bridge> stressIsLessThanOrEqual(double maxStress)
    {
        // new video streams as a result of a new participant joining.
        return b -> b.getStress() <= maxStress;
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
