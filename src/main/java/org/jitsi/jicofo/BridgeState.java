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
package org.jitsi.jicofo;

import org.jitsi.assertions.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.util.*;

/**
 * Class holds videobridge state and implements {@link Comparable}
 * interface to find least loaded bridge.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
class BridgeState
    implements Comparable<BridgeState>
{
    private final static Logger logger = Logger.getLogger(BridgeState.class);

    /**
     * The parent {@link BridgeSelector}.
     */
    private BridgeSelector bridgeSelector;

    /**
     * The XMPP address of the bridge.
     */
    private final String jid;

    /**
     * How many conferences are there on the bridge (as reported by the bridge
     * itself).
     */
    private int conferenceCount = 0;

    /**
     * How many video channels are there on the bridge (as reported by the
     * bridge itself).
     */
    private int videoChannelCount = 0;

    /**
     * How many video streams are there on the bridge (as reported by the bridge
     * itself).
     */
    private int videoStreamCount = 0;

    /**
     * The relay ID advertised by the bridge, or {@code null} if none was
     * advertised.
     */
    private String relayId = null;

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
     * Holds bridge version (if known - not all bridge version are capable of
     * reporting it).
     */
    private final Version version;

    /**
     * Stores the {@code operational} status of the bridge, which is
     * {@code true} if the bridge has been successfully used by the focus to
     * allocate channels. It is reset to {@code false} when the focus fails
     * to allocate channels, but it gets another chance when all currently
     * working bridges go down and might eventually get elevated back to
     * {@code true}.
     */
    private boolean isOperational = true /* we assume it is operational */;

    /**
     * The time when this instance has failed.
     */
    private long failureTimestamp;

    BridgeState(BridgeSelector bridgeSelector, String bridgeJid,
                Version version)
    {
        this.bridgeSelector = bridgeSelector;
        Assert.notNullNorEmpty(bridgeJid, "bridgeJid: " + bridgeJid);

        this.jid = bridgeJid;
        this.version = version;
    }

    public void setConferenceCount(int conferenceCount)
    {
        if (this.conferenceCount != conferenceCount)
        {
            logger.info("Conference count for: " + jid + ": " + conferenceCount);
        }
        this.conferenceCount = conferenceCount;
    }

    public int getConferenceCount()
    {
        return this.conferenceCount;
    }

    /**
     * Return the number of channels used.
     * @return the number of channels used.
     */
    public int getVideoChannelCount()
    {
        return videoChannelCount;
    }

    /**
     * @return the relay ID advertised by the bridge, or {@code null} if
     * none was advertised.
     */
    public String getRelayId()
    {
        return relayId;
    }

    /**
     * Sets the number of channels used.
     * @param channelCount the number of channels used.
     */
    public void setVideoChannelCount(int channelCount)
    {
        this.videoChannelCount = channelCount;
    }

    /**
     * Sets the relay ID advertised by the bridge.
     * @param relayId the value to set.
     */
    public void setRelayId(String relayId)
    {
        this.relayId = relayId;
    }

    /**
     * Returns the number of streams used.
     * @return the number of streams used.
     */
    public int getVideoStreamCount()
    {
        return videoStreamCount;
    }

    /**
     * Sets the stream count currently used.
     * @param streamCount the stream count currently used.
     */
    public void setVideoStreamCount(int streamCount)
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
                    + " video channels: " + this.videoChannelCount
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
     * The least value is returned the least the bridge is loaded.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public int compareTo(BridgeState o)
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

        return this.getEstimatedVideoStreamCount()
            - o.getEstimatedVideoStreamCount();
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
                + " video channels: " + this.videoChannelCount
                + " video streams: " + this.videoStreamCount
                + " diff: " + videoStreamCountDiff
                + " (estimated: " + getEstimatedVideoStreamCount() + ")");
    }

    public String getJid()
    {
        return jid;
    }

    public Version getVersion()
    {
        return version;
    }
}
