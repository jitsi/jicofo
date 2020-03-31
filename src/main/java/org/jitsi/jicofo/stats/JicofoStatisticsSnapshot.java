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
     * The number of participants in the largest, currently-active conference
     */
    public int largestConferenceSize;

    /**
     * The current number of participants on this Jicofo
     */
    public int numParticipants;

    public int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];

    public static JicofoStatisticsSnapshot generate(
        @NotNull FocusManager focusManager)
    {
        JicofoStatisticsSnapshot snapshot = new JicofoStatisticsSnapshot();
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

        return snapshot;
    }
}
