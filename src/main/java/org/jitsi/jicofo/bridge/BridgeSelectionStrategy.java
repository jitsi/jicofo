package org.jitsi.jicofo.bridge;

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.json.simple.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import static org.glassfish.jersey.internal.guava.Predicates.not;
import static org.jitsi.jicofo.bridge.BridgeConfig.config;

/**
 * Represents an algorithm for bridge selection.
 */
public abstract class BridgeSelectionStrategy
{
    /**
     * The logger.
     */
    private final static Logger logger = new LoggerImpl(BridgeSelectionStrategy.class.getName());

    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region that was not
     * overloaded.
     */
    private int totalNotLoadedAlreadyInConferenceInRegion;
    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region group that was not
     * overloaded.
     */
    private int totalNotLoadedAlreadyInConferenceInRegionGroup;
    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region that was not overloaded.
     */
    private int totalNotLoadedInRegion;
    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region group that was not overloaded.
     */
    private int totalNotLoadedInRegionGroup;
    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region.
     */
    private int totalLeastLoadedAlreadyInConferenceInRegion;
    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region group.
     */
    private int totalLeastLoadedAlreadyInConferenceInRegionGroup;
    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region.
     */
    private int totalLeastLoadedInRegion;
    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region group.
     */
    private int totalLeastLoadedInRegionGroup;
    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference.
     */
    private int totalLeastLoadedAlreadyInConference;
    /**
     * Total number of times selection succeeded because there was any bridge
     * available.
     */
    private int totalLeastLoaded;
    /**
     * Total number of times a new bridge was added to a conference to satisfy
     * the desired region.
     */
    private int totalSplitDueToRegion;
    /**
     * Total number of times a new bridge was added to a conference due to
     * load.
     */
    private int totalSplitDueToLoad;

    /**
     * Maximum participants per bridge in one conference, or {@code -1} for no maximum.
     */
    private final int maxParticipantsPerBridge = config.maxBridgeParticipants();

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
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion,
            boolean allowMultiBridge)
    {
        if (conferenceBridges.isEmpty())
        {
            Bridge bridge = doSelect(bridges, conferenceBridges, participantRegion);
            if (bridge != null)
            {
                logger.info("Selected initial bridge " + bridge
                        + " with reported stress=" + bridge.getLastReportedStressLevel()
                        + " for participantRegion=" + participantRegion
                        + " using strategy " + this.getClass().getSimpleName());
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
            Bridge existingBridge = conferenceBridges.keySet().stream().findFirst().get();
            if (!allowMultiBridge
                || existingBridge.getRelayId() == null)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "Existing bridge does not have a relay, will not " +
                            "consider other bridges.");
                }

                return existingBridge;
            }

            Bridge bridge = doSelect(bridges, conferenceBridges, participantRegion);
            if (bridge != null)
            {
                logger.info("Selected bridge " + bridge
                        + " with stress=" + bridge.getLastReportedStressLevel()
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
    Optional<Bridge> notLoadedAlreadyInConferenceInRegion(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(not(b -> isOverloaded(b, conferenceBridges)))
            .filter(selectFrom(conferenceBridges.keySet()))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            totalNotLoadedAlreadyInConferenceInRegion++;
            logSelection(result.get(), conferenceBridges, participantRegion);
        }

        return result;
    }

    Optional<Bridge> notLoadedAlreadyInConferenceInRegionGroup(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            Set<String> participantRegionGroup)
    {
        Optional<Bridge> result = bridges.stream()
                .filter(not(b -> isOverloaded(b, conferenceBridges)))
                .filter(selectFrom(conferenceBridges.keySet()))
                .filter(inRegionGroup(participantRegionGroup))
                .findFirst();

        if (result.isPresent())
        {
            totalNotLoadedAlreadyInConferenceInRegionGroup++;
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup);
        }

        return result;
    }

    private void logSelection(
            Bridge bridge,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        logSelection(bridge, conferenceBridges, participantRegion, null);
    }

    private void logSelection(
            Bridge bridge,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion,
            Set<String> participantRegionGroup)
    {
        String method = Thread.currentThread().getStackTrace()[2].getMethodName();
        logger.debug("Bridge selected: method=" + method
            + ", participantRegion=" + participantRegion
            + ", participantRegionGroup=" + participantRegionGroup
            + ", bridge=" + bridge
            + ", conference_bridges="
            + conferenceBridges.keySet().stream().map(Bridge::toString)
                .collect(Collectors.joining(", ")));
    }

    /**
     * Finds the least loaded bridge in the participant's region that is not
     * overloaded.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param participantRegion the participant region.
     *
     * @return an optional that contains a bridge that is not loaded and that is
     * in the participant region.
     */
    Optional<Bridge> notLoadedInRegion(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(not(b -> isOverloaded(b, conferenceBridges)))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            totalNotLoadedInRegion++;
            updateSplitStats(conferenceBridges, result.get(), participantRegion);
            logSelection(result.get(), conferenceBridges, participantRegion);
        }

        return result;
    }

    Optional<Bridge> notLoadedInRegionGroup(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            Set<String> participantRegionGroup)
    {
        Optional<Bridge> result = bridges.stream()
                .filter(not(b -> isOverloaded(b, conferenceBridges)))
                .filter(inRegionGroup(participantRegionGroup))
                .findFirst();

        if (result.isPresent())
        {
            totalNotLoadedInRegionGroup++;
            updateSplitStats(conferenceBridges, result.get(), null, participantRegionGroup);
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup);
        }

        return result;
    }

    private void updateSplitStats(
            Map<Bridge, Integer> conferenceBridges,
            Bridge selectedBridge,
            String participantRegion)
    {
        updateSplitStats(conferenceBridges, selectedBridge, participantRegion, null);
    }

    private void updateSplitStats(
            Map<Bridge, Integer> conferenceBridges,
            Bridge selectedBridge,
            String participantRegion,
            Set<String> participantRegionGroup)
    {
        if (!conferenceBridges.isEmpty() && !conferenceBridges.containsKey(selectedBridge))
        {
            // We added a new bridge to the conference. Was it because the
            // conference had no bridges in that region, or because it had
            // some, but they were over loaded?
            if (
                (participantRegion != null && conferenceBridges.keySet().stream().anyMatch(inRegion(participantRegion))
                        || (participantRegionGroup != null &&
                                conferenceBridges.keySet().stream().anyMatch(inRegionGroup(participantRegionGroup)))))
            {
                totalSplitDueToLoad++;
            }
            else
            {
                totalSplitDueToRegion++;
            }
        }
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
    Optional<Bridge> leastLoadedAlreadyInConferenceInRegion(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(selectFrom(conferenceBridges.keySet()))
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            totalLeastLoadedAlreadyInConferenceInRegion++;
            logSelection(result.get(), conferenceBridges, participantRegion);
        }

        return result;
    }

    Optional<Bridge> leastLoadedAlreadyInConferenceInRegionGroup(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            Set<String> participantRegionGroup)
    {
        Optional<Bridge> result = bridges.stream()
                .filter(selectFrom(conferenceBridges.keySet()))
                .filter(inRegionGroup(participantRegionGroup))
                .findFirst();

        if (result.isPresent())
        {
            totalLeastLoadedAlreadyInConferenceInRegionGroup++;
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup);
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
    Optional<Bridge> leastLoadedInRegion(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(inRegion(participantRegion))
            .findFirst();

        if (result.isPresent())
        {
            totalLeastLoadedInRegion++;
            updateSplitStats(conferenceBridges, result.get(), participantRegion);
            logSelection(result.get(), conferenceBridges, participantRegion);
        }

        return result;
    }

    Optional<Bridge> leastLoadedInRegionGroup(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            Set<String> participantRegionGroup)
    {
        Optional<Bridge> result = bridges.stream()
                .filter(inRegionGroup(participantRegionGroup))
                .findFirst();

        if (result.isPresent())
        {
            totalLeastLoadedInRegionGroup++;
            updateSplitStats(conferenceBridges, result.get(), null, participantRegionGroup);
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup);
        }

        return result;
    }

    /**
     * Finds the least loaded non overloaded bridge that is already handling the conference.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param conferenceBridges the set of bridges that are already used in the
     * conference.
     *
     * @return an optional that contains the least loaded bridge that is already
     * in the  conference, if it exists.
     */
    Optional<Bridge> nonLoadedAlreadyInConference(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream()
            .filter(not(b -> isOverloaded(b, conferenceBridges)))
            .filter(selectFrom(conferenceBridges.keySet()))
            .findFirst();

        if (result.isPresent())
        {
            totalLeastLoadedAlreadyInConference++;
            logSelection(result.get(), conferenceBridges, participantRegion);
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
    Optional<Bridge> leastLoaded(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        Optional<Bridge> result = bridges.stream().findFirst();

        if (result.isPresent())
        {
            totalLeastLoaded++;
            updateSplitStats(conferenceBridges, result.get(), participantRegion);
            logSelection(result.get(), conferenceBridges, participantRegion);
        }

        return result;
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
        Map<Bridge, Integer> conferenceBridges,
        String participantRegion);

    private static Predicate<Bridge> selectFrom(Collection<Bridge> conferenceBridges)
    {
        return b -> conferenceBridges != null && conferenceBridges.contains(b);
    }

    private static Predicate<Bridge> inRegion(String region)
    {
        return b -> region != null && region.equalsIgnoreCase(b.getRegion());
    }

    private static Predicate<Bridge> inRegionGroup(@NotNull Set<String> regionGroup)
    {
        return b -> regionGroup.contains(b.getRegion());
    }

    /**
     * Checks whether a {@link Bridge} should be considered overloaded for a
     * particular conference.
     * @param bridge the bridge
     * @param conferenceBridges the bridges in the conference
     * @return {@code true} if the bridge should be considered overloaded.
     */
    private boolean isOverloaded(
            Bridge bridge,
            Map<Bridge, Integer> conferenceBridges)
    {
        return bridge.isOverloaded()
            || (maxParticipantsPerBridge > 0 && conferenceBridges.containsKey(bridge)
                && conferenceBridges.get(bridge) >= maxParticipantsPerBridge);
    }


    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        JSONObject json = new JSONObject();

        json.put("total_not_loaded_in_region_in_conference", totalNotLoadedAlreadyInConferenceInRegion);
        json.put("total_not_loaded_in_region_group_in_conference", totalNotLoadedAlreadyInConferenceInRegionGroup);
        json.put("total_not_loaded_in_region", totalNotLoadedInRegion);
        json.put("total_not_loaded_in_region_group", totalNotLoadedInRegionGroup);
        json.put("total_least_loaded_in_region_in_conference", totalLeastLoadedAlreadyInConferenceInRegion);
        json.put("total_least_loaded_in_region_group_in_conference", totalLeastLoadedAlreadyInConferenceInRegionGroup);
        json.put("total_least_loaded_in_region", totalLeastLoadedInRegion);
        json.put("total_least_loaded_in_region_group", totalLeastLoadedInRegionGroup);
        json.put("total_least_loaded_in_conference", totalLeastLoadedAlreadyInConference);
        json.put("total_least_loaded", totalLeastLoaded);
        json.put("total_split_due_to_region", totalSplitDueToRegion);
        json.put("total_split_due_to_load", totalSplitDueToLoad);

        return json;
    }
}
