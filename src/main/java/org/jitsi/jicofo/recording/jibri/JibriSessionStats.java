package org.jitsi.jicofo.recording.jibri;

import org.json.simple.*;

import java.util.*;
import java.util.function.*;

/**
 * Statistics snapshot for {@link JibriSession}.
 */
public class JibriSessionStats
{
    /**
     * Counts active {@link JibriSession}s.
     * @param sessions the list of sessions.
     * @param jibriType the type of Jibri to count.
     * @return how many active Jibri sessions of given type are in the list.
     */
    private static int countActive(Collection<JibriSession> sessions,
                                   JibriSession.Type jibriType)
    {
        return countJibris(sessions, jibriType, JibriSession::isActive);
    }

    /**
     * Counts Jibri sessions.
     * @param sessions the list of sessions to scan.
     * @param jibriType the type of jibri session to be count.
     * @param selector the selector which makes the decision on whether or not
     * to count the given instance.
     * @return the count of Jibri sessions of given type that pass
     * the selector's test.
     */
    private static int countJibris(
            Collection<JibriSession> sessions,
            JibriSession.Type jibriType,
            Function<JibriSession, Boolean> selector)
    {
        int count = 0;

        for (JibriSession session : sessions)
        {
            if (session.getJibriType().equals(jibriType)
                    && selector.apply(session))
            {
                count++;
            }
        }

        return count;
    }

    /**
     * Counts pending Jibri sessions of given type.
     * @param sessions the list of sessions to scan.
     * @param jibriType the type of Jibri session to count.
     * @return how many Jibri sessions of given type and in the pending state
     * are there on the list.
     */
    private static int countPending(Collection<JibriSession> sessions,
                                    JibriSession.Type jibriType)
    {
        return countJibris(sessions, jibriType, JibriSession::isPending);
    }

    /**
     * How many active recording Jibri sessions.
     */
    private int activeRecordingSessions;

    /**
     * How many active lice streaming Jibri sessions.
     */
    private int activeLiveStreamingSessions;

    /**
     * How many active SIP call Jibri sessions.
     */
    private int activeSipCallSessions;

    /**
     * How many pending recording Jibri sessions.
     */
    private int pendingRecordingSessions;

    /**
     * How many pending live streaming Jibri sessions
     */
    private int pendingLiveStreamingSessions;

    /**
     * How many pending SIP call Jibri sessions.
     */
    private int pendingSipCallSessions;

    /**
     * Creates new {@link JibriSessionStats} computed for the given list of
     * {@link JibriSession}.
     *
     * @param sessions the list for which the stats will be generated.
     */
    public JibriSessionStats(Collection<JibriSession> sessions)
    {
        activeLiveStreamingSessions
            = countActive(
                sessions,
                JibriSession.Type.LIVE_STREAMING);
        activeRecordingSessions
            = countActive(
                sessions,
                JibriSession.Type.RECORDING);
        activeSipCallSessions
            = countActive(
                sessions,
                JibriSession.Type.SIP_CALL);

        pendingLiveStreamingSessions
            = countPending(
                sessions,
                JibriSession.Type.LIVE_STREAMING);
        pendingRecordingSessions
            = countPending(
                sessions,
                JibriSession.Type.RECORDING);
        pendingSipCallSessions
            = countPending(
                sessions,
                JibriSession.Type.SIP_CALL);
    }

    /**
     * Describes as JSON.
     * @param stats the JSON object where the stats will end up.
     */
    @SuppressWarnings("unchecked")
    public void toJSON(JSONObject stats)
    {
        stats.put("live_streaming_active", activeLiveStreamingSessions);
        stats.put("recording_active", activeRecordingSessions);
        stats.put("sip_call_active", activeSipCallSessions);

        stats.put("live_streaming_pending", pendingLiveStreamingSessions);
        stats.put("recording_pending", pendingRecordingSessions);
        stats.put("sip_call_pending", pendingSipCallSessions);
    }
}
