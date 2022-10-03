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
package org.jitsi.jicofo.bridge

import org.glassfish.jersey.internal.guava.Predicates.not
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.json.simple.JSONObject
import java.util.*
import java.util.function.Predicate

/**
 * Represents an algorithm for bridge selection.
 */
abstract class BridgeSelectionStrategy {
    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region that was not
     * overloaded.
     */
    private var totalNotLoadedAlreadyInConferenceInRegion = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region group that was not
     * overloaded.
     */
    private var totalNotLoadedAlreadyInConferenceInRegionGroup = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region that was not overloaded.
     */
    private var totalNotLoadedInRegion = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region group that was not overloaded.
     */
    private var totalNotLoadedInRegionGroup = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region.
     */
    private var totalLeastLoadedAlreadyInConferenceInRegion = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference, in the desired region group.
     */
    private var totalLeastLoadedAlreadyInConferenceInRegionGroup = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region.
     */
    private var totalLeastLoadedInRegion = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * in the desired region group.
     */
    private var totalLeastLoadedInRegionGroup = 0

    /**
     * Total number of times selection succeeded because there was a bridge
     * already in the conference.
     */
    private var totalLeastLoadedAlreadyInConference = 0

    /**
     * Total number of times selection succeeded because there was any bridge
     * available.
     */
    private var totalLeastLoaded = 0

    /**
     * Total number of times a new bridge was added to a conference to satisfy
     * the desired region.
     */
    private var totalSplitDueToRegion = 0

    /**
     * Total number of times a new bridge was added to a conference due to
     * load.
     */
    private var totalSplitDueToLoad = 0

    /**
     * Maximum participants per bridge in one conference, or `-1` for no maximum.
     */
    private val maxParticipantsPerBridge =
        BridgeConfig.config.maxBridgeParticipants()

    /**
     * Selects a bridge to be used for a new participant in a conference.
     *
     * @param bridges the list of bridges to select from.
     * @param conferenceBridges the bridges already in use by the conference
     * for which for which a bridge is to be selected.
     * @param participantRegion the region of the participant for which
     * a bridge is to be selected.
     * @return the selected bridge, or `null` if no bridge is
     * available.
     */
    open fun select(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?,
        allowMultiBridge: Boolean
    ): Bridge? {
        return if (conferenceBridges.isEmpty()) {
            val bridge = doSelect(bridges, conferenceBridges, participantRegion)
            if (bridge != null) {
                logger.info(
                    "Selected initial bridge $bridge with reported stress=${bridge.lastReportedStressLevel} " +
                        "for participantRegion=$participantRegion using strategy ${this.javaClass.simpleName}"
                )
            } else {
                logger.warn(
                    "Failed to select initial bridge for participantRegion=$participantRegion"
                )
            }
            bridge
        } else {
            val existingBridge = conferenceBridges.keys.stream().findFirst().get()
            if (!allowMultiBridge || existingBridge.relayId == null) {
                logger.info("Existing bridge does not have a relay, will not consider other bridges.")
                return existingBridge
            }
            val bridge = doSelect(bridges, conferenceBridges, participantRegion)
            if (bridge != null) {
                logger.info(
                    "Selected bridge $bridge with stress=${bridge.lastReportedStressLevel} " +
                        "for participantRegion=$participantRegion"
                )
            } else {
                logger.warn("Failed to select bridge for participantRegion=$participantRegion")
            }
            bridge
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
    fun notLoadedAlreadyInConferenceInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(not { b -> isOverloaded(b, conferenceBridges) })
            .filter(selectFrom(conferenceBridges.keys))
            .filter(inRegion(participantRegion))
            .findFirst()
        if (result.isPresent) {
            totalNotLoadedAlreadyInConferenceInRegion++
            logSelection(result.get(), conferenceBridges, participantRegion)
        }
        return result
    }

    fun notLoadedAlreadyInConferenceInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegionGroup: Set<String>
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(not { b -> isOverloaded(b, conferenceBridges) })
            .filter(selectFrom(conferenceBridges.keys))
            .filter(inRegionGroup(participantRegionGroup))
            .findFirst()
        if (result.isPresent) {
            totalNotLoadedAlreadyInConferenceInRegionGroup++
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup)
        }
        return result
    }

    private fun logSelection(
        bridge: Bridge,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?,
        participantRegionGroup: Set<String>? = null
    ) {
        val method = Thread.currentThread().stackTrace[2].methodName
        logger.debug {
            "Bridge selected: method=$method, participantRegion=$participantRegion, " +
                "participantRegionGroup=$participantRegionGroup, bridge=$bridge, " +
                "conference_bridges=${conferenceBridges.keys.joinToString()}"
        }
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
    fun notLoadedInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(not { b: Bridge -> isOverloaded(b, conferenceBridges) })
            .filter(inRegion(participantRegion))
            .findFirst()
        if (result.isPresent) {
            totalNotLoadedInRegion++
            updateSplitStats(conferenceBridges, result.get(), participantRegion)
            logSelection(result.get(), conferenceBridges, participantRegion)
        }
        return result
    }

    fun notLoadedInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegionGroup: Set<String>
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(not { b: Bridge -> isOverloaded(b, conferenceBridges) })
            .filter(inRegionGroup(participantRegionGroup))
            .findFirst()
        if (result.isPresent) {
            totalNotLoadedInRegionGroup++
            updateSplitStats(conferenceBridges, result.get(), null, participantRegionGroup)
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup)
        }
        return result
    }

    private fun updateSplitStats(
        conferenceBridges: Map<Bridge, Int>,
        selectedBridge: Bridge,
        participantRegion: String?,
        participantRegionGroup: Set<String>? = null
    ) {
        if (conferenceBridges.isNotEmpty() && !conferenceBridges.containsKey(selectedBridge)) {
            // We added a new bridge to the conference. Was it because the
            // conference had no bridges in that region, or because it had
            // some, but they were over loaded?
            if (participantRegion != null && conferenceBridges.keys.stream().anyMatch(inRegion(participantRegion)) || (
                participantRegionGroup != null &&
                    conferenceBridges.keys.stream().anyMatch(inRegionGroup(participantRegionGroup))
                )
            ) {
                totalSplitDueToLoad++
            } else {
                totalSplitDueToRegion++
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
    fun leastLoadedAlreadyInConferenceInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(selectFrom(conferenceBridges.keys))
            .filter(inRegion(participantRegion))
            .findFirst()
        if (result.isPresent) {
            totalLeastLoadedAlreadyInConferenceInRegion++
            logSelection(result.get(), conferenceBridges, participantRegion)
        }
        return result
    }

    fun leastLoadedAlreadyInConferenceInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegionGroup: Set<String>
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(selectFrom(conferenceBridges.keys))
            .filter(inRegionGroup(participantRegionGroup))
            .findFirst()
        if (result.isPresent) {
            totalLeastLoadedAlreadyInConferenceInRegionGroup++
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup)
        }
        return result
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
    fun leastLoadedInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(inRegion(participantRegion))
            .findFirst()
        if (result.isPresent) {
            totalLeastLoadedInRegion++
            updateSplitStats(conferenceBridges, result.get(), participantRegion)
            logSelection(result.get(), conferenceBridges, participantRegion)
        }
        return result
    }

    fun leastLoadedInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegionGroup: Set<String>
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(inRegionGroup(participantRegionGroup))
            .findFirst()
        if (result.isPresent) {
            totalLeastLoadedInRegionGroup++
            updateSplitStats(conferenceBridges, result.get(), null, participantRegionGroup)
            logSelection(result.get(), conferenceBridges, null, participantRegionGroup)
        }
        return result
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
    fun nonLoadedAlreadyInConference(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Optional<Bridge> {
        val result = bridges.stream()
            .filter(not { b -> isOverloaded(b, conferenceBridges) })
            .filter(selectFrom(conferenceBridges.keys))
            .findFirst()
        if (result.isPresent) {
            totalLeastLoadedAlreadyInConference++
            logSelection(result.get(), conferenceBridges, participantRegion)
        }
        return result
    }

    /**
     * Finds the least loaded bridge.
     *
     * @param bridges the list of operational bridges, ordered by load.
     *
     * @return an optional that contains the least loaded bridge if it exists.
     */
    fun leastLoaded(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Optional<Bridge?> {
        val result = bridges.stream().findFirst()
        if (result.isPresent) {
            totalLeastLoaded++
            updateSplitStats(conferenceBridges, result.get(), participantRegion)
            logSelection(result.get(), conferenceBridges, participantRegion)
        }
        return result
    }

    /**
     * Selects a bridge to be used for a new participant in a conference.
     *
     * @param bridges the list of bridges to select from.
     * @param conferenceBridges the list of bridges currently used by the
     * conference.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     * @return the selected bridge, or `null` if no bridge is
     * available.
     */
    abstract fun doSelect(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, Int>,
        participantRegion: String?
    ): Bridge?

    /**
     * Checks whether a [Bridge] should be considered overloaded for a
     * particular conference.
     * @param bridge the bridge
     * @param conferenceBridges the bridges in the conference
     * @return `true` if the bridge should be considered overloaded.
     */
    private fun isOverloaded(
        bridge: Bridge,
        conferenceBridges: Map<Bridge, Int>
    ): Boolean {
        return bridge.isOverloaded || (
            maxParticipantsPerBridge > 0 &&
                conferenceBridges.containsKey(bridge) &&
                conferenceBridges[bridge]!! >= maxParticipantsPerBridge
            )
    }

    val stats: JSONObject
        get() {
            val json = JSONObject()
            json["total_not_loaded_in_region_in_conference"] = totalNotLoadedAlreadyInConferenceInRegion
            json["total_not_loaded_in_region_group_in_conference"] = totalNotLoadedAlreadyInConferenceInRegionGroup
            json["total_not_loaded_in_region"] = totalNotLoadedInRegion
            json["total_not_loaded_in_region_group"] = totalNotLoadedInRegionGroup
            json["total_least_loaded_in_region_in_conference"] = totalLeastLoadedAlreadyInConferenceInRegion
            json["total_least_loaded_in_region_group_in_conference"] = totalLeastLoadedAlreadyInConferenceInRegionGroup
            json["total_least_loaded_in_region"] = totalLeastLoadedInRegion
            json["total_least_loaded_in_region_group"] = totalLeastLoadedInRegionGroup
            json["total_least_loaded_in_conference"] = totalLeastLoadedAlreadyInConference
            json["total_least_loaded"] = totalLeastLoaded
            json["total_split_due_to_region"] = totalSplitDueToRegion
            json["total_split_due_to_load"] = totalSplitDueToLoad
            return json
        }

    companion object {
        /**
         * The logger.
         */
        private val logger: Logger = LoggerImpl(
            BridgeSelectionStrategy::class.java.name
        )

        private fun selectFrom(conferenceBridges: Collection<Bridge>?): Predicate<Bridge> {
            return Predicate { b -> conferenceBridges != null && conferenceBridges.contains(b) }
        }

        private fun inRegion(region: String?): Predicate<Bridge> {
            return Predicate { b -> region != null && region.equals(b.region, ignoreCase = true) }
        }

        private fun inRegionGroup(regionGroup: Set<String>): Predicate<Bridge> {
            return Predicate { b -> regionGroup.contains(b.region) }
        }
    }
}
