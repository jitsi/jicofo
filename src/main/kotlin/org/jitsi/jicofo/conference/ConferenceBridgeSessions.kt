/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

package org.jitsi.jicofo.conference

import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jxmpp.jid.Jid

/**
 * Contain the set of [BridgeSession]s used by a single conference.
 */
class ConferenceBridgeSessions(
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    /** The underlying list. */
    private val bridgeSessions: MutableList<BridgeSession> = mutableListOf()
    /** Get the number of [BridgeSession]s */
    fun count() = bridgeSessions.size

    /** Get the subset of [BridgeSession]s that are operational (i.e. the bridge hasn't failed). */
    fun operational() = bridgeSessions.filter { it.isOperational() }
    /** Get the number of [BridgeSession]s that have operational bridges. */
    fun operationalCount() = bridgeSessions.count { it.isOperational() }
    /** Get the subset of [BridgeSession]s that are non-operational. */
    fun nonOperational() = bridgeSessions.filter { !it.isOperational() }

    /** Add a [BridgeSession] to the list. */
    fun add(bridgeSession: BridgeSession) = bridgeSessions.add(bridgeSession)

    /** Get the set of "relays" for operational bridges in the list. */
    fun getAllRelays(
        /** An optional relay ID to exclude from the returned list. */
        exclude: String? = null
    ) = operational().mapNotNull { it.bridge.relayId }.filter { it != exclude }.toSet()

    /** Updates the Octo relays for all operational bridge sessions. */
    fun updateOctoRelays() {
        val allRelays = getAllRelays().toList()
        logger.info("Updating Octo relays: $allRelays")
        operational().forEach { it.setRelays(allRelays) }
    }

    /** Return the bridge session for a given [Participant] or else null. */
    fun find(participant: Participant) = bridgeSessions.find { it.participants.contains(participant) }
    /** Return the bridge session for a given [Bridge] or else null. */
    fun find(bridge: Bridge) = bridgeSessions.find { it.bridge == bridge }
    /** Return the bridge session for a given JID of a [Bridge] or else null. */
    fun find(bridgeJid: Jid) = bridgeSessions.find { it.bridge.jid == bridgeJid }

    /** Dispose of all [BridgeSession]s and clear them from the list. */
    fun dispose() {
        // No need to expire channels, just expire the whole colibri conference.
        // bridgeSessions.forEach { it. terminateAll() }
        bridgeSessions.forEach { it.dispose() }
        bridgeSessions.clear()
    }

    /**
     * Expires a specific [bridgeSession] if it has no more participants.
     * @return True if the bridge sessions was expired, and false otherwise.
     */
    fun maybeExpire(bridgeSession: BridgeSession): Boolean {
        if (bridgeSession.participants.isEmpty()) {
            // This is needed to terminate the octo participant.
            bridgeSession.terminateAll()
            bridgeSessions.remove(bridgeSession)
            updateOctoRelays();
            return true
        }
        return false
    }

    /** Adds sources to the octo participant of each [BridgeSession] (except [exclude]). */
    fun propagateNewSourcesToOcto(
        /** A [BridgeSession] to exclude, i.e. to not add sources to. */
        exclude: BridgeSession?,
        /** The sources to add. */
        sources: ConferenceSourceMap
    ) = operational().filter { it != exclude }.forEach { it.addSourcesToOcto(sources) }

    /** Removes sources from the octo participant of each [BridgeSession] (except [exclude]). */
    fun removeSourcesFromOcto(
        /** A [BridgeSession] to exclude, i.e. to not remove sources from. */
        exclude: BridgeSession?,
        /** The sources to remove. */
        sources: ConferenceSourceMap
    ) = operational().filter { it != exclude }.forEach { it.removeSourcesFromOcto(sources) }

    /** Remove a specific [bridgeSession] from the list. */
    fun remove(bridgeSession: BridgeSession) = bridgeSessions.remove(bridgeSession)

    /**
     * Gets a map of each [Bridge] used in one of the [BridgeSession]s to the number of participants on that bridge
     * (for use in bridge selection).
     *
     * TODO: do we actually want the hasFailed check here?
     */
    fun getBridges() = bridgeSessions.filter { !it.hasFailed }.associate { it.bridge to it.participants.size }
}

/**
 * Whether a [BridgeSession] is to be considered "operational" -- if it hasn't failed a request in this conference,
 * and the bridge hasn't been marked as non-operational for other reasons.
 * Note that a bridge may change from non-operational and back to operational, which may change the value of this flag.
 * But if it fails a request in this conference it will forever be considered non-operational.
 */
private fun BridgeSession.isOperational() = !hasFailed && bridge.isOperational
