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

    public int totalNotLoadedAlreadyInConferenceInRegion,
        totalNotLoadedInRegion,
        totalLeastLoadedAlreadyInConferenceInRegion,
        totalLeastLoadedInRegion,
        totalLeastLoadedAlreadyInConference,
        totalLeastLoaded;

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
     * Finds the least loaded bridge in the participant's region that is not
     * overloaded and that is already handling the conference.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param conferenceBridges the set of bridges that are already used in the conference.
     * @param participantRegion the participant region.
     *
     * @return an optional that contains a bridge that is not loaded and that
     * is already in the conference and that is in the participant region, if it
     * exists.
     */
    private Optional<Bridge> notLoadedAlreadyInConferenceInRegion(List<Bridge> bridges,
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

            totalNotLoadedAlreadyInConferenceInRegion++;
        }

        return result;
    }

    /**
     * Finds the least loaded bridge in the participant's region that is not
     * overloaded.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param participantRegion the set of bridges that are already used in the conference.
     *
     * @return an optional that contains a bridge that is not loaded and that is
     * in the participant region.
     */
    private Optional<Bridge> notLoadedInRegion(List<Bridge> bridges,
                                               String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(not(Bridge::isOverloaded))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=a2,bridge=" + result.get() +
                "participant_region " + participantRegion);

            totalNotLoadedInRegion++;
        }

        return result;
    }

    /**
     * Finds the least loaded conference bridge in the participant's region that
     * is already handling the conference.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param conferenceBridges the set of bridges that are already used in the conference.
     * @param participantRegion the participant region.
     *
     * @return an optional that contains the least loaded bridge that is already
     * in the conference and that is in the participant region if it exists.
     */
    private Optional<Bridge> leastLoadedAlreadyInConferenceInRegion(List<Bridge> bridges,
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
            totalLeastLoadedAlreadyInConferenceInRegion++;
        }

        return result;
    }

    /**
     * Finds the least loaded bridge in the participant's region.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param participantRegion the participant region.
     *
     * @return an optional that contains the least loaded bridge in the
     * participant's region if it exists.
     */
    private Optional<Bridge> leastLoadedInRegion(List<Bridge> bridges,
                                                 String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {

            logger.info("bridge_selected,rule=c2,bridge="  + result.get() +
                "participant_region " + participantRegion);

            totalLeastLoadedInRegion++;
        }

        return result;
    }

    /**
     * Finds the least loaded bridge that is already handling the conference.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param conferenceBridges the set of bridges that are already used in the
     * conference.
     *
     * @return an optional that contains the least loaded bridge that is already
     * in the  conference, if it exists.
     */
    private Optional<Bridge> leastLoadedAlreadyInConference(List<Bridge> bridges,
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
            totalLeastLoadedAlreadyInConference++;
        }

        return result;
    }

    /**
     * Finds the least loaded bridge.
     *
     * @param bridges the list of operational bridges, ordered by load.
     *
     * @return an optional that contains the least loaded bridge if it exists.
     */
    private Optional<Bridge> leastLoaded(List<Bridge> bridges)
    {
        Optional<Bridge> result = bridges.stream().findFirst();

        if (result.isPresent())
        {
            logger.info("bridge_selected,rule=d2,bridge=" + result.get());
            totalLeastLoaded++;
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

        return notLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
            () -> notLoadedInRegion(bridges, participantRegion).orElseGet(
                () -> leastLoadedAlreadyInConferenceInRegion(bridges, conferenceBridges, participantRegion).orElseGet(
                    () -> leastLoadedInRegion(bridges, participantRegion).orElseGet(
                        () -> leastLoadedAlreadyInConference(bridges, conferenceBridges, participantRegion).orElseGet(
                            () -> leastLoaded(bridges).orElse(null))))));
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

        json.put("total_not_loaded_in_region_in_conference", totalNotLoadedAlreadyInConferenceInRegion);
        json.put("total_not_loaded_in_region", totalNotLoadedInRegion);
        json.put("total_least_loaded_in_region_in_conference", totalLeastLoadedAlreadyInConferenceInRegion);
        json.put("total_least_loaded_in_region", totalLeastLoadedInRegion);
        json.put("total_least_loaded_in_conference", totalLeastLoadedAlreadyInConference);
        json.put("total_least_loaded", totalLeastLoaded);

        return json;
    }
}
