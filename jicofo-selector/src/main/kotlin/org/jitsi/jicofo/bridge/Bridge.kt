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

    /**
     * Keep track of the recently added endpoints.
     */
    private val newEndpointsRate = RateTracker(
        BridgeConfig.config.participantRampupInterval(),
        Duration.ofMillis(100),
        clock
    )

    /**
     * The last report stress level
     */
    var lastReportedStressLevel = 0.0
        private set

    /**
     * Holds bridge version (if known - not all bridge version are capable of
     * reporting it).
     */
    private var version: String? = null

    /**
     * Whether the last received presence indicated the bridge is healthy.
     */
    var isHealthy = true
        private set

    /**
     * Holds bridge release ID, or null if not known.
     */
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
                Duration.between(failureInstant, clock.instant()).compareTo(failureResetThreshold) < 0
            ) {
                false
            } else field
        set(isOperational) {
            field = isOperational
            if (!isOperational) {
                // Remember when the bridge has last failed
                failureInstant = clock.instant()
            }
        }

    /**
     * Start out with the configured value, update if the bridge reports a value.
     */
    private var averageParticipantStress = BridgeConfig.config.averageParticipantStress()

    /**
     * Stores a boolean that indicates whether the bridge is in graceful shutdown mode.
     */
    var isInGracefulShutdown = false /* we assume it is not shutting down */

    /**
     * Whether the bridge is in SHUTTING_DOWN mode.
     */
    var isShuttingDown = false
        private set

    /**
     * @return true if the bridge is currently in drain mode
     */
    /**
     * Stores a boolean that indicates whether the bridge is in drain mode.
     */
    var isDraining = true /* Default to true to prevent unwanted selection before reading actual state */
        private set

    /**
     * The time when this instance has failed.
     *
     * Use `null` to represent "never" because calculating the duration from [Instant.MIN] is slow.
     */
    private var failureInstant: Instant? = null

    /**
     * @return the region of this [Bridge].
     */
    var region: String? = null
        private set

    /**
     * @return the relay ID advertised by the bridge, or `null` if
     * none was advertised.
     */
    var relayId: String? = null
        private set

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
        } else if (BridgeConfig.config.usePresenceForHealth) {
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

    /**
     * Notifies this [Bridge] that it was used for a new endpoint.
     */
    fun endpointAdded() {
        newEndpointsRate.update(1)
    }

    /**
     * Returns the net number of video channels recently allocated or removed
     * from this bridge.
     */
    private val recentlyAddedEndpointCount: Long
        get() = newEndpointsRate.getAccumulatedCount()

    /**
     * The version of this bridge (with embedded release ID, if available).
     */
    val fullVersion: String?
        get() = if (version != null && releaseId != null) "$version-$releaseId" else version

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return String.format(
            "Bridge[jid=%s, version=%s, relayId=%s, region=%s, stress=%.2f]",
            jid.toString(),
            fullVersion,
            relayId,
            region,
            stress
        )
    }

    /**
     * Gets the "stress" of the bridge, represented as a double between 0 and 1 (though technically the value
     * can exceed 1).
     * @return this bridge's stress level
     */
    val stress: Double
        get() =
            // While a stress of 1 indicates a bridge is fully loaded, we allow
            // larger values to keep sorting correctly.
            lastReportedStressLevel +
                recentlyAddedEndpointCount.coerceAtLeast(0) * averageParticipantStress

    /**
     * @return true if the stress of the bridge is greater-than-or-equal to the threshold.
     */
    val isOverloaded: Boolean
        get() = stress >= BridgeConfig.config.stressThreshold()

    val debugState: OrderedJsonObject
        get() {
            val o = OrderedJsonObject()
            o["version"] = version.toString()
            o["release"] = releaseId.toString()
            o["stress"] = stress
            o["operational"] = isOperational
            o["region"] = region.toString()
            o["drain"] = isDraining
            o["graceful-shutdown"] = isInGracefulShutdown
            o["shutting-down"] = isShuttingDown
            o["overloaded"] = isOverloaded
            o["relay-id"] = relayId.toString()
            o["healthy"] = isHealthy
            return o
        }

    companion object {
        /**
         * How long the "failed" state should be sticky for. Once a [Bridge] goes in a non-operational state (via
         * [.setIsOperational]) it will be considered non-operational for at least this amount of time.
         * See the tests for example behavior.
         */
        private val failureResetThreshold = BridgeConfig.config.failureResetThreshold()

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
            } else b1.stress.compareTo(b2.stress)
        }

        private fun getPriority(b: Bridge): Int {
            return if (b.isOperational) { if (b.isInGracefulShutdown) 2 else 1 } else 3
        }
    }
}
