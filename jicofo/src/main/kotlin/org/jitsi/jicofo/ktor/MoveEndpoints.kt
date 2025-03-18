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
package org.jitsi.jicofo.ktor

import org.jitsi.jicofo.bridgeload.BridgeNotFoundException
import org.jitsi.jicofo.bridgeload.ConferenceNotFoundException
import org.jitsi.jicofo.bridgeload.InvalidParameterException
import org.jitsi.jicofo.bridgeload.LoadRedistributor
import org.jitsi.jicofo.bridgeload.MissingParameterException
import org.jitsi.jicofo.bridgeload.MoveFailedException
import org.jitsi.jicofo.bridgeload.MoveResult
import org.jitsi.jicofo.ktor.exception.BadRequest
import org.jitsi.jicofo.ktor.exception.NotFound
import org.jitsi.utils.logging2.createLogger

/**
 * An API for moving (i.e. re-inviting) endpoints. The main goal is to facilitate reducing the load on a bridge. Note
 * that when re-inviting the normal bridge selection logic is used again, so it's possible that the same bridge is
 * selected (unless it's unhealthy/draining or overloaded and there are less loaded bridges).
 */
class MoveEndpoints(private val loadRedistributor: LoadRedistributor) {
    val logger = createLogger()

    /**
     * Move a specific endpoint in a specific conference.
     */
    fun moveEndpoint(
        /** Conference JID, e.g room@conference.example.com. */
        conferenceId: String?,
        /** Endpoint ID, e.g. abcdefgh. */
        endpointId: String?,
        /**
         * Optional bridge JID. If specified, the endpoint will only be moved it if is indeed connected to this bridge.
         */
        bridgeId: String?
    ): MoveResult = translateException { loadRedistributor.moveEndpoint(conferenceId, endpointId, bridgeId) }

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
    ): MoveResult = translateException { loadRedistributor.moveEndpoints(bridgeId, conferenceId, numEndpoints) }

    /**
     * Move a specific fraction of the endpoints from a specific bridge.
     *
     * The endpoints to move are selected by ordering the list of conferences that use the bridge by the number of
     * endpoints on this bridge. Then we select greedily from the list until we've selected the desired count. Note
     * that this may need to be adjusted if it leads to thundering horde issues (though the recentlyAddedEndpointCount
     * correction should prevent them).
     */
    fun moveFraction(
        /** Bridge JID, e.g. jvbbrewery@muc.jvb.example.com/jvb1. */
        bridgeId: String?,
        /** The fraction of endpoints to move. */
        fraction: Double
    ): MoveResult = translateException { loadRedistributor.moveFraction(bridgeId, fraction) }

    private fun translateException(block: () -> MoveResult): MoveResult {
        return try {
            block()
        } catch (e: MoveFailedException) {
            throw when (e) {
                is BridgeNotFoundException -> NotFound("Bridge not found")
                is ConferenceNotFoundException -> NotFound("Conference not found")
                is MissingParameterException, is InvalidParameterException -> BadRequest(e.message)
            }
        }
    }
}
