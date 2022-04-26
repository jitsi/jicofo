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
package org.jitsi.jicofo.conference.colibri

import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension

interface ColibriSessionManager {
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** Expire all colibri sessions. */
    fun expire()

    /** Remove a participant, expiring all resources allocated for it */
    fun removeParticipant(participant: Participant)

    fun mute(participantId: String, doMute: Boolean, mediaType: MediaType): Boolean =
        mute(setOf(participantId), doMute, mediaType)
    fun mute(participantIds: Set<String>, doMute: Boolean, mediaType: MediaType): Boolean
    val bridgeCount: Int
    val bridgeRegions: Set<String>
    @Throws(ColibriAllocationFailedException::class)
    fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        forceMuteAudio: Boolean,
        forceMuteVideo: Boolean
    ): ColibriAllocation

    /** For use in java because @JvmOverloads is not available for interfaces. */
    fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension? = null,
        sources: ConferenceSourceMap? = null,
    ) = updateParticipant(participant, transport, sources, false)

    fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension? = null,
        sources: ConferenceSourceMap? = null,
        suppressLocalBridgeUpdate: Boolean = false
    )
    fun getBridgeSessionId(participant: Participant): String?

    /**
     * Stop using [bridge] (because they were detected to have failed).
     * @return the list of participant IDs which were on one of the removed bridges and now need to be re-invited.
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
    }
}
