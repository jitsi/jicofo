/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.jicofo.bridgeload

import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.utils.logging2.createLogger
import org.jxmpp.jid.impl.JidCreate
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.jvm.Throws
import kotlin.math.min
import kotlin.math.roundToInt
import org.jitsi.jicofo.bridge.BridgeConfig.Companion.config as config

/**
 * This class serves two purposes:
 * 1. Provide an API that can be used externally (exposed via HTTP) to move endpoints away from a bridge:
 *  - [moveEndpoint]
 *  - [moveEndpoints]
 *  - [moveFraction]
 * 2. Optionally, automatically redistribute load from overloaded bridges to non-overloaded ones. This is controlled by
 * the [config.loadRedistribution] configuration.
 */
class LoadRedistributor(private val conferenceStore: ConferenceStore, private val bridgeSelector: BridgeSelector) {
    val logger = createLogger()

    private var task = if (config.loadRedistribution.enabled) {
        logger.info("Enabling automatic load redistribution: ${config.loadRedistribution}")
        TaskPools.scheduledPool.scheduleAtFixedRate(
            { run() },
            config.loadRedistribution.interval.toMillis(),
            config.loadRedistribution.interval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    } else {
        null
    }

    fun shutdown() = task?.let {
        logger.info("Stopping load redistribution")
        it.cancel(true)
        bridgesInTimeout.clear()
    }

    private val bridgesInTimeout: MutableMap<Bridge, Instant> = mutableMapOf()

    private fun run() {
        try {
            logger.trace("Running load redistribution")
            if (!bridgeSelector.hasNonOverloadedBridge()) {
                logger.warn("No non-overloaded bridges, skipping load redistribution")
                return
            }

            cleanupTimeouts()
            bridgeSelector.getAll().filter { !bridgesInTimeout.containsKey(it) }.forEach(::runSingleBridge)
        } catch (e: Exception) {
            logger.error("Error running load redistribution", e)
        }
    }

    private fun runSingleBridge(bridge: Bridge) {
        if (bridge.correctedStress >= config.loadRedistribution.stressThreshold) {
            bridgesInTimeout[bridge] = Instant.now()
            val result = moveEndpoints(bridge.jid.toString(), null, config.loadRedistribution.endpoints)
            logger.info("Moved $result away from ${bridge.jid.resourceOrEmpty}")
            bridge.endpointsMoved(result.movedEndpoints.toLong())
            totalEndpointsMoved.add(result.movedEndpoints.toLong())
        }
    }

    private fun cleanupTimeouts() {
        val limit = Instant.now() - config.loadRedistribution.timeout
        bridgesInTimeout.entries.removeIf { it.value.isBefore(limit) }
    }

    /**
     * Move a specific endpoint in a specific conference.
     */
    @Throws(MoveFailedException::class)
    fun moveEndpoint(
        /** Conference JID, e.g room@conference.example.com. */
        conferenceId: String?,
        /** Endpoint ID, e.g. abcdefgh. */
        endpointId: String?,
        /**
         * Optional bridge JID. If specified, the endpoint will only be moved it if is indeed connected to this bridge.
         */
        bridgeId: String?
    ): MoveResult {
        if (conferenceId.isNullOrBlank()) throw MissingParameterException("conference")
        if (endpointId.isNullOrBlank()) throw MissingParameterException("endpoint")
        val bridge = if (bridgeId.isNullOrBlank()) null else getBridge(bridgeId)
        val conference = getConference(conferenceId)

        logger.info("Moving conference=$conferenceId endpoint=$endpointId bridge=$bridgeId")
        return if (conference.moveEndpoint(endpointId, bridge)) {
            logger.info("Moved successfully")
            MoveResult(1, 1)
        } else {
            // Should we throw?
            logger.info("Failed to move")
            MoveResult(0, 0)
        }
    }

    /**
     * Moves a specific number E of endpoints from a specific bridge B. If a conference is specified, only endpoints in
     * that conference are moved. Otherwise, all conferences are ordered by the number of endpoints on B, and endpoints
     * from large conferences are removed until E is reached.
     *
     * If a conference is specified, the endpoints are selected randomly from it. Otherwise, the endpoints are selected
     * by ordering the list of conferences that use the bridge by the number of endpoints on this bridge. Then we select
     * greedily from the list until we've selected the desired count. Note that this may need to be adjusted if it leads
     * to thundering horde issues (though the recentlyAddedEndpointCount correction should prevent them).
     */
    @Throws(MoveFailedException::class)
    fun moveEndpoints(
        /** Bridge JID, e.g. jvbbrewery@muc.jvb.example.com/jvb1. */
        bridgeId: String?,
        /**
         * Optional conference JID, e.g room@conference.example.com. If specified only endpoints from this conference
         * will be moved.
         */
        conferenceId: String?,
        /** Number of endpoints to move. */
        numEndpoints: Int
    ): MoveResult {
        if (bridgeId.isNullOrBlank()) throw MissingParameterException("bridge")
        val bridge = getBridge(bridgeId)
        val conference = if (conferenceId.isNullOrBlank()) null else getConference(conferenceId)
        val bridgeConferences = if (conference == null) {
            bridge.getConferences()
        } else {
            bridge.getConferences().filter { it.first == conference }
        }
        logger.info("Moving $numEndpoints endpoints from bridge=${bridge.jid} (conference=$conference)")
        val endpointsToMove = bridgeConferences.select(numEndpoints)
        return doMove(bridge, endpointsToMove)
    }

    /**
     * Move a specific fraction of the endpoints from a specific bridge.
     *
     * The endpoints to move are selected by ordering the list of conferences that use the bridge by the number of
     * endpoints on this bridge. Then we select greedily from the list until we've selected the desired count. Note
     * that this may need to be adjusted if it leads to thundering horde issues (though the recentlyAddedEndpointCount
     * correction should prevent them).
     */
    @Throws(MoveFailedException::class)
    fun moveFraction(
        /** Bridge JID, e.g. jvbbrewery@muc.jvb.example.com/jvb1. */
        bridgeId: String?,
        /** The fraction of endpoints to move. */
        fraction: Double
    ): MoveResult {
        if (bridgeId.isNullOrBlank()) throw MissingParameterException("bridge")
        val bridge = getBridge(bridgeId)
        val bridgeConferences = bridge.getConferences()
        val totalEndpoints = bridgeConferences.sumOf { it.second }
        val numEndpoints = (fraction * totalEndpoints).roundToInt()
        logger.info("Moving $fraction of endpoints from bridge=$bridge ($numEndpoints out of $totalEndpoints)")
        val endpointsToMove = bridgeConferences.select(numEndpoints)
        return doMove(bridge, endpointsToMove)
    }

    private fun getConference(conferenceId: String): JitsiMeetConference {
        val conferenceJid = try {
            JidCreate.entityBareFrom(conferenceId)
        } catch (e: Exception) {
            throw InvalidParameterException("conference ID")
        }
        return conferenceStore.getConference(conferenceJid) ?: throw ConferenceNotFoundException(conferenceId)
    }

    private fun getBridge(bridge: String): Bridge {
        val bridgeJid = try {
            JidCreate.from(bridge)
        } catch (e: Exception) {
            throw InvalidParameterException("bridge ID")
        }

        bridgeSelector.get(bridgeJid)?.let { return it }

        val bridgeFullJid = try {
            JidCreate.from("${config.breweryJid}/$bridge")
        } catch (e: Exception) {
            throw InvalidParameterException("bridge ID")
        }
        return bridgeSelector.get(bridgeFullJid) ?: throw BridgeNotFoundException(bridge)
    }

    private fun Bridge.getConferences() = conferenceStore.getAllConferences().mapNotNull { conference ->
        conference.bridges[this]?.participantCount?.let { Pair(conference, it) }
    }.sortedByDescending { it.second }

    private fun doMove(bridge: Bridge, endpointsToMove: Map<JitsiMeetConference, Int>): MoveResult {
        logger.info("Moving endpoints from bridge ${bridge.jid}: $endpointsToMove")
        var movedEndpoints = 0
        var conferences = 0
        endpointsToMove.forEach { (conference, numEps) ->
            val moved = conference.moveEndpoints(bridge, numEps)
            movedEndpoints += moved
            if (moved > 0) conferences++
        }
        logger.info("Moved $movedEndpoints endpoints from $conferences conferences.")
        return MoveResult(movedEndpoints, conferences)
    }

    companion object {
        val totalEndpointsMoved = JicofoMetricsContainer.instance.registerCounter(
            "load_redistributor_endpoints_moved",
            "Total number of endpoints moved away from any bridge for automatic load redistribution"
        )
    }
}

data class MoveResult(
    val movedEndpoints: Int,
    val conferences: Int
) {
    override fun toString() = "$movedEndpoints endpoints from $conferences conferences"
}

/**
 * Select endpoints to move, e.g. with a map m={a: 1, b: 3, c: 3}:
 * select(m, 1) should return {a: 1}
 * select(m, 2) should return {a: 1, b: 1}
 * select(m, 3) should return {a: 1, b: 2}
 * select(m, 6) should return {a: 1, b: 3, c: 2}
 * select(m, 100) should return {a: 1, b: 3, c: 3}
 *
 * That is, it selects greedily in the order of the list.
 */
private fun <T> List<Pair<T, Int>>.select(n: Int): Map<T, Int> {
    var moved = 0
    return buildMap {
        this@select.forEach {
            if (moved >= n) {
                return@forEach
            }
            val m = min(it.second, n - moved)
            moved += m
            put(it.first, m)
        }
    }
}

sealed class MoveFailedException(msg: String) : Exception(msg)
class MissingParameterException(name: String) : MoveFailedException("Missing parameter: $name")
class InvalidParameterException(name: String) : MoveFailedException("Invalid parameter: $name")
class BridgeNotFoundException(bridge: String) : MoveFailedException("Bridge not found: $bridge")
class ConferenceNotFoundException(conference: String) : MoveFailedException("Conference not found: $conference")
