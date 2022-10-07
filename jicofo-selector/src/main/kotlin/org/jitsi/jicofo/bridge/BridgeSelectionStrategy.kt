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

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.json.simple.JSONObject

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
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        allowMultiBridge: Boolean
    ): Bridge? {
        return if (conferenceBridges.isEmpty()) {
            val bridge = doSelect(bridges, conferenceBridges, participantProperties)
            if (bridge != null) {
                logger.info(
                    "Selected initial bridge $bridge with reported stress=${bridge.lastReportedStressLevel} " +
                        "for participantProperties=$participantProperties using strategy ${this.javaClass.simpleName}"
                )
            } else {
                logger.warn(
                    "Failed to select initial bridge for participantProperties=$participantProperties"
                )
            }
            bridge
        } else {
            val existingBridge = conferenceBridges.keys.first()
            if (!allowMultiBridge || existingBridge.relayId == null) {
                logger.info("Existing bridge does not have a relay, will not consider other bridges.")
                return existingBridge
            }
            val bridge = doSelect(bridges, conferenceBridges, participantProperties)
            if (bridge != null) {
                logger.info(
                    "Selected bridge $bridge with stress=${bridge.lastReportedStressLevel} " +
                        "for participantProperties=$participantProperties"
                )
            } else {
                logger.warn("Failed to select bridge for participantProperties=$participantProperties")
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
     * @param desiredRegion the selection strategy's desired region.  Depending on the policy this
     *  may or may not be the same as the participant's region.
     *
     * @return a bridge that is not loaded and that
     * is already in the conference and that is in the participant region, if one
     * exists, or null
     */
    fun notLoadedAlreadyInConferenceInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val result = bridges
            .filterNot { isOverloaded(it, conferenceBridges) }
            .intersect(conferenceBridges.keys)
            .firstOrNull { desiredRegion != null && it.region.equals(desiredRegion) }
        if (result != null) {
            totalNotLoadedAlreadyInConferenceInRegion++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    fun notLoadedAlreadyInConferenceInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val regionGroup = getRegionGroup(desiredRegion)
        val result = bridges
            .filterNot { isOverloaded(it, conferenceBridges) }
            .intersect(conferenceBridges.keys)
            .firstOrNull { regionGroup.contains(it.region) }
        if (result != null) {
            totalNotLoadedAlreadyInConferenceInRegionGroup++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    private fun logSelection(
        bridge: Bridge,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String? = null
    ) {
        val method = Thread.currentThread().stackTrace[2].methodName
        logger.debug {
            "Bridge selected: method=$method, desiredRegion=$desiredRegion, " +
                "participantProperties=$participantProperties, bridge=$bridge, " +
                "conference_bridges=${conferenceBridges.keys.joinToString()}"
        }
    }

    /**
     * Finds the least loaded bridge in the desired region that is not
     * overloaded.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param desiredRegion the selection strategy's desired region.  Depending on the policy this
     *  may or may not be the same as the participant's region.
     *
     * @return a bridge that is not loaded and that is in the participant region, if one exists, or null
     */
    fun notLoadedInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val result = bridges
            .filterNot { isOverloaded(it, conferenceBridges) }
            .firstOrNull { desiredRegion != null && it.region.equals(desiredRegion) }
        if (result != null) {
            totalNotLoadedInRegion++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    fun notLoadedInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val regionGroup = getRegionGroup(desiredRegion)
        val result = bridges
            .filterNot { isOverloaded(it, conferenceBridges) }
            .firstOrNull { regionGroup.contains(it.region) }
        if (result != null) {
            totalNotLoadedInRegionGroup++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    /**
     * Finds the least loaded conference bridge in the desired region that
     * is already handling the conference.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param conferenceBridges the set of bridges that are already used in the conference.
     * @param desiredRegion the selection strategy's desired region.  Depending on the policy this
     *  may or may not be the same as the participant's region.
     *
     * @return the least loaded bridge that is already
     * in the conference and that is in the participant region, if it exists, or null
     */
    fun leastLoadedAlreadyInConferenceInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val result = bridges
            .intersect(conferenceBridges.keys)
            .firstOrNull { desiredRegion != null && it.region.equals(desiredRegion) }
        if (result != null) {
            totalLeastLoadedAlreadyInConferenceInRegion++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    fun leastLoadedAlreadyInConferenceInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val regionGroup = getRegionGroup(desiredRegion)
        val result = bridges
            .intersect(conferenceBridges.keys)
            .firstOrNull { regionGroup.contains(it.region) }
        if (result != null) {
            totalLeastLoadedAlreadyInConferenceInRegionGroup++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    /**
     * Finds the least loaded bridge in a desired region.
     *
     * @param bridges the list of operational bridges, ordered by load.
     * @param desiredRegion the selection strategy's desired region.  Depending on the policy this
     *  may or may not be the same as the participant's region.
     *
     * @return the least loaded bridge in the participant's region, if it exists, or null.
     */
    fun leastLoadedInRegion(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val result = bridges
            .firstOrNull { desiredRegion != null && it.region.equals(desiredRegion) }
        if (result != null) {
            totalLeastLoadedInRegion++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
        }
        return result
    }

    fun leastLoadedInRegionGroup(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        desiredRegion: String?
    ): Bridge? {
        val regionGroup = getRegionGroup(desiredRegion)
        val result = bridges
            .firstOrNull { regionGroup.contains(it.region) }
        if (result != null) {
            totalLeastLoadedInRegionGroup++
            logSelection(result, conferenceBridges, participantProperties, desiredRegion)
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
     * @return the least loaded bridge that is already in the conference, if it exists, or null
     */
    fun nonLoadedAlreadyInConference(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties
    ): Bridge? {
        val result = bridges
            .filterNot { isOverloaded(it, conferenceBridges) }
            .intersect(conferenceBridges.keys)
            .firstOrNull()
        if (result != null) {
            totalLeastLoadedAlreadyInConference++
            logSelection(result, conferenceBridges, participantProperties)
        }
        return result
    }

    /**
     * Finds the least loaded bridge.
     *
     * @param bridges the list of operational bridges, ordered by load.
     *
     * @return the least loaded bridge, if it exists, or null.
     */
    fun leastLoaded(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties
    ): Bridge? {
        val result = bridges.firstOrNull()
        if (result != null) {
            totalLeastLoaded++
            logSelection(result, conferenceBridges, participantProperties)
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
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties
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
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>
    ): Boolean {
        return bridge.isOverloaded || (
            maxParticipantsPerBridge > 0 &&
                conferenceBridges.containsKey(bridge) &&
                conferenceBridges[bridge]!!.participantCount >= maxParticipantsPerBridge
            )
    }

    private val regionGroups: MutableMap<String, Set<String>> = HashMap()

    init {
        BridgeConfig.config.regionGroups.forEach { regionGroup ->
            regionGroup.forEach { region ->
                regionGroups[region] = regionGroup
            }
        }
    }

    protected fun getRegionGroup(region: String?): Set<String> {
        if (region == null) return setOf()
        val regionGroup = regionGroups[region]
        return regionGroup ?: setOf(region)
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
            return json
        }

    companion object {
        /**
         * The logger.
         */
        private val logger: Logger = LoggerImpl(
            BridgeSelectionStrategy::class.java.name
        )
    }
}
