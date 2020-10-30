package org.jitsi.jicofo.stats;

import java.util.concurrent.atomic.*;

/**
 * Track persistent, long-running Jicofo statistics
 */
public class Statistics
{
    /**
     * The total number of conferences created on this Jicofo since
     * it was started
     */
    public final AtomicInteger totalConferencesCreated = new AtomicInteger(0);

    /**
     * The total number of participants that have connected to this
     * Jicofo since it was started
     */
    public final AtomicInteger totalParticipants = new AtomicInteger(0);

    /**
     * The number of participants that were moved away from a failed bridge.
     */
    public final AtomicInteger totalParticipantsMoved = new AtomicInteger();

    /**
     * The number of participants that reported an ICE failure on their connection to the bridge.
     */
    public final AtomicInteger totalParticipantsIceFailed = new AtomicInteger();

    /**
     * The number of participants that requested to be re-invited via session-terminate.
     */
    public final AtomicInteger totalParticipantsRequestedRestart = new AtomicInteger();

    /**
     * The number of time a bridge as removed from a conference because it had failed.
     */
    public final AtomicInteger totalBridgesRemoved = new AtomicInteger();
}
