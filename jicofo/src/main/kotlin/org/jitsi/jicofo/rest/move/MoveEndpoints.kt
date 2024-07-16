/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc
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
package org.jitsi.jicofo.rest.move

import jakarta.servlet.http.HttpServletResponse
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jitsi.config.JitsiConfig
import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeConfig
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.rest.BadRequestExceptionWithMessage
import org.jitsi.metaconfig.config
import org.jitsi.utils.logging2.createLogger
import org.jxmpp.jid.impl.JidCreate
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An API for moving (i.e. re-inviting) endpoints. The main goal is to facilitate reducing the load on a bridge. Note
 * that when re-inviting the normal bridge selection logic is used again, so it's possible that the same bridge is
 * selected (unless it's unhealthy/draining or overloaded and there are less loaded bridges).
 */
@Path("/move-endpoints")
class MoveEndpoints(
    val conferenceStore: ConferenceStore,
    val bridgeSelector: BridgeSelector
) {
    val logger = createLogger()

    /**
     * Move a specific endpoint in a specific conference.
     */
    @Path("move-endpoint")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun moveEndpoint(
        /**
         * Conference JID, e.g room@conference.example.com. This is a required parameter, but without a @DefaultValue
         * jetty returns a 500 and prints a stack trace.
         */
        @QueryParam("conference") @DefaultValue("") conferenceId: String,
        /**
         * Endpoint ID, e.g. abcdefgh. This is a required parameter, but without a @DefaultValue jetty returns a 500
         * and prints a stack trace.
         */
        @QueryParam("endpoint") @DefaultValue("") endpointId: String,
        /**
         * Optional bridge JID. If specified, the endpoint will only be moved it if is indeed connected to this bridge.
         */
        @QueryParam("bridge") @DefaultValue("") bridgeId: String
    ): Result {
        if (conferenceId.isEmpty()) throw BadRequestExceptionWithMessage("Conference ID is missing")
        if (endpointId.isEmpty()) throw BadRequestExceptionWithMessage("Endpoint ID is missing")
        val bridge = if (bridgeId.isEmpty()) null else getBridge(bridgeId)
        val conference = getConference(conferenceId)

        logger.info("Moving conference=$conferenceId endpoint=$endpointId bridge=$bridgeId")
        return if (conference.moveEndpoint(endpointId, bridge)) {
            logger.info("Moved successfully")
            Result(1, 1)
        } else {
            logger.info("Failed to move")
            Result(0, 0)
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
    @Path("move-endpoints")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun moveEndpoints(
        /**
         * Bridge JID, e.g. jvbbrewery@muc.jvb.example.com/jvb1. This is a required parameter, but without a
         * @DefaultValue jetty returns a 500 and prints a stack trace.
         */
        @QueryParam("bridge") @DefaultValue("") bridgeId: String,
        /**
         * Optional conference JID, e.g room@conference.example.com. If specified only endpoints from this conference
         * will be moved.
         */
        @QueryParam("conference") @DefaultValue("") conferenceId: String,
        /** Number of endpoints to move. */
        @QueryParam("endpoints") @DefaultValue("1") numEndpoints: Int
    ): Result {
        if (bridgeId.isEmpty()) throw BadRequestExceptionWithMessage("Bridge JID is missing")
        val bridge = getBridge(bridgeId)
        val conference = if (conferenceId.isEmpty()) null else getConference(conferenceId)
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
    @Path("move-fraction")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun moveFraction(
        /**
         * Bridge JID, e.g. jvbbrewery@muc.jvb.example.com/jvb1. This is a required parameter, but without a
         * @DefaultValue jetty returns a 500 and prints a stack trace.
         */
        @QueryParam("bridge") @DefaultValue("") bridgeId: String,
        /** The fraction of endpoints to move. Defaults to 10% */
        @QueryParam("fraction") @DefaultValue("0.1") fraction: Double
    ): Result {
        if (bridgeId.isEmpty()) throw BadRequestExceptionWithMessage("Bridge JID is missing")
        val bridge = getBridge(bridgeId)
        val bridgeConferences = bridge.getConferences()
        val totalEndpoints = bridgeConferences.sumOf { it.second }
        val numEndpoints = (fraction * totalEndpoints).roundToInt()
        logger.info("Moving $fraction of endpoints from bridge=$bridge ($numEndpoints out of $totalEndpoints)")
        val endpointsToMove = bridgeConferences.select(numEndpoints)
        return doMove(bridge, endpointsToMove)
    }

    private fun doMove(bridge: Bridge, endpointsToMove: Map<JitsiMeetConference, Int>): Result {
        logger.info("Moving endpoints from bridge ${bridge.jid}: $endpointsToMove")
        var movedEndpoints = 0
        var conferences = 0
        endpointsToMove.forEach { (conference, numEps) ->
            val moved = conference.moveEndpoints(bridge, numEps)
            movedEndpoints += moved
            if (moved > 0) conferences++
        }
        logger.info("Moved $movedEndpoints endpoints from $conferences conferences.")
        return Result(movedEndpoints, conferences)
    }

    private fun getBridge(bridge: String): Bridge {
        val bridgeJid = try {
            JidCreate.from(bridge)
        } catch (e: Exception) {
            throw BadRequestExceptionWithMessage("Invalid bridge ID")
        }

        bridgeSelector.get(bridgeJid)?.let { return it }

        val bridgeFullJid = try {
            JidCreate.from("${BridgeConfig.config.breweryJid}/$bridge")
        } catch (e: Exception) {
            throw BadRequestExceptionWithMessage("Invalid bridge ID")
        }
        return bridgeSelector.get(bridgeFullJid) ?: throw NotFoundExceptionWithMessage("Bridge not found")
    }

    private fun getConference(conferenceId: String): JitsiMeetConference {
        val conferenceJid = try {
            JidCreate.entityBareFrom(conferenceId)
        } catch (e: Exception) {
            throw BadRequestExceptionWithMessage("Invalid conference ID")
        }
        return conferenceStore.getConference(conferenceJid)
            ?: throw NotFoundExceptionWithMessage("Conference not found")
    }

    private fun Bridge.getConferences() = conferenceStore.getAllConferences().mapNotNull { conference ->
        conference.bridges[this]?.participantCount?.let { Pair(conference, it) }
    }.sortedByDescending { it.second }
}

data class Result(
    val movedEndpoints: Int,
    val conferences: Int
)

class MoveEndpointsConfig {
    companion object {
        val enabled: Boolean by config {
            "jicofo.rest.move-endpoints.enabled".from(JitsiConfig.newConfig)
        }
    }
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

/**
 * The [NotFoundException(String message)] constructor doesn't actually include the message in the response.
 */
class NotFoundExceptionWithMessage(message: String?) : NotFoundException(
    Response.status(HttpServletResponse.SC_NOT_FOUND, message).build()
)
