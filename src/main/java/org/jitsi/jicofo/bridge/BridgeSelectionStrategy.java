package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging.*;

import java.util.*;
import java.util.function.*;

import static org.glassfish.jersey.internal.guava.Predicates.not;

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
                logger.info("Selected initial bridge " + bridge
                        + " with packetRate=" + bridge.getLastReportedPacketRatePps()
                        + " for participantRegion=" + participantRegion);
            }
            else
            {
                logger.warn("Failed to select initial bridge for participantRegion="
                        + participantRegion);
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

            Bridge bridge = doSelect(
                    bridges, conferenceBridges, participantRegion);
            if (bridge != null)
            {
                logger.info("Selected bridge " + bridge
                        + " with packetRate=" + bridge.getLastReportedPacketRatePps()
                        + " for participantRegion=" + participantRegion);
            }
            else
            {
                logger.warn("Failed to select bridge for participantRegion=" + participantRegion);
            }
            return bridge;
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
    public Bridge selectLeastLoadedNearby(List<Bridge> bridges,
                                          List<Bridge> conferenceBridges,
                                          String participantRegion)
    {
        if (bridges.isEmpty())
        {
            return null;
        }

        // NOTE that A2 is equivalent to B1 but we include it as a separate
        // step for clarity when comparing the code to the document that
        // describes the load balancing scheme.

        return bridges.stream()
            // A1. (Happy case 1): There is a NOL bridge in the conference and
            // in the region. Use the least loaded of them.
            .filter(not(Bridge::isOverloaded))
            .filter(selectFrom(conferenceBridges))
            .filter(inRegion(participantRegion))
            .findFirst()
            .orElse(bridges.stream()
                // A2. (Happy case 2): There is a NOL bridge in the region, and the
                // conference has no bridges in the region. Use the least loaded
                // bridge in the region.
                .filter(not(Bridge::isOverloaded))
                .filter(inRegion(participantRegion))
                .findFirst()
                .orElse(bridges.stream()
                    // B1. (Split case 1): There is a NOL bridge in the region, the
                    // conference has bridges in the region but all are OL. Use the
                    // least loaded of the bridges in the region.
                    .filter(not(Bridge::isOverloaded))
                    .filter(inRegion(participantRegion))
                    .findFirst()
                    .orElse(bridges.stream()
                        // C1. (Overload case 1): All bridges in the region are
                        // overloaded, and the conference has a bridge in the
                        // region. Use the least loaded conference bridge in the
                        // region.
                        .filter(selectFrom(conferenceBridges))
                        .filter(inRegion(participantRegion))
                        .findFirst()
                        .orElse(bridges.stream()
                            // C2. (Overload case 2): All bridges in the region are
                            // overloaded, and the conference has no bridges in the
                            // region. Use the least loaded bridge in the region.
                            .filter(inRegion(participantRegion))
                            .findFirst()
                            .orElse(bridges.stream()
                                // D1. (No-region-match case 1): There are NO
                                // bridges in the region, and the conference has
                                // a NOL bridge. Use the least loaded conference
                                // bridge.
                                .filter(not(Bridge::isOverloaded))
                                .filter(selectFrom(conferenceBridges))
                                .findFirst()
                                .orElse(bridges.stream()
                                    // D2. (No-region-match case 2): There are NO
                                    // bridges in the region and all conference
                                    // bridges are OL.
                                    .findFirst()
                                    .orElse(null)))))));
    }

    /**
     * Selects a bridge to be used for a new participant in a conference.
     *
     * @param bridges the list of bridges to select from.
     * @param conferenceBridges the list of bridges currently used by the
     * conference.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     * @return the selected bridge, or {@code null} if no bridge is
     * available.
     */
    abstract Bridge doSelect(
        List<Bridge> bridges,
        List<Bridge> conferenceBridges,
        String participantRegion);

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
