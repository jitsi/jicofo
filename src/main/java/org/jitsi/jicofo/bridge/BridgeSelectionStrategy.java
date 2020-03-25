package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging.*;
import org.json.simple.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

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

    public int totalA1, totalA2, totalB1, totalC1, totalC2, totalD1, totalD2;

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
            if (bridge != null)
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
     * A1. (Happy case 1): There is a NOL bridge in the conference and in the
     * region. Use the least loaded of them.
     *
     * @param bridges
     * @param conferenceBridges
     * @param participantRegion
     *
     * @return
     */
    private Optional<Bridge> findNotLoadedBridgeAlreadyInConferenceInRegion(List<Bridge> bridges,
                                                                            List<Bridge> conferenceBridges,
                                                                            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(not(Bridge::isOverloaded))
            .filter(selectFrom(conferenceBridges))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=a1,bridge=" + result.get() +
                ",conference_bridges=" + conferenceBridges.stream().map(Bridge::toString).collect(Collectors.joining(", ")) +
                "participant_region " + participantRegion);

            totalA1++;
        }

        return result;
    }

    /**
     * A2. (Happy case 2): There is a NOL bridge in the region, and the
     * conference has no bridges in the region. Use the least loaded bridge in
     * the region.
     *
     * @param bridges
     * @param participantRegion
     *
     * @return
     */
    private Optional<Bridge> findNotLoadedBridgeInRegion(List<Bridge> bridges,
                                                         List<Bridge> conferenceBridges,
                                                         String participantRegion)
    {
        // NOTE that A2 is equivalent to B1 but we include it as a separate
        // step for clarity when comparing the code to the document that
        // describes the load balancing scheme.

        Optional<Bridge> result = bridges.stream()
            .filter(not(Bridge::isOverloaded))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=a2,bridge=" + result.get() +
                ",conference_bridges=" + conferenceBridges.stream().map(Bridge::toString).collect(Collectors.joining(", ")) +
                "participant_region " + participantRegion);

            totalA2++;
        }

        return result;
    }

    /**
     * C1. (Overload case 1): All bridges in the region are overloaded, and the
     * conference has a bridge in the region. Use the least loaded conference
     * bridge in the region.
     *
     * @param bridges
     * @param conferenceBridges
     * @param participantRegion
     *
     * @return
     */
    private Optional<Bridge> findLeastLoadedBridgeAlreadyInConferenceInRegion(List<Bridge> bridges,
                                                                              List<Bridge> conferenceBridges,
                                                                              String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(selectFrom(conferenceBridges))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=c1,bridge=" + result.get() +
                ",conference_bridges=" + conferenceBridges.stream().map(Bridge::toString).collect(Collectors.joining(", ")) +
                "participant_region " + participantRegion);
            totalC1++;
        }

        return result;
    }

    /**
     * C2. (Overload case 2): All bridges in the region are overloaded, and the
     * conference has no bridges in the region. Use the least loaded bridge in
     * the region.
     *
     * @param bridges
     * @param participantRegion
     *
     * @return
     */
    private Optional<Bridge> findLeastLoadedBridgeInRegion(List<Bridge> bridges,
                                                           List<Bridge> conferenceBridges,
                                                           String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {

            logger.info("bridge_selected,rule=c2,bridge="  + result.get() +
                ",conference_bridges=" + conferenceBridges.stream().map(Bridge::toString).collect(Collectors.joining(", ")) +
                "participant_region " + participantRegion);

            totalC2++;
        }

        return result;
    }

    /**
     * D1. (No-region-match case 1): There are NO bridges in the region, and the
     * conference has a NOL bridge. Use the least loaded conference bridge.
     *
     * @param bridges
     * @param conferenceBridges
     *
     * @return
     */
    private Optional<Bridge> findLeastLoadedBridgeAlreadyInConference(List<Bridge> bridges,
                                                                      List<Bridge> conferenceBridges,
                                                                      String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(not(Bridge::isOverloaded))
            .filter(selectFrom(conferenceBridges))
            .findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=d1,bridge=" + result.get() +
                ",conference_bridges=" + conferenceBridges.stream().map(Bridge::toString).collect(Collectors.joining(", ")) +
                "participant_region " + participantRegion);
            totalD1++;
        }

        return result;
    }

    /**
     * D2. (No-region-match case 2): There are NO bridges in the region and all
     * conference bridges are OL.
     *
     * @param bridges
     *
     * @return
     */
    private Optional<Bridge> findLeastLoadedBridge(List<Bridge> bridges,
                                                   List<Bridge> conferenceBridges,
                                                   String participantRegion)
    {
        Optional<Bridge> result = bridges.stream().findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=d2,bridge=" + result.get() +
                ",conference_bridges=" + conferenceBridges.stream().map(Bridge::toString).collect(Collectors.joining(", ")) +
                "participant_region " + participantRegion);
            totalD2++;
        }

        return result;
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

        return findNotLoadedBridgeAlreadyInConferenceInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
            () -> findNotLoadedBridgeInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                () -> findLeastLoadedBridgeAlreadyInConferenceInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                    () -> findLeastLoadedBridgeInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                        () -> findLeastLoadedBridgeAlreadyInConference(bridges, conferenceBridges, participantRegion).orElseGet(
                            () -> findLeastLoadedBridge(bridges, conferenceBridges, participantRegion).orElse(null))))));
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

    public JSONObject getStats()
    {
        JSONObject json = new JSONObject();

        json.put("total_a1", totalA1);
        json.put("total_a2", totalA2);
        json.put("total_b1", totalB1);
        json.put("total_c1", totalC1);
        json.put("total_c2", totalC2);
        json.put("total_d1", totalD1);
        json.put("total_d2", totalD2);

        return json;
    }
}
