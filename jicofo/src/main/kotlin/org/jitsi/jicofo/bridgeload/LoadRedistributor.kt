package org.jitsi.jicofo.bridgeload

import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeConfig
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.utils.logging2.createLogger
import org.jxmpp.jid.impl.JidCreate
import kotlin.jvm.Throws
import kotlin.math.min
import kotlin.math.roundToInt

class LoadRedistributor(val conferenceStore: ConferenceStore, val bridgeSelector: BridgeSelector) {
    val logger = createLogger()

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
        logger.info("Moving $numEndpoints from bridge=${bridge.jid} (conference=$conference)")
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
            JidCreate.from("${BridgeConfig.config.breweryJid}/$bridge")
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
}

data class MoveResult(
    val movedEndpoints: Int,
    val conferences: Int
)

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
class MissingParameterException(name: String): MoveFailedException("Missing parameter: $name")
class InvalidParameterException(name: String): MoveFailedException("Invalid parameter: $name")
class BridgeNotFoundException(bridge: String) : MoveFailedException("Bridge not found: $bridge")
class ConferenceNotFoundException(conference: String) : MoveFailedException("Conference not found: $conference")
