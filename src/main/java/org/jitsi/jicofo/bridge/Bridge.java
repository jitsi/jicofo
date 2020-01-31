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

import org.jitsi.xmpp.extensions.colibri.*;
import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

import java.util.*;

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
     * The {@link Logger} used by the {@link Bridge} class and its instances.
     */
    private static final Logger logger = Logger.getLogger(Bridge.class);

    /**
     * A {@link ColibriStatsExtension} instance with no stats.
     */
    private static final ColibriStatsExtension EMPTY_STATS
        = new ColibriStatsExtension();

    /***
     * TBD
     */
    private static final int RECOMMENDED_MAX_VIDEO_STREAMS = -1;

    /***
     * TBD
     */
    private static final int RECOMMENDED_MAX_BITRATE = -1;

    /**
     * The parent {@link BridgeSelector}.
     */
    private BridgeSelector bridgeSelector;

    /**
     * The XMPP address of the bridge.
     */
    private final Jid jid;

    /**
     * How many video streams are there on the bridge (as reported by the bridge
     * itself).
     */
    private int videoStreamCount = 0;

    /**
     * Accumulates video stream count changes coming from
     * {@link BridgeEvent#VIDEOSTREAMS_CHANGED} in order to estimate video
     * stream count on the bridge. The value is included in the result
     * returned by {@link #getEstimatedVideoStreamCount()} if not
     * <tt>null</tt>.
     *
     * Is is set back to <tt>null</tt> when new value from the bridge
     * arrives.
     */
    private int videoStreamCountDiff = 0;

    /**
     * The last reported bitrate in Kbps.
     */
    private int lastReportedBitrateKbps = 0;

    /**
     * Holds bridge version (if known - not all bridge version are capable of
     * reporting it).
     */
    private String version = null;

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

        Integer videoStreamCount = stats.getValueAsInt(VIDEO_STREAMS);
        if (videoStreamCount != null)
        {
            // We have extra logic for keeping track of the number of video
            // streams.
            setVideoStreamCount(videoStreamCount);
        }

        Integer bitrateUpKbps = null;
        Integer bitrateDownKbps = null;
        Integer octoReceiveBitrate = null;
        Integer octoSendBitrate = null;
        try
        {
            bitrateUpKbps = stats.getValueAsInt(BITRATE_UPLOAD);
            bitrateDownKbps = stats.getValueAsInt(BITRATE_DOWNLOAD);
            octoReceiveBitrate
                    = stats.getValueAsInt(OCTO_RECEIVE_BITRATE);
            octoSendBitrate = stats.getValueAsInt(OCTO_SEND_BITRATE);
        }
        catch (NumberFormatException nfe)
        {
        }

        if (bitrateUpKbps != null && bitrateDownKbps != null)
        {
            int bitrate = bitrateDownKbps + bitrateUpKbps;
            if (octoReceiveBitrate != null)
            {
                bitrate += octoReceiveBitrate;
            }
            if (octoSendBitrate != null)
            {
                bitrate += octoSendBitrate;
            }

            lastReportedBitrateKbps = bitrate;
        }

        setIsOperational(!Boolean.parseBoolean(stats.getValueAsString(
            SHUTDOWN_IN_PROGRESS)));

        String newVersion = stats.getValueAsString(VERSION);
        if (newVersion != null)
        {
            version = newVersion;
        }
    }

    Bridge(BridgeSelector bridgeSelector,
           Jid jid,
           Version version)
    {
        this.bridgeSelector = bridgeSelector;
        this.jid = Objects.requireNonNull(jid, "jid");
        if (version != null)
        {
            this.version = version.getVersion();
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

    /**
     * Sets the stream count currently used.
     * @param streamCount the stream count currently used.
     */
    private void setVideoStreamCount(int streamCount)
    {
        if (this.videoStreamCount != streamCount)
        {
            logger.info("Video stream count for: " + jid + ": " + streamCount);
        }

        int estimatedBefore = getEstimatedVideoStreamCount();

        this.videoStreamCount = streamCount;

        // The event for video streams count diff are processed on
        // a single threaded queue and those will pile up during conference
        // burst. Because of that "videoStreamCountDiff" must be cleared
        // even if the was no change to the actual value.
        // FIXME eventually add a timestamp and reject old events
        if (videoStreamCountDiff != 0)
        {
            videoStreamCountDiff = 0;
            logger.info(
                "Reset video stream diff on " + this.jid
                    + " video streams: " + this.videoStreamCount
                    + " (estimation error: "
                    // FIXME estimation error is often invalid wrong,
                    // but not enough time to look into it now
                    + (estimatedBefore - getEstimatedVideoStreamCount())
                    + ")");
        }
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
        // Check if we should give this bridge another try
        verifyFailureThreshold();

        return isOperational;
    }

    /**
     * Verifies if it has been long enough since last bridge failure to give
     * it another try(reset isOperational flag).
     */
    private void verifyFailureThreshold()
    {
        if (isOperational)
        {
            return;
        }

        if (System.currentTimeMillis() - failureTimestamp
                > bridgeSelector.getFailureResetThreshold())
        {
            logger.info("Resetting operational status for " + jid);
            isOperational = true;
        }
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

        return this.lastReportedBitrateKbps - o.lastReportedBitrateKbps;
    }

    private int getEstimatedVideoStreamCount()
    {
        return videoStreamCount + videoStreamCountDiff;
    }

    void onVideoStreamsChanged(Integer videoStreamCount)
    {
        if (videoStreamCount == null || videoStreamCount == 0)
        {
            logger.error("videoStreamCount is " + videoStreamCount);
            return;
        }

        boolean adding = videoStreamCount > 0;

        videoStreamCountDiff += videoStreamCount;
        logger.info(
            (adding ? "Adding " : "Removing ") + Math.abs(videoStreamCount)
                + " video streams on " + this.jid
                + " video streams: " + this.videoStreamCount
                + " diff: " + videoStreamCountDiff
                + " (estimated: " + getEstimatedVideoStreamCount() + ")");
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
                "Bridge[jid=%s, relayId=%s, region=%s]",
                     jid.toString(),
                     getRelayId(),
                     getRegion());
    }

    private boolean exceedsRecommendedBitrate()
    {
        return RECOMMENDED_MAX_BITRATE > -1
            && lastReportedBitrateKbps > RECOMMENDED_MAX_BITRATE;
    }

    private boolean exceedsRecommendedVideoStreams()
    {
        return RECOMMENDED_MAX_VIDEO_STREAMS > -1
            && getEstimatedVideoStreamCount() > RECOMMENDED_MAX_VIDEO_STREAMS;
    }

    public boolean isInSurvivalMode()
    {
        // XXX maybe this flag should come directly from the bridge
        return  exceedsRecommendedBitrate() || exceedsRecommendedVideoStreams();

    }
}
