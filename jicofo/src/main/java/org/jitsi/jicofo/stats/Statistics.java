package org.jitsi.jicofo.stats;

import org.jitsi.jicofo.metrics.*;
import org.jitsi.metrics.*;

/**
 * Track persistent, long-running Jicofo statistics
 */
public class Statistics
{
    private Statistics()
    {
    }

    /**
     * The total number of conferences created on this Jicofo since it was started
     */
    public static final CounterMetric totalConferencesCreated = JicofoMetricsContainer.getInstance().registerCounter(
            "conferences_created",
            "The number of conferences created on this Jicofo since it was started");

    /**
     * The total number of participants that have connected to this Jicofo since it was started.
     */
    public static final CounterMetric totalParticipants = JicofoMetricsContainer.getInstance().registerCounter(
            "participants",
            "The number of participants");

    /**
     * Total number of participants with no support for receiving multiple streams.
     */
    public static final CounterMetric totalParticipantsNoMultiStream
            = JicofoMetricsContainer.getInstance().registerCounter(
                    "participants_no_multi_stream",
                    "Number of participants with no support for receiving multiple streams.");

    /**
     * The total number of participants with no support for source names.
     */
    public static final CounterMetric totalParticipantsNoSourceName
            = JicofoMetricsContainer.getInstance().registerCounter(
                    "participants_no_source_name",
                    "Number of participants with no support for source names.");

    /**
     * The number of participants that were moved away from a failed bridge.
     */
    public static final CounterMetric totalParticipantsMoved = JicofoMetricsContainer.getInstance().registerCounter(
            "participants_moved",
            "Number of participants moved away from a failed bridge");

    /**
     * The number of participants that reported an ICE failure on their connection to the bridge.
     */
    public final static CounterMetric totalParticipantsIceFailed = JicofoMetricsContainer.getInstance().registerCounter(
            "participants_ice_failures",
            "Number of participants that reported an ICE failure");

    /**
     * The number of participants that requested to be re-invited via session-terminate.
     */
    public static final CounterMetric totalParticipantsRequestedRestart
            = JicofoMetricsContainer.getInstance().registerCounter(
                    "participants_restart_requests",
            "Number of times a participant requested a restart via session-terminate");
}
