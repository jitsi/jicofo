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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.stats.RateTracker
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jxmpp.jid.Jid
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import org.jitsi.jicofo.bridge.BridgeConfig.Companion.config as config

/**
 * Represents a jitsi-videobridge instance, reachable at a certain JID, which
 * can be used by jicofo for hosting conferences. Contains the state related
 * to the jitsi-videobridge instance, such as numbers of channels and streams,
 * the region in which the instance resides, etc.
 *
 * TODO fix comparator (should not be reflexive unless the objects are the same?)
 * @author Pawel Domas
 * @author Boris Grozev
 */
@SuppressFBWarnings("EQ_COMPARETO_USE_OBJECT_EQUALS")
class Bridge @JvmOverloads internal constructor(
    /**
     * The XMPP address of the bridge.
     */
    val jid: Jid,
    private val clock: Clock = Clock.systemUTC()
) : Comparable<Bridge> {

    /** Keep track of the recently added endpoints. */
    private val newEndpointsRate = RateTracker(
        config.participantRampupInterval,
        Duration.ofMillis(100),
        clock
    )

    private val endpointRestartRequestRate = RateTracker(
        config.iceFailureDetection.interval,
        Duration.ofSeconds(1),
        clock
    )

    /** Number of endpoints currently allocated on this bridge by this jicofo instance. */
    val endpoints = AtomicInteger(0)

    /** The last report stress level */
    var lastReportedStressLevel = 0.0
        private set

    /** Holds bridge version (if known - not all bridge version are capable of reporting it). */
    private var version: String? = null

    /** Whether the last received presence indicated the bridge is healthy. */
    var isHealthy = true
        private set

    /** Holds bridge release ID, or null if not known. */
    private var releaseId: String? = null

    /**
     * Stores the `operational` status of the bridge, which is
     * `true` if the bridge has been successfully used by the focus to
     * allocate channels. It is reset to `false` when the focus fails
     * to allocate channels, but it gets another chance when all currently
     * working bridges go down and might eventually get elevated back to
     * `true`.
     */
    @Volatile
    var isOperational = true
        get() =
            // To filter out intermittent failures, do not return operational
            // until past the reset threshold since the last failure.
            if (failureInstant != null &&
                Duration.between(failureInstant, clock.instant()).compareTo(config.failureResetThreshold) < 0
            ) {
                false
            } else {
                field
            }
        set(isOperational) {
            field = isOperational
            if (!isOperational) {
                // Remember when the bridge has last failed
                failureInstant = clock.instant()
            }
        }

    /** Start out with the configured value, update if the bridge reports a value. */
    private var averageParticipantStress = config.averageParticipantStress

    /** Stores a boolean that indicates whether the bridge is in graceful shutdown mode. */
    var isInGracefulShutdown = false // we assume it is not shutting down

    /** Whether the bridge is in SHUTTING_DOWN mode. */
    var isShuttingDown = false
        private set

    /**
     * Stores a boolean that indicates whether the bridge is in drain mode.
     */
    var isDraining = true // Default to true to prevent unwanted selection before reading actual state
        private set

    /**
     * The time when this instance has failed.
     *
     * Use `null` to represent "never" because calculating the duration from [Instant.MIN] is slow.
     */
    private var failureInstant: Instant? = null

    /** @return the region of this [Bridge]. */
    var region: String? = null
        private set

    /** @return the relay ID advertised by the bridge, or `null` if none was advertised. */
    var relayId: String? = null
        private set

    /**
     * If this [Bridge] has been removed from the list of bridges. Once removed, the metrics specific to this instance
     *  are cleared and no longer emitted. If the bridge re-connects, a new [Bridge] instance will be created.
     */
    val removed = AtomicBoolean(false)

    /**
     * The last instant at which we detected, based on restart requests from endpoints, that this bridge is failing ICE
     */
    private var lastIceFailed = Instant.MIN
    private val failingIce: Boolean
        get() = Duration.between(lastIceFailed, clock.instant()) < config.iceFailureDetection.timeout

    private val logger: Logger = LoggerImpl(Bridge::class.java.name)

    init {
        logger.addContext("jid", jid.toString())
    }

    private var lastPresenceReceived = Instant.MIN

    val timeSinceLastPresence: Duration
        get() = Duration.between(lastPresenceReceived, clock.instant())

    /**
     * Notifies this instance that a new [ColibriStatsExtension] was
     * received for this instance.
     * @param stats the [ColibriStatsExtension] instance which was
     * received.
     */
    fun setStats(stats: ColibriStatsExtension?) {
        if (stats == null) {
            return
        }
        lastPresenceReceived = clock.instant()
        val stressLevel = stats.getDouble("stress_level")
        if (stressLevel != null) {
            lastReportedStressLevel = stressLevel
        }
        val averageParticipantStress = stats.getDouble("average_participant_stress")
        if (averageParticipantStress != null) {
            this.averageParticipantStress = averageParticipantStress
        }
        if (java.lang.Boolean.parseBoolean(
                stats.getValueAsString(
                    ColibriStatsExtension.SHUTDOWN_IN_PROGRESS
                )
            )
        ) {
            isInGracefulShutdown = true
        }
        if (java.lang.Boolean.parseBoolean(stats.getValueAsString("shutting_down"))) {
            isShuttingDown = true
        }
        val drainStr = stats.getValueAsString(ColibriStatsExtension.DRAIN)
        if (drainStr != null) {
            isDraining = java.lang.Boolean.parseBoolean(drainStr)
        }
        val newVersion = stats.getValueAsString(ColibriStatsExtension.VERSION)
        if (newVersion != null) {
            version = newVersion
        }
        val newReleaseId = stats.getValueAsString(ColibriStatsExtension.RELEASE)
        if (newReleaseId != null) {
            releaseId = newReleaseId
        }
        val region = stats.getValueAsString(ColibriStatsExtension.REGION)
        if (region != null) {
            this.region = region
        }
        val relayId = stats.getValueAsString(ColibriStatsExtension.RELAY_ID)
        if (relayId != null) {
            this.relayId = relayId
        }
        val healthy = stats.getValueAsString("healthy")
        if (healthy != null) {
            isHealthy = java.lang.Boolean.parseBoolean(healthy)
        } else if (config.usePresenceForHealth) {
            logger.warn(
                "Presence-based health checks are enabled, but presence did not include health status. Health " +
                    "checks for this bridge are effectively disabled."
            )
        }
    }

    /**
     * Returns a negative number if this instance is more able to serve conferences than o. For details see
     * [.compare].
     *
     * @param other the other bridge instance
     *
     * @return a negative number if this instance is more able to serve conferences than o
     */
    override fun compareTo(other: Bridge): Int {
        return compare(this, other)
    }

    /** Notifies this [Bridge] that it was used for a new endpoint. */
    fun endpointAdded() {
        newEndpointsRate.update(1)
        endpoints.incrementAndGet()
        if (!removed.get()) {
            BridgeMetrics.endpoints.set(endpoints.get().toLong(), listOf(jid.resourceOrEmpty.toString()))
        }
    }

    /** Updates the "endpoints moved" metric for this bridge. */
    fun endpointsMoved(count: Long) {
        BridgeMetrics.endpointsMoved.add(count, listOf(jid.resourceOrEmpty.toString()))
    }
    fun endpointRemoved() = endpointsRemoved(1)
    fun endpointsRemoved(count: Int) {
        endpoints.addAndGet(-count)
        if (!removed.get()) {
            BridgeMetrics.endpoints.set(endpoints.get().toLong(), listOf(jid.resourceOrEmpty.toString()))
        }
        if (endpoints.get() < 0) {
            logger.error("Removed more endpoints than were allocated. Resetting to 0.", Throwable())
            endpoints.set(0)
        }
    }
    internal fun markRemoved() {
        if (removed.compareAndSet(false, true)) {
            BridgeMetrics.restartRequestsMetric.remove(listOf(jid.resourceOrEmpty.toString()))
            BridgeMetrics.endpoints.remove(listOf(jid.resourceOrEmpty.toString()))
            BridgeMetrics.failingIce.remove(listOf(jid.resourceOrEmpty.toString()))
            BridgeMetrics.endpointsMoved.remove(listOf(jid.resourceOrEmpty.toString()))
        }
    }
    internal fun updateMetrics() {
        if (!removed.get()) {
            BridgeMetrics.failingIce.set(failingIce, listOf(jid.resourceOrEmpty.toString()))
        }
    }

    fun endpointRequestedRestart() {
        endpointRestartRequestRate.update(1)
        if (!removed.get()) {
            BridgeMetrics.restartRequestsMetric.inc(listOf(jid.resourceOrEmpty.toString()))
        }

        if (config.iceFailureDetection.enabled) {
            val restartCount = endpointRestartRequestRate.getAccumulatedCount()
            val endpoints = endpoints.get()
            if (endpoints >= config.iceFailureDetection.minEndpoints &&
                restartCount > endpoints * config.iceFailureDetection.threshold
            ) {
                // Reset the timeout regardless of the previous state, but only log if the state changed.
                if (!failingIce) {
                    logger.info("Detected an ICE failing state.")
                }
                lastIceFailed = clock.instant()
            }
        }
    }

    /** Returns the net number of video channels recently allocated or removed from this bridge. */
    private val recentlyAddedEndpointCount: Long
        get() = newEndpointsRate.getAccumulatedCount()

    /** The version of this bridge (with embedded release ID, if available). */
    val fullVersion: String?
        get() = if (version != null && releaseId != null) "$version-$releaseId" else version

    override fun toString(): String {
        return String.format(
            "Bridge[jid=%s, version=%s, relayId=%s, region=%s, correctedStress=%.2f]",
            jid.toString(),
            fullVersion,
            relayId,
            region,
            correctedStress
        )
    }

    /**
     * Gets the "stress" of the bridge, represented as a double between 0 and 1 (though technically the value
     * can exceed 1).
     * @return this bridge's stress level
     */
    val correctedStress: Double
        get() {
            // Correct for recently added endpoints.
            // While a stress of 1 indicates a bridge is fully loaded, we allow larger values to keep sorting correctly.
            val s = lastReportedStressLevel + recentlyAddedEndpointCount.coerceAtLeast(0) * averageParticipantStress

            // Correct for failing ICE.
            return if (failingIce) max(s, config.stressThreshold + 0.01) else s
        }

    /** @return true if the stress of the bridge is greater-than-or-equal to the threshold. */
    val isOverloaded: Boolean
        get() = correctedStress >= config.stressThreshold

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["corrected-stress"] = correctedStress
            this["drain"] = isDraining
            this["endpoints"] = endpoints.get()
            this["endpoint-restart-requests"] = endpointRestartRequestRate.getAccumulatedCount()
            this["failing-ice"] = failingIce
            this["graceful-shutdown"] = isInGracefulShutdown
            this["healthy"] = isHealthy
            this["operational"] = isOperational
            this["overloaded"] = isOverloaded
            this["region"] = region.toString()
            this["relay-id"] = relayId.toString()
            this["release"] = releaseId.toString()
            this["shutting-down"] = isShuttingDown
            this["stress"] = lastReportedStressLevel
            this["version"] = version.toString()
        }

    companion object {

        /**
         * Returns a negative number if b1 is more able to serve conferences than b2. The computation is based on the
         * following three comparisons
         *
         * operating bridges < non operating bridges
         * not in graceful shutdown mode < bridges in graceful shutdown mode
         * lower stress < higher stress
         *
         * @param b1 the 1st bridge instance
         * @param b2 the 2nd bridge instance
         *
         * @return a negative number if b1 is more able to serve conferences than b2
         */
        fun compare(b1: Bridge, b2: Bridge): Int {
            val myPriority = getPriority(b1)
            val otherPriority = getPriority(b2)
            return if (myPriority != otherPriority) {
                myPriority - otherPriority
            } else {
                b1.correctedStress.compareTo(b2.correctedStress)
            }
        }

        private fun getPriority(b: Bridge): Int {
            return if (b.isOperational) {
                if (b.isInGracefulShutdown) 2 else 1
            } else {
                3
            }
        }
    }
}
