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
package org.jitsi.jicofo.bridge

import org.jitsi.jicofo.OctoConfig
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.concurrent.CustomizableThreadFactory
import org.jitsi.utils.event.AsyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.json.simple.JSONObject
import org.jxmpp.jid.Jid
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Class exposes methods for selecting best videobridge from all currently
 * available.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
class BridgeSelector @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC()
) : HealthCheckListener {
    private val logger = createLogger()

    /**
     * TODO: Refactor to use a common executor.
     */
    private val eventEmitterExecutor =
        Executors.newSingleThreadExecutor(CustomizableThreadFactory("BridgeSelector-AsyncEventEmitter", false))
    private val eventEmitter = AsyncEventEmitter<EventHandler>(eventEmitterExecutor)
    fun addHandler(eventHandler: EventHandler) = eventEmitter.addHandler(eventHandler)
    fun removeHandler(eventHandler: EventHandler) = eventEmitter.removeHandler(eventHandler)

    /**
     * The bridge selection strategy.
     */
    private val bridgeSelectionStrategy = BridgeConfig().selectionStrategy.also {
        logger.info("Using ${it.javaClass.name}")
    }

    /**
     * The map of bridge JID to <tt>Bridge</tt>.
     */
    private val bridges: MutableMap<Jid, Bridge> = mutableMapOf()

    val bridgeCount: Int
        @Synchronized
        get() = bridges.size

    val operationalBridgeCount: Int
        @Synchronized
        get() = bridges.values.count { it.isOperational && !it.isInGracefulShutdown }

    /**
     * The number of bridges which disconnected without going into graceful shutdown first.
     */
    private val lostBridges = AtomicInteger()

    /**
     * Adds a bridge to this selector, or if a bridge with the given JID
     * already exists updates its stats.
     *
     * @param bridgeJid the JID of videobridge to be added to this selector's
     * set of videobridges.
     * @param stats the last reported statistics
     * @return the [Bridge] instance for the given JID.
     */
    @JvmOverloads
    @Synchronized
    fun addJvbAddress(bridgeJid: Jid, stats: ColibriStatsExtension? = null): Bridge = bridges[bridgeJid]?.let {
        it.setStats(stats)
        return it
    } ?: Bridge(bridgeJid, clock).also { newBridge ->
        logger.info("Added new videobridge: $newBridge")
        if (stats != null) {
            newBridge.setStats(stats)
        }
        bridges[bridgeJid] = newBridge
        eventEmitter.fireEvent { bridgeAdded(newBridge) }
    }

    /**
     * Removes Jitsi Videobridge XMPP address from the list videobridge
     * instances available in the system .
     *
     * @param bridgeJid the JID of videobridge to be removed from this selector's
     * set of videobridges.
     */
    @Synchronized
    fun removeJvbAddress(bridgeJid: Jid) {
        logger.info("Removing JVB: $bridgeJid")
        bridges.remove(bridgeJid)?.let {
            if (it.isInGracefulShutdown) {
                lostBridges.incrementAndGet()
            }
            eventEmitter.fireEvent { bridgeRemoved(it) }
        }
    }

    override fun healthCheckPassed(bridgeJid: Jid) = bridges[bridgeJid]?.setIsOperational(true) ?: Unit
    override fun healthCheckFailed(bridgeJid: Jid) = bridges[bridgeJid]?.let {
        // When a bridge returns a non-healthy status, we mark it as non-operational AND we move all conferences
        // away from it.
        it.setIsOperational(false)
        eventEmitter.fireEvent { bridgeRemoved(it) }
    } ?: Unit

    override fun healthCheckTimedOut(bridgeJid: Jid) = bridges[bridgeJid]?.let {
        // We are more lenient when a health check times out as opposed to failing with an error. We mark it as
        // non-operational to prevent new conferences being allocated there, but do not move existing conferences
        // away from it (which is what `notifyBridgeDown` would trigger).
        //
        // The reason for this is to better handle the case of an intermittent network failure between the bridge
        // and jicofo that does not affect the endpoints. In this case a conference will be moved away from the
        // bridge if and when a request for that conference fails or times out. This prevents unnecessary moves when
        // the bridge eventually recovers (the XMPP/MUC disconnect takes much longer than a health check timing
        // out), and prevents a burst of requests due to all conferences being moved together (this is especially
        // bad when multiple bridges experience network problems, and conference from one failing bridge are
        // attempted to be moved to another failing bridge).
        // The other possible case is that the bridge is not responding to jicofo, and is also unavailable to
        // endpoints. In this case we rely on endpoints reporting ICE failures to jicofo, which then trigger a move.
        it.setIsOperational(false)
    } ?: Unit

    /**
     * Selects a bridge to be used for a specific new [Participant] of
     * a specific [JitsiMeetConference].
     *
     * @return the selected bridge, represented by its [Bridge].
     * @param conferenceBridges the bridges in the conference mapped to the number of participants on each.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     */
    @Synchronized
    @JvmOverloads
    fun selectBridge(
        conferenceBridges: Map<Bridge, Int> = emptyMap(),
        participantRegion: String? = null,
        /**
         * A specific jitsi-videobridge version to use, or null to use any version. If conferenceBridges is non-empty
         * the version needs to match the version of the existing bridges.
         * */
        version: String? = null
    ): Bridge? {
        // the list of all known videobridges JIDs ordered by load and *operational* status.
        val prioritizedBridges = synchronized(this) { ArrayList(bridges.values) }
        prioritizedBridges.sort()

        var candidateBridges = prioritizedBridges
            .filter { it.isOperational && !it.isInGracefulShutdown }
            .toList()

        // if there's no candidate bridge, we include bridges that are in graceful shutdown mode
        // (the alternative is to crash the user)
        if (candidateBridges.isEmpty()) {
            candidateBridges = prioritizedBridges.filter { it.isOperational }.toList()
        }

        val v = version ?: conferenceBridges.keys.firstOrNull()?.version
        val candidateBridgesMatchingVersion = if (v == null) candidateBridges else
            candidateBridges.filter { it.version == v }
        if (candidateBridges.isNotEmpty() && candidateBridgesMatchingVersion.isEmpty()) {
            logger.warn("There are available bridges, but none with the required version: $v")
        }

        if (candidateBridgesMatchingVersion.isEmpty()) return null

        return bridgeSelectionStrategy.select(
            candidateBridgesMatchingVersion,
            conferenceBridges,
            participantRegion,
            OctoConfig.config.enabled
        )
    }

    val stats: JSONObject
        @Synchronized
        get() = bridgeSelectionStrategy.stats.apply {
            // We want to avoid exposing unnecessary hierarchy levels in the stats,
            // so we'll merge stats from different "child" objects here.
            this["bridge_count"] = bridgeCount
            this["operational_bridge_count"] = bridges.values.count { it.isOperational }
            this["in_shutdown_bridge_count"] = bridges.values.count { it.isInGracefulShutdown }
            this["lost_bridges"] = lostBridges.get()
        }

    val debugState: OrderedJsonObject
        @Synchronized
        get() = OrderedJsonObject().apply {
            this["strategy"] = bridgeSelectionStrategy.javaClass.simpleName
            this["bridge"] = OrderedJsonObject().apply {
                bridges.values.forEach { put(it.jid.toString(), it.debugState) }
            }
        }

    interface EventHandler {
        fun bridgeRemoved(bridge: Bridge)
        fun bridgeAdded(bridge: Bridge)
    }
}
