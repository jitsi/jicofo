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
}
