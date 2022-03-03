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
package org.jitsi.jicofo.bridge;

import edu.umd.cs.findbugs.annotations.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.stats.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jxmpp.jid.*;

import java.time.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.jitsi.jicofo.bridge.BridgeConfig.config;

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
public class Bridge
    implements Comparable<Bridge>
{
    /**
     * How long the "failed" state should be sticky for. Once a {@link Bridge} goes in a non-operational state (via
     * {@link #setIsOperational(boolean)}) it will be considered non-operational for at least this amount of time.
     * See the tests for example behavior.
     */
    private static final Duration failureResetThreshold = config.failureResetThreshold();

    /**
     * The XMPP address of the bridge.
     */
    @NonNull private final Jid jid;

    /**
     * Keep track of the recently added endpoints.
     */
    private final RateTracker newEndpointsRate
            = new RateTracker(config.participantRampupInterval(), Duration.ofMillis(100));

    /**
     * The last reported packet rate in packets per second.
     */
    private int lastReportedPacketRatePps = 0;

    /**
     * The last report stress level
     */
    private double lastReportedStressLevel = 0.0;

    /**
     * For older bridges which don't support reporting their stress level we'll fall back
     * to calculating the stress manually via the packet rate.
     */
    private boolean usePacketRateStatForStress = true;

    /**
     * Holds bridge version (if known - not all bridge version are capable of
     * reporting it).
     */
    private String version = null;

    /**
     * Holds bridge release ID, or null if not known.
     */
    private String releaseId = null;

    /**
     * Whether the bridge supports colibri2.
     */
    private boolean colibri2 = false;

    /**
     * Stores the {@code operational} status of the bridge, which is
     * {@code true} if the bridge has been successfully used by the focus to
     * allocate channels. It is reset to {@code false} when the focus fails
     * to allocate channels, but it gets another chance when all currently
     * working bridges go down and might eventually get elevated back to
     * {@code true}.
     */
    private volatile boolean isOperational = true;

    /**
     * Start out with the configured value, update if the bridge reports a value.
     */
    private double averageParticipantStress = config.averageParticipantStress();

    /**
     * Stores a boolean that indicates whether the bridge is in graceful shutdown mode.
     */
    private boolean shutdownInProgress = false /* we assume it is not shutting down */;

    /**
     * Stores a boolean that indicates whether the bridge is in drain mode.
     */
    private boolean draining = true; /* Default to true to prevent unwanted selection before reading actual state */

    /**
     * The time when this instance has failed.
     *
     * Use {@code null} to represent "never" because calculating the duration from {@link Instant#MIN} is slow.
     */
    @Nullable
    private Instant failureInstant = null;

    private String region = null;
    private String relayId = null;

    @NonNull
    private final Clock clock;

    Bridge(@NonNull Jid jid, @NonNull Clock clock)
    {
        this.jid = jid;
        this.clock = clock;
    }

    Bridge(@NonNull Jid jid)
    {
        this(jid, Clock.systemUTC());
    }

    /**
     * Notifies this instance that a new {@link ColibriStatsExtension} was
     * received for this instance.
     * @param stats the {@link ColibriStatsExtension} instance which was
     * received.
     */
    public void setStats(ColibriStatsExtension stats)
    {
        if (stats == null)
        {
            return;
        }

        Double stressLevel = UtilKt.getDouble(stats, "stress_level");
        if (stressLevel != null)
        {
            lastReportedStressLevel = stressLevel;
            usePacketRateStatForStress = false;
        }
        Double averageParticipantStress = UtilKt.getDouble(stats, "average_participant_stress");
        if (averageParticipantStress != null)
        {
            this.averageParticipantStress = averageParticipantStress;
        }

        Integer packetRateDown = null;
        Integer packetRateUp = null;
        try
        {
            packetRateDown = stats.getValueAsInt(PACKET_RATE_DOWNLOAD);
            packetRateUp = stats.getValueAsInt(PACKET_RATE_UPLOAD);
        }
        catch (NumberFormatException ignored)
        {
        }

        if (packetRateDown != null && packetRateUp != null)
        {
            lastReportedPacketRatePps = packetRateDown + packetRateUp;
        }

        if (Boolean.parseBoolean(stats.getValueAsString(SHUTDOWN_IN_PROGRESS)))
        {
            shutdownInProgress = true;
        }

        String drainStr = stats.getValueAsString(DRAIN);
        if (drainStr != null)
        {
            draining = Boolean.parseBoolean(drainStr);
        }

        if (Boolean.parseBoolean(stats.getValueAsString("colibri2")))
        {
            colibri2 = true;
        }

        String newVersion = stats.getValueAsString(VERSION);
        if (newVersion != null)
        {
            version = newVersion;
        }

        String newReleaseId = stats.getValueAsString(RELEASE);
        if (newReleaseId != null)
        {
            releaseId = newReleaseId;
        }

        String region = stats.getValueAsString(REGION);
        if (region != null)
        {
            this.region = region;
        }

        String relayId = stats.getValueAsString(RELAY_ID);
        if (relayId != null)
        {
            this.relayId = relayId;
        }
    }

    public boolean supportsColibri2()
    {
        return colibri2;
    }

    /**
     * @return the relay ID advertised by the bridge, or {@code null} if
     * none was advertised.
     */
    public String getRelayId()
    {
        return relayId;
    }

    public void setIsOperational(boolean isOperational)
    {
        this.isOperational = isOperational;

        if (!isOperational)
        {
            // Remember when the bridge has last failed
            failureInstant = clock.instant();
        }
    }

    public boolean isOperational()
    {
        // To filter out intermittent failures, do not return operational
        // until past the reset threshold since the last failure.
        if (failureInstant != null &&
                Duration.between(failureInstant, clock.instant()).compareTo(failureResetThreshold) < 0)
        {
            return false;
        }

        return isOperational;
    }

    /**
     * Returns a negative number if this instance is more able to serve conferences than o. For details see
     * {@link #compare(Bridge, Bridge)}.
     *
     * @param o the other bridge instance
     *
     * @return a negative number if this instance is more able to serve conferences than o
     */
    @Override
    public int compareTo(Bridge o)
    {
        return compare(this, o);
    }

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
    public static int compare(Bridge b1, Bridge b2)
    {
        int myPriority = getPriority(b1);
        int otherPriority = getPriority(b2);

        if (myPriority != otherPriority)
        {
            return myPriority - otherPriority;
        }

        return Double.compare(b1.getStress(), b2.getStress());
    }

    private static int getPriority(Bridge b)
    {
        return b.isOperational() ? (b.isInGracefulShutdown() ? 2 : 1) : 3;
    }

    /**
     * Notifies this {@link Bridge} that it was used for a new endpoint.
     */
    public void endpointAdded()
    {
        newEndpointsRate.update(1);
    }

    /**
     * Returns the net number of video channels recently allocated or removed
     * from this bridge.
     */
    private long getRecentlyAddedEndpointCount()
    {
        return newEndpointsRate.getAccumulatedCount();
    }

    public Jid getJid()
    {
        return jid;
    }

    /**
     * Get the version of this bridge (with embedded release ID, if available).
     * @return The version, or null if not known.
     */
    public String getVersion()
    {
        if (version != null && releaseId != null)
            return version + "-" + releaseId;
        else
            return version;
    }

    /**
     * @return the region of this {@link Bridge}.
     */
    public String getRegion()
    {
        return region;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return String.format(
                "Bridge[jid=%s, relayId=%s, region=%s, stress=%.2f]",
                     jid.toString(),
                     getRelayId(),
                     getRegion(),
                     getStress());
    }

    /**
     * Gets the "stress" of the bridge, represented as a double between 0 and 1 (though technically the value
     * can exceed 1).
     * @return this bridge's stress level
     */
    public double getStress()
    {
        if (usePacketRateStatForStress)
        {
            return getStressFromPacketRate();
        }
        // While a stress of 1 indicates a bridge is fully loaded, we allow
        // larger values to keep sorting correctly.
        return (lastReportedStressLevel + Math.max(0, getRecentlyAddedEndpointCount()) * averageParticipantStress);
    }

    /**
     * Returns the "stress" of the bridge. The stress is computed based on the
     * total packet rate reported by the bridge and the video stream diff
     * estimation since the last update from the bridge. Note that this is techincally
     * deprecated and only exists for backwards compatibility with bridges who don't
     * yet support reporting their stress level directly.
     *
     * @return the sum of the last total reported packet rate (in pps) and an
     * estimation of the packet rate of the streams that we estimate that the bridge
     * hasn't reported to Jicofo yet. The estimation is the product of the
     * number of unreported streams and a constant C (which we set to 500 pps).
     */
    private double getStressFromPacketRate()
    {
        // While a stress of 1 indicates a bridge is fully loaded, we allow
        // larger values to keep sorting correctly.
        return (lastReportedPacketRatePps
            + Math.max(0, getRecentlyAddedEndpointCount()) * config.averageParticipantPacketRatePps())
            / (double) config.maxBridgePacketRatePps();
    }

    /**
     * @return true if the stress of the bridge is greater-than-or-equal to the threshold.
     */
    public boolean isOverloaded()
    {
        return getStress() >= config.stressThreshold();
    }

    public double getLastReportedStressLevel()
    {
        return lastReportedStressLevel;
    }

    public boolean isInGracefulShutdown()
    {
        return shutdownInProgress;
    }

    /**
     * @return true if the bridge is currently in drain mode
     */
    public boolean isDraining()
    {
        return draining;
    }

    @NonNull
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject o = new OrderedJsonObject();
        o.put("version", String.valueOf(version));
        o.put("release", String.valueOf(releaseId));
        o.put("stress", getStress());
        o.put("operational", isOperational());
        o.put("packet_rate", lastReportedPacketRatePps);
        o.put("region", String.valueOf(region));
        o.put("drain", isDraining());
        o.put("graceful-shutdown", isInGracefulShutdown());
        o.put("overloaded", isOverloaded());
        o.put("relay-id", String.valueOf(relayId));

        return o;
    }
}
