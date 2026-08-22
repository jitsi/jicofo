/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.jicofo.conference.agent

import org.jitsi.jicofo.AgentConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.colibri.AgentConnectRequest
import org.jitsi.jicofo.bridge.colibri.ColibriSessionManager
import org.jitsi.jicofo.bridge.colibri.ParticipantAllocationParameters
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.jicofo.xmpp.RoomMetadata
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.util.concurrent.ThreadLocalRandom

/**
 * Ties the voice-agent request map (from RoomMetadata) to the conference: for each requested agent it allocates a
 * synthetic (transport-less) colibri2 endpoint owning a synthetic audio source with a freshly minted SSRC, and
 * drives the agent `<connect>` on the bridge session hosting that endpoint.
 *
 * The agent's sources are NOT added to the conference source map (no Jingle signaling): clients learn about the
 * agent from room metadata and receive its audio only after explicitly subscribing to its source, which is
 * delivered over the bridge channel (AudioSourcesMap).
 *
 * Endpoint allocation involves a blocking colibri round-trip, so it runs on the IO pool; connects are (re)applied
 * once the allocation completes. All state ([allocated], [ready], [pending]) is guarded by this object's monitor.
 */
class ConferenceAgentManager(
    /** Used only to check SSRCs already in use in the conference when minting; never modified. */
    private val conferenceSources: ValidatingConferenceSourceMap,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    private var requests: Map<String, RoomMetadata.Metadata.Agent> = emptyMap()

    /** Agent id -> its synthetic audio source. Kept stable across updates (an existing agent keeps its SSRC). */
    private val allocated = mutableMapOf<String, Source>()

    /** Agents whose synthetic endpoint has been allocated on a bridge. */
    private val ready = mutableSetOf<String>()

    /** Agents with an allocation in flight on the IO pool. */
    private val pending = mutableSetOf<String>()

    /** Update the requested agents (agent id -> connect config) and re-apply. */
    @Synchronized
    fun setRequests(
        requests: Map<String, RoomMetadata.Metadata.Agent>,
        colibriSessionManager: ColibriSessionManager?,
        meetingId: String?
    ) {
        this.requests = requests
        apply(colibriSessionManager, meetingId)
    }

    /** Re-apply the last request map (e.g. once colibri is ready). */
    @Synchronized
    fun reapply(colibriSessionManager: ColibriSessionManager?, meetingId: String?) =
        apply(colibriSessionManager, meetingId)

    @Synchronized
    private fun apply(colibriSessionManager: ColibriSessionManager?, meetingId: String?) {
        if (colibriSessionManager == null || meetingId == null) {
            // Not ready yet; will be applied from JitsiMeetConferenceImpl once colibri is initialized.
            return
        }
        if (!AgentConfig.config.enabled) {
            if (requests.isNotEmpty()) {
                logger.warn("Voice agents requested, but no agent URL is configured. Ignoring.")
            }
            return
        }

        (allocated.keys - requests.keys).toList().forEach { id ->
            logger.info("Removing agent $id")
            allocated.remove(id)
            pending.remove(id)
            if (ready.remove(id)) {
                colibriSessionManager.removeParticipant(id)
            }
        }

        requests.keys.forEach { id ->
            val source = allocated.getOrPut(id) {
                Source(mintSsrc(), MediaType.AUDIO, name = sourceName(id), synthetic = true)
            }
            if (id !in ready && id !in pending) {
                logger.info("Allocating synthetic endpoint for agent $id (${source.name}, ssrc ${source.ssrc})")
                pending.add(id)
                TaskPools.ioPool.submit { allocateAgent(id, source, colibriSessionManager, meetingId) }
            }
        }

        updateConnects(colibriSessionManager, meetingId)
    }

    /** Runs on the IO pool: the colibri allocation is a blocking XMPP round-trip, done outside the monitor. */
    private fun allocateAgent(
        id: String,
        source: Source,
        colibriSessionManager: ColibriSessionManager,
        meetingId: String
    ) {
        try {
            colibriSessionManager.allocate(
                ParticipantAllocationParameters(
                    id = id,
                    statsId = null,
                    region = null,
                    sources = EndpointSourceSet(source),
                    useSsrcRewriting = false,
                    useRtpMidDemux = false,
                    forceMuteAudio = false,
                    forceMuteVideo = false,
                    useSctp = false,
                    visitor = false,
                    supportsPrivateAddresses = false,
                    diarize = false,
                    medias = emptySet(),
                    synthetic = true
                )
            )
            // The allocation request doesn't carry sources; signal them now.
            colibriSessionManager.updateParticipant(id, sources = EndpointSourceSet(source))
        } catch (e: Exception) {
            logger.error("Failed to allocate synthetic endpoint for agent $id", e)
            synchronized(this) { pending.remove(id) }
            return
        }

        synchronized(this) {
            pending.remove(id)
            if (id in allocated) {
                ready.add(id)
                updateConnects(colibriSessionManager, meetingId)
            } else {
                // The agent was removed while its allocation was in flight.
                logger.info("Agent $id was removed during allocation, expiring its endpoint.")
                colibriSessionManager.removeParticipant(id)
            }
        }
    }

    @Synchronized
    private fun updateConnects(colibriSessionManager: ColibriSessionManager, meetingId: String) {
        val url = AgentConfig.config.getUrl(meetingId) ?: return
        val connects = ready.mapNotNull { id ->
            val agent = requests[id] ?: return@mapNotNull null
            AgentConnectRequest(
                endpointId = id,
                syntheticSourceName = sourceName(id),
                url = url,
                urlParams = agent.urlParams,
                httpHeaders = agent.httpHeaders
            )
        }
        colibriSessionManager.setAgents(connects)
    }

    /** Whether an SSRC is already used by a source in the conference or by another agent. */
    private fun ssrcInUse(ssrc: Long): Boolean = allocated.values.any { it.ssrc == ssrc } ||
        conferenceSources.unmodifiable().values.any { set -> set.sources.any { it.ssrc == ssrc } }

    private fun mintSsrc(): Long {
        repeat(MAX_MINT_ATTEMPTS) {
            val candidate = ThreadLocalRandom.current().nextLong(1, MAX_SSRC)
            if (!ssrcInUse(candidate)) {
                return candidate
            }
        }
        throw IllegalStateException("Failed to mint a non-conflicting SSRC after $MAX_MINT_ATTEMPTS attempts")
    }

    companion object {
        private const val MAX_SSRC = 0x1_0000_0000L
        private const val MAX_MINT_ATTEMPTS = 1000

        /** The agent's synthetic audio source name, derived from its endpoint id like a client's first audio source. */
        fun sourceName(agentId: String) = "$agentId-a0"
    }
}
