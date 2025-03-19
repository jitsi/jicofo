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
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.concurrent.CustomizableThreadFactory
import org.jitsi.utils.event.AsyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.json.simple.JSONObject
import org.jxmpp.jid.Jid
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

    /** The bridge selection strategy. */
    private val bridgeSelectionStrategy = BridgeConfig.config.selectionStrategy.also {
        logger.info("Using ${it.javaClass.name}")
    }

    /** The map of bridge JID to <tt>Bridge</tt>. */
    private val bridges: MutableMap<Jid, Bridge> = ConcurrentHashMap()

    /** Get the [Bridge] with a specific JID or null */
    fun get(jid: Jid) = bridges[jid]

    init {
        JicofoMetricsContainer.instance.metricsUpdater.addUpdateTask { updateMetrics() }
    }

    fun hasNonOverloadedBridge(): Boolean = bridges.values.any { !it.isOverloaded }
    fun getAll(): List<Bridge> = bridges.values.toList()

    val operationalBridgeCount: Int
        @Synchronized
        get() = bridges.values.count { it.isOperational && !it.isInGracefulShutdown }

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
        val wasShutingDown = it.isShuttingDown
        it.setStats(stats)
        if (!wasShutingDown && it.isShuttingDown) {
            logger.info("${it.jid} entered SHUTTING_DOWN")
            eventEmitter.fireEvent { bridgeIsShuttingDown(it) }
        }
        return it
    } ?: Bridge(bridgeJid, clock).also { newBridge ->
        if (stats != null) {
            newBridge.setStats(stats)
        }
        logger.info("Added new videobridge: $newBridge")
        bridges[bridgeJid] = newBridge
        bridgeCount.inc()
        eventEmitter.fireEvent { bridgeAdded(newBridge) }
    }

    /**
     * Removes a [Bridge] with a specific JID from the list of videobridge instances.
     *
     * @param bridgeJid the JID of bridge to remove.
     */
    @Synchronized
    fun removeJvbAddress(bridgeJid: Jid) {
        logger.info("Removing JVB: $bridgeJid")
        bridges.remove(bridgeJid)?.let {
            if (!it.isInGracefulShutdown && !it.isShuttingDown) {
                logger.warn("Lost a bridge: $bridgeJid")
                lostBridges.inc()
            }
            it.markRemoved()
            bridgeCount.dec()
            eventEmitter.fireEvent { bridgeRemoved(it) }
        }
    }

    override fun healthCheckPassed(bridgeJid: Jid) {
        bridges[bridgeJid]?.isOperational = true
    }

    override fun healthCheckFailed(bridgeJid: Jid) = bridges[bridgeJid]?.let {
        // When a bridge returns a non-healthy status, we mark it as non-operational AND we move all conferences
        // away from it.
        it.isOperational = false
        eventEmitter.fireEvent { bridgeFailedHealthCheck(it) }
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
        it.isOperational = false
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
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties> = emptyMap(),
        participantProperties: ParticipantProperties = ParticipantProperties(),
        /**
         * A specific jitsi-videobridge version to use, or null to use any version. If conferenceBridges is non-empty
         * the version needs to match the version of the existing bridges.
         * */
        version: String? = null
    ): Bridge? {
        var v = conferenceBridges.keys.firstOrNull()?.fullVersion
        if (v == null) {
            v = version
        } else if (version != null && version != v) {
            logger.warn("An inconsistent version was requested: $version. Conference is using version: $v")
            return null
        }

        // the list of all known videobridges JIDs ordered by load and *operational* status.
        val prioritizedBridges = synchronized(this) { ArrayList(bridges.values) }
        prioritizedBridges.sort()

        var candidateBridges = prioritizedBridges.filter { it.isOperational }.toList()
        if (candidateBridges.isEmpty()) {
            logger.warn("There are no operational bridges.")
            return null
        }

        candidateBridges = candidateBridges.filter { !it.isShuttingDown }.toList()
        if (candidateBridges.isEmpty()) {
            logger.warn("All operational bridges are SHUTTING_DOWN")
            return null
        }

        if (v != null && !OctoConfig.config.allowMixedVersions) {
            candidateBridges = candidateBridges.filter { it.fullVersion == v }
            if (candidateBridges.isEmpty()) {
                logger.warn("There are no bridges with the required version: $v")
                return null
            }
        }

        // If there are active bridges, prefer those.
        val activeBridges = candidateBridges.filter { !it.isDraining }.toList()
        if (!activeBridges.isEmpty()) {
            candidateBridges = activeBridges
        }

        // If there are bridges not shutting down, prefer those.
        val runningBridges = candidateBridges.filter { !it.isInGracefulShutdown }.toList()
        if (!runningBridges.isEmpty()) {
            candidateBridges = runningBridges
        }

        return bridgeSelectionStrategy.select(
            candidateBridges,
            conferenceBridges,
            participantProperties,
            OctoConfig.config.enabled
        )
    }

    val stats: JSONObject
        @Synchronized
        get() = JSONObject().apply {
            // We want to avoid exposing unnecessary hierarchy levels in the stats,
            // so we'll merge stats from different "child" objects here.
            this["bridge_count"] = bridgeCount.get()
            this["operational_bridge_count"] = operationalBridgeCountMetric.get()
            this["in_shutdown_bridge_count"] = inShutdownBridgeCountMetric.get()
            this["lost_bridges"] = lostBridges.get()
            this["bridge_version_count"] = bridgeVersionCount.get()
        }

    val debugState: OrderedJsonObject
        @Synchronized
        get() = OrderedJsonObject().apply {
            this["strategy"] = bridgeSelectionStrategy.javaClass.simpleName
            this["bridge"] = OrderedJsonObject().apply {
                bridges.values.forEach { put(it.jid.toString(), it.debugState) }
            }
        }

    fun updateMetrics() {
        inShutdownBridgeCountMetric.set(bridges.values.count { it.isInGracefulShutdown }.toLong())
        operationalBridgeCountMetric.set(bridges.values.count { it.isOperational }.toLong())
        bridgeVersionCount.set(bridges.values.map { it.fullVersion }.toSet().size.toLong())
        bridges.values.forEach { it.updateMetrics() }
    }

    companion object {
        @JvmField
        val lostBridges = JicofoMetricsContainer.instance.registerCounter(
            "bridge_selector_lost_bridges",
            "Number of bridges which disconnected unexpectedly."
        )

        @JvmField
        val bridgeCount = JicofoMetricsContainer.instance.registerLongGauge(
            "bridge_selector_bridge_count",
            "The current number of bridges"
        )
        val operationalBridgeCountMetric = JicofoMetricsContainer.instance.registerLongGauge(
            "bridge_selector_bridge_count_operational",
            "The current number of operational bridges"
        )
        val inShutdownBridgeCountMetric = JicofoMetricsContainer.instance.registerLongGauge(
            "bridge_selector_bridge_count_in_shutdown",
            "The current number of bridges in graceful shutdown"
        )
        val bridgeVersionCount = JicofoMetricsContainer.instance.registerLongGauge(
            "bridge_selector_bridge_version_count",
            "The current number of different bridge versions"
        )
    }

    interface EventHandler {
        fun bridgeRemoved(bridge: Bridge)
        fun bridgeAdded(bridge: Bridge)
        fun bridgeFailedHealthCheck(bridge: Bridge)
        fun bridgeIsShuttingDown(bridge: Bridge) {}
    }
}

data class ConferenceBridgeProperties(
    val participantCount: Int,
    val visitor: Boolean = false
)

data class ParticipantProperties(
    val region: String? = null,
    val visitor: Boolean = false
)
