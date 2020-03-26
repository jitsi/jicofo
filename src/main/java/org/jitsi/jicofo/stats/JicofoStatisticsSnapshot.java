package org.jitsi.jicofo.stats;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.osgi.framework.*;

/**
 * A snapshot of the current state of Jicofo, as well as
 * the current values for long-running statistics
 */
public class JicofoStatisticsSnapshot
{
    /**
     * The number of buckets to use for conference sizes.
     */
    private static final int CONFERENCE_SIZE_BUCKETS = 22;

    /**
     * See {@link Statistics#totalConferencesCreated}
     */
    public int totalConferencesCreated;
    /**
     * The current number of conferences on this Jicofo
     */
    public int numConferences;
    /**
     * The number of participants in the largest, currently-active conference
     */
    public int largestConferenceSize;

    /**
     * See {@link Statistics#totalParticipants}
     */
    public int totalNumParticipants;

    /**
     * The current number of participants on this Jicofo
     */
    public int numParticipants;

    /**
     * Number of jitsi-videobridge instances.
     */
    public int bridgeCount;

    /**
     * Number of jitsi-videobridge instances that are operational (not failed).
     */
    public int operationalBridgeCount;

    /**
     * How many times the live streaming has failed to start.
     */
    public int totalLiveStreamingFailures;

    /**
     * How many times the recording has failed to start.
     */
    public int totalRecordingFailures;

    /**
     * How many times Jicofo has failed to start a SIP call.
     */
    public int totalSipCallFailures;

    /**
     * Number of jigasi instances that support SIP.
     */
    public int jigasiSipCount;

    /**
     * Number of jigasi instances that support transcription.
     */
    public int jigasiTranscriberCount;

    /**
     * Number of jibri instances.
     */
    public int jibriCount;

    /**
     * Number of jibri instances for SIP.
     */
    public int sipJibriCount;

    public int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];

    public static JicofoStatisticsSnapshot generate(
        @NotNull FocusManager focusManager,
        @NotNull JibriStats   jibriStats
    )
    {
        JicofoStatisticsSnapshot snapshot = new JicofoStatisticsSnapshot();
        snapshot.totalNumParticipants =
            focusManager.getStatistics().totalParticipants.get();
        snapshot.totalConferencesCreated =
            focusManager.getStatistics().totalConferencesCreated.get();
        snapshot.numConferences = focusManager.getNonHealthCheckConferenceCount();
        for (JitsiMeetConference conference : focusManager.getConferences())
        {
            if (!conference.includeInStatistics())
            {
                continue;
            }
            int confSize = conference.getParticipantCount();
            // getParticipantCount only includes endpoints with allocated media
            // channels, so if a single participant is waiting in a meeting
            // they wouldn't be counted.  In stats, calling this a conference
            // with size 0 would be misleading, so we add 1 in this case to
            // properly show it as a conference of size 1.  (If there really
            // weren't any participants in there at all, the conference
            // wouldn't have existed in the first place).
            if (confSize == 0)
            {
                confSize = 1;
            }
            snapshot.numParticipants += confSize;
            if (confSize > snapshot.largestConferenceSize)
            {
                snapshot.largestConferenceSize = confSize;
            }
            int conferenceSizeIndex = confSize < snapshot.conferenceSizes.length
                ? confSize
                : snapshot.conferenceSizes.length - 1;
            snapshot.conferenceSizes[conferenceSizeIndex]++;
        }

        BridgeSelector bridgeSelector
                = focusManager.getJitsiMeetServices().getBridgeSelector();
        if (bridgeSelector != null)
        {
            snapshot.bridgeCount = bridgeSelector.getBridgeCount();
            snapshot.operationalBridgeCount = bridgeSelector.getOperationalBridgeCount();
        }

        JigasiDetector jigasiDetector
                = focusManager.getJitsiMeetServices().getJigasiDetector();
        if (jigasiDetector != null)
        {
            snapshot.jigasiSipCount = jigasiDetector.getJigasiSipCount();
            snapshot.jigasiTranscriberCount = jigasiDetector.getJigasiTranscriberCount();
        }

        JibriDetector jibriDetector
                = focusManager.getJitsiMeetServices().getJibriDetector();
        if (jibriDetector != null)
        {
            snapshot.jibriCount = jibriDetector.getInstanceCount();
        }

        JibriDetector sipJibriDetector
                = focusManager.getJitsiMeetServices().getSipJibriDetector();
        if (sipJibriDetector != null)
        {
            snapshot.sipJibriCount = sipJibriDetector.getInstanceCount();
        }

        snapshot.totalLiveStreamingFailures
            = jibriStats.getTotalLiveStreamingFailures();
        snapshot.totalRecordingFailures
            = jibriStats.getTotalRecordingFailures();
        snapshot.totalSipCallFailures
            = jibriStats.getTotalSipCallFailures();

        return snapshot;
    }
}
