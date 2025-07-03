/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
package org.jitsi.jicofo.bridge.colibri

import org.jitsi.jicofo.MediaType
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.ConferenceBridgeProperties
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.TemplatedUrl
import org.jitsi.xmpp.extensions.colibri2.InitialLastN
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension

interface ColibriSessionManager {
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** Expire all colibri sessions. */
    fun expire()

    /** Remove a participant, expiring all resources allocated for it */
    fun removeParticipant(participantId: String)

    fun mute(participantId: String, doMute: Boolean, mediaType: MediaType): Boolean =
        mute(setOf(participantId), doMute, mediaType)
    fun mute(participantIds: Set<String>, doMute: Boolean, mediaType: MediaType): Boolean
    val bridgeCount: Int
    val bridgeRegions: Set<String>

    /** Get the list of participant IDs that are currently allocated on a specific [Bridge]. */
    fun getParticipants(bridge: Bridge): List<String>

    @Throws(ColibriAllocationFailedException::class, BridgeSelectionFailedException::class)
    fun allocate(participant: ParticipantAllocationParameters): ColibriAllocation
    fun getBridges(): Map<Bridge, ConferenceBridgeProperties>

    fun updateParticipant(
        participantId: String,
        transport: IceUdpTransportPacketExtension? = null,
        sources: EndpointSourceSet? = null,
        initialLastN: InitialLastN? = null,
        suppressLocalBridgeUpdate: Boolean = false
    )

    fun getBridgeSessionId(participantId: String): Pair<Bridge?, String?>

    fun setTranscriberUrl(url: TemplatedUrl?)

    /**
     * Stop using [bridge], expiring all endpoints on it (e.g. because it was detected to have failed).
     * @return the list of participant IDs which were on the removed bridge and now need to be re-invited.
     */
    fun removeBridge(bridge: Bridge): List<String>

    val debugState: OrderedJsonObject

    /**
     * Interface for events fired by [ColibriSessionManager].
     */
    interface Listener {
        /** The number of bridges changed. */
        fun bridgeCountChanged(bridgeCount: Int)

        fun bridgeSelectionFailed() {}
        fun bridgeSelectionSucceeded() {}

        /** A bridge was removed from the conference due to a failure. **/
        fun bridgeRemoved(
            /** The bridge that was removed. */
            bridge: Bridge,
            /** The list of participant IDs which were on the removed bridge. **/
            participantIds: List<String>
        )

        /** Endpoint removed due to a failure e.g. unknown endpoint */
        fun endpointRemoved(endpointId: String)
    }
}

data class ParticipantAllocationParameters(
    val id: String,
    val statsId: String?,
    val region: String?,
    val sources: EndpointSourceSet,
    val useSsrcRewriting: Boolean,
    val forceMuteAudio: Boolean,
    val forceMuteVideo: Boolean,
    val useSctp: Boolean,
    val visitor: Boolean,
    val supportsPrivateAddresses: Boolean,
    val medias: Set<Media>
)
