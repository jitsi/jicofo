/*
 * Copyright @ 2018 - present 8x8, Inc.
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
 *
 */
package org.jitsi.jicofo.recording.jibri;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;
import org.jitsi.utils.logging.*;
import org.json.simple.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Service listens for {@link JibriSession} events and computes statistics.
 */
public class JibriStats
    extends EventHandlerActivator
{
    /**
     * The class logger used by {@link JibriStats}.
     */
    static private final Logger logger = Logger.getLogger(JibriStats.class);

    /**
     * The OSGi bundle context.
     */
    private BundleContext bundleContext;

    /**
     * How many times a Jibri SIP call has failed to start.
     */
    private volatile int totalSipCallFailures = 0;

    /**
     * How many times Jibri live streaming has failed to start.
     */
    private volatile int totalLiveStreamingFailures = 0;

    /**
     * How many times Jibri recording has failed to start.
     */
    private volatile int totalRecordingFailures = 0;

    /**
     * Creates new instance.
     */
    public JibriStats()
    {
        super(new String[] { JibriSessionEvent.FAILED_TO_START });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
            throws Exception
    {
        super.start(bundleContext);

        this.bundleContext = bundleContext;

        bundleContext.registerService(JibriStats.class, this, null);
    }

    /**
     * Generates the stats section covering Jibri sessions.
     * @param stats the JSON object to which the stats are added to.
     */
    private void generateJibriSessionStats(JSONObject stats)
    {
        if (this.bundleContext == null)
        {
            return;
        }

        FocusManager focusManager
                = ServiceUtils2.getService(this.bundleContext, FocusManager.class);

        if (focusManager == null)
        {
            return;
        }

        List<JitsiMeetConference> conferences = focusManager.getConferences();
        JibriSessionStats jibriSessionStats = new JibriSessionStats();

        for (JitsiMeetConference conf : conferences)
        {
            if (!conf.includeInStatistics())
            {
                continue;
            }

            jibriSessionStats.merge(conf.getJibriSessionStats());
        }

        jibriSessionStats.toJSON(stats);
    }

    /**
     * Handles Jibri events.
     * @param event the event to process.
     */
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    @Override
    public void handleEvent(Event event)
    {
        if (!(event instanceof JibriSessionEvent))
        {
            return;
        }

        JibriSessionEvent jibriSessionEvent = (JibriSessionEvent) event;
        JibriSessionEvent.Type type = jibriSessionEvent.getType();

        if (type == null)
        {
            logger.error("No event type passed for JibriSessionEvent");
            return;
        }

        // It's only ever 1 thread writing, so it's fine to do ++ on a volatile
        switch(type)
        {
            case SIP_CALL:
                totalSipCallFailures++;
                break;
            case RECORDING:
                totalRecordingFailures++;
                break;
            case LIVE_STREAMING:
                totalLiveStreamingFailures++;
                break;
            default:
                logger.error("Unhandled JibriSessionEvent.Type: " + type);
                break;
        }
    }

    /**
     * @return how many times a Jibri SIP call has failed to start.
     */
    public int getTotalSipCallFailures()
    {
        return totalSipCallFailures;
    }

    /**
     * @return how many times Jibri live streaming has failed to start.
     */
    public int getTotalLiveStreamingFailures()
    {
        return totalLiveStreamingFailures;
    }

    /**
     * @return how many times Jibri recording has failed to start.
     */
    public int getTotalRecordingFailures()
    {
        return totalRecordingFailures;
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        JSONObject stats = new JSONObject();
        stats.put("total_live_streaming_failures", getTotalLiveStreamingFailures());
        stats.put("total_recording_failures", getTotalRecordingFailures());
        stats.put("total_sip_call_failures", getTotalSipCallFailures());

        generateJibriSessionStats(stats);

        return stats;
    }
}
