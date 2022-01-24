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

import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.colibri.v1.ColibriV1SessionManager
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jxmpp.jid.Jid

interface ColibriSessionManager {
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** Expire all colibri sessions. */
    fun expire()

    /** Remove a participant, expiring all resources allocated for it */
    fun removeParticipant(participant: Participant)

    /**
     *  Remove a set of participants, expiring all resources allocated for them.
     *
     *  Defined in addition to [removeParticipant] to allow implementations to perform it atomically.
     */
    fun removeParticipants(participants: Collection<Participant>)

    fun addSources(participant: Participant, sources: ConferenceSourceMap)

    /**
     *  Note at the time this is called [participant.sources] have already been updated.
     * TODO: remove in favor of updateParticipant
     */
    fun removeSources(
        participant: Participant,
        sources: ConferenceSourceMap,
        /**
         * If this is `false`, the source removal will only be signaled to remote bridges. This is used to avoid sending
         * an unnecessary "remove sources" message prior to the endpoint itself being expired (the "remove sources"
         * message for remote bridges is always necessary).
         */
        removeSourcesFromLocalBridge: Boolean
    )

    fun mute(participant: Participant, doMute: Boolean, mediaType: MediaType): Boolean
    val bridgeCount: Int
    val bridgeRegions: Set<String>
    @Throws(ColibriAllocationFailedException::class)
    fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        reInvite: Boolean
    ): ColibriAllocation

    fun updateParticipant(
        participant: Participant,
        transport: IceUdpTransportPacketExtension?,
        sources: ConferenceSourceMap?,
        rtpDescriptions: Map<String, RtpDescriptionPacketExtension>?
    )
    fun getAllocation(participant: Participant): ColibriAllocation?
    fun bridgesDown(bridges: Set<Jid>): List<Participant>

    val debugState: OrderedJsonObject

    /**
     * Interface for events fired by [ColibriSessionManager].
     *
     * Note that [ColibriV1SessionManager] calls these while holding its internal lock, so listeners should be careful
     * not to perform action that will cause a deadlock.
     */
    interface Listener {
        /** The number of bridges changed. */
        fun bridgeCountChanged(bridgeCount: Int)
        /** A specific number of bridges were removed from the conference because they failed. */
        fun failedBridgesRemoved(count: Int)
    }
}
