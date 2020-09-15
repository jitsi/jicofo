/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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

import org.jitsi.utils.stats.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jxmpp.jid.*;

import java.util.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.jitsi.jicofo.bridge.BridgeConfig.config;

/**
 * Represents a jitsi-videobridge instance, reachable at a certain JID, which
 * can be used by jicofo for hosting conferences. Contains the state related
 * to the jitsi-videobridge instance, such as numbers of channels and streams,
 * the region in which the instance resides, etc.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class Bridge
    implements Comparable<Bridge>
{
    /**
     * A {@link ColibriStatsExtension} instance with no stats.
     */
    private static final ColibriStatsExtension EMPTY_STATS
        = new ColibriStatsExtension();

    /**
     * This is static for the purposes of tests.
     * TODO: just use the config and port the tests.
     */
    private static long failureResetThreshold = config.failureResetThreshold().toMillis();

    static void setFailureResetThreshold(long newValue)
    {
        failureResetThreshold = newValue;
    }

    /**
     * The XMPP address of the bridge.
     */
    private final Jid jid;

    /**
     * Keep track of the recently allocated or removed channels.
     */
    private final RateStatistics newEndpointsRate = new RateStatistics(10000);

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
     * The version of Octo that this bridge supports. This is set to the default
     * version (0) which will be assumed if the bridge does not explicitly
     * advertise a version.
     */
    private int octoVersion = 0;

    /**
     * Stores the {@code operational} status of the bridge, which is
     * {@code true} if the bridge has been successfully used by the focus to
     * allocate channels. It is reset to {@code false} when the focus fails
     * to allocate channels, but it gets another chance when all currently
     * working bridges go down and might eventually get elevated back to
     * {@code true}.
     */
    private volatile boolean isOperational
        = true /* we assume it is operational */;

    /**
     * The time when this instance has failed.
     */
    private volatile long failureTimestamp;

    /**
     * The last known {@link ColibriStatsExtension} reported by this bridge.
     */
    private ColibriStatsExtension stats = EMPTY_STATS;

    Bridge(Jid jid)
    {
        this.jid = Objects.requireNonNull(jid, "jid");
    }

    /**
     * Notifies this instance that a new {@link ColibriStatsExtension} was
     * received for this instance.
     * @param stats the {@link ColibriStatsExtension} instance which was
     * received.
     */
    void setStats(ColibriStatsExtension stats)
    {
        if (stats == null)
        {
            this.stats = EMPTY_STATS;
        }
        else
        {
            this.stats = ColibriStatsExtension.clone(stats);
        }
        stats = this.stats;

        double stressLevel;
        String stressLevelStr = stats.getValueAsString("stress_level");
        if (stressLevelStr != null)
        {
            try
            {
                stressLevel = Double.parseDouble(stressLevelStr);
                lastReportedStressLevel = stressLevel;
                usePacketRateStatForStress = false;
            }
            catch (Exception ignored)
            {
            }
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

        // FIXME graceful shutdown should be treated separately from
        //  "operational". When jvb is in graceful shutdown it does not allow
        //  any new conferences but it allows to add participants to
        //  the existing ones. Marking a bridge not operational while in
        //  graceful shutdown will move the conference as soon as any new
        //  participant joins and that is not very graceful.
        if (Boolean.parseBoolean(stats.getValueAsString(SHUTDOWN_IN_PROGRESS)))
        {
            setIsOperational(false);
        }

        String newVersion = stats.getValueAsString(VERSION);
        if (newVersion != null)
        {
            version = newVersion;
        }

        Integer octoVersion = stats.getValueAsInt("octo_version");
        if (octoVersion != null)
        {
            this.octoVersion = octoVersion;
        }
    }

    /**
     * @return the relay ID advertised by the bridge, or {@code null} if
     * none was advertised.
     */
    public String getRelayId()
    {
        return stats.getValueAsString(RELAY_ID);
    }

    public void setIsOperational(boolean isOperational)
    {
        this.isOperational = isOperational;

        if (!isOperational)
        {
            // Remember when the bridge has last failed
            failureTimestamp = System.currentTimeMillis();
        }
    }

    public boolean isOperational()
    {
        // To filter out intermittent failures, do not return operational
        // until past the reset threshold since the last failure.
        if (System.currentTimeMillis() - failureTimestamp
                < failureResetThreshold)
        {
            return false;
        }

        return isOperational;
    }

    /**
     * The least value is returned the least the bridge is loaded. Currently
     * we use the bitrate to estimate load.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public int compareTo(Bridge o)
    {
        boolean meOperational = isOperational();
        boolean otherOperational = o.isOperational();

        if (meOperational && !otherOperational)
        {
            return -1;
        }
        else if (!meOperational && otherOperational)
        {
            return 1;
        }

        return Double.compare(this.getStress(), o.getStress());
    }

    /**
     * Notifies this {@link Bridge} that it was used for a new endpoint.
     */
    public void endpointAdded()
    {
        newEndpointsRate.update(1, System.currentTimeMillis());
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

    public String getVersion()
    {
        return version;
    }

    /**
     * @return the region of this {@link Bridge}.
     */
    public String getRegion()
    {
        return stats.getValueAsString(REGION);
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
        return (lastReportedStressLevel +
            Math.max(0, getRecentlyAddedEndpointCount()) * config.getAverageParticipantStress());
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
        double stress =
            (lastReportedPacketRatePps
                + Math.max(0, getRecentlyAddedEndpointCount()) * config.averageParticipantPacketRatePps())
                / (double) config.maxBridgePacketRatePps();
        // While a stress of 1 indicates a bridge is fully loaded, we allow
        // larger values to keep sorting correctly.
        return stress;
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

    public int getOctoVersion()
    {
        return octoVersion;
    }
}
