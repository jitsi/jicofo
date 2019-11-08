package org.jitsi.jicofo.stats;

import java.util.concurrent.atomic.*;

/**
 * Track persistent, long-running Jicofo statistics
 */
public class Statistics
{
    public final AtomicInteger totalConferencesCreated = new AtomicInteger(0);
}
