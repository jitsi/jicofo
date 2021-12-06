/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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

package org.jitsi.jicofo.conference.colibri.v2

import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.colibri.ColibriAllocation
import org.jitsi.jicofo.conference.colibri.ColibriSessionManager
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jxmpp.jid.Jid

class ColibriV2SessionManager(
    parentLogger: Logger
) : ColibriSessionManager {
    private val logger = createChildLogger(parentLogger)

    private val eventEmitter = SyncEventEmitter<ColibriSessionManager.Listener>()
    override fun addListener(listener: ColibriSessionManager.Listener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ColibriSessionManager.Listener) = eventEmitter.removeHandler(listener)

    override fun expire() {
        TODO("Not yet implemented")
    }

    override fun removeParticipant(participant: Participant) {
        TODO("Not yet implemented")
    }

    override fun removeParticipants(participants: Collection<Participant>) {
        TODO("Not yet implemented")
    }

    override fun addSources(participant: Participant, sources: ConferenceSourceMap) {
        TODO("Not yet implemented")
    }

    override fun removeSources(participant: Participant, sources: ConferenceSourceMap) {
        TODO("Not yet implemented")
    }

    override fun mute(participant: Participant, doMute: Boolean, mediaType: MediaType): Boolean {
        TODO("Not yet implemented")
    }

    override val bridgeCount: Int
        get() = TODO("Not yet implemented")
    override val bridgeRegions: Set<String>
        get() = TODO("Not yet implemented")

    override fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        reInvite: Boolean
    ): ColibriAllocation {
        TODO("Not yet implemented")
    }

    override fun updateTransportInfo(participant: Participant, contents: List<ContentPacketExtension>) {
        TODO("Not yet implemented")
    }

    override fun updateChannels(participant: Participant) {
        TODO("Not yet implemented")
    }

    override fun setRtpDescriptionMap(participant: Participant, contents: List<ContentPacketExtension>) {
        TODO("Not yet implemented")
    }

    override fun addTransportFromJingle(participant: Participant, contents: List<ContentPacketExtension>) {
        TODO("Not yet implemented")
    }

    override fun updateSources(participant: Participant, sources: ConferenceSourceMap) {
        TODO("Not yet implemented")
    }

    override fun getAllocation(participant: Participant): ColibriAllocation? {
        TODO("Not yet implemented")
    }

    override fun bridgesDown(bridges: Set<Jid>): List<Participant> {
        TODO("Not yet implemented")
    }
}