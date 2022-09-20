/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
 */
package org.jitsi.jicofo;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.metrics.*;
import org.jitsi.jicofo.stats.*;
import org.jitsi.metrics.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.stats.*;
import org.json.simple.*;
import org.jxmpp.jid.*;

import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Manages {@link JitsiMeetConference} on some server. Takes care of creating
 * and expiring conference focus instances. Manages focus XMPP connection.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class FocusManager
    implements JitsiMeetConferenceImpl.ConferenceListener, ConferenceStore
{
    /**
     * The logger used by this instance.
     */
    private final static Logger logger = new LoggerImpl(FocusManager.class.getName());

    /**
     * The thread that expires {@link JitsiMeetConference}s.
     */
    private final FocusExpireThread expireThread = new FocusExpireThread();

    /**
     * Jitsi Meet conferences mapped by MUC room names.
     *
     * Note that access to this field is almost always protected by a lock on
     * {@code this}. However, {@code #getConferenceCount()} executes
     * {@link Map#size()} on it, which wouldn't be safe with a
     * {@link HashMap} (as opposed to a {@link ConcurrentHashMap}.
     * I've chosen this solution, because I don't know whether the cleaner
     * solution of synchronizing on {@link #conferencesSyncRoot} in
     * {@code #getConferenceCount()} is safe.
     */
    private final Map<EntityBareJid, JitsiMeetConferenceImpl> conferences = new ConcurrentHashMap<>();
    private final LongGaugeMetric conferenceCount = JicofoMetricsContainer.getInstance().registerLongGauge(
            "conferences",
            "Running count of conferences (excluding internal conferences created for health checks).");

    private final List<JitsiMeetConference> conferencesCache = new CopyOnWriteArrayList<>();

    /**
     * The object used to synchronize access to {@link #conferences}.
     */
    private final Object conferencesSyncRoot = new Object();

    private final List<ConferenceStore.Listener> listeners = new ArrayList<>();

    /**
     * Holds the conferences that are currently pinned to a specific bridge version.
     */
    private final Map<EntityBareJid, PinnedConference> pinnedConferences = new HashMap<>();

    /**
     * Clock to use for pin timeouts.
     */
    private final Clock clock;

    /**
     * Create FocusManager with custom clock for testing.
     */
    public FocusManager(Clock clock)
    {
        this.clock = clock;
    }

    /**
     * Create FocusManager with system clock.
     */
    public FocusManager()
    {
        this(Clock.systemUTC());
    }

    /**
     * Starts this manager.
     */
    public void start()
    {
        expireThread.start();
    }

    /**
     * Stops this instance.
     */
    public void stop()
    {
        expireThread.stop();
    }

    /**
     * Allocates new focus for given MUC room, using the default logging level.
     *
     * @param room the name of MUC room for which new conference has to be
     *             allocated.
     * @param properties configuration properties map included in the request.
     * @return <tt>true</tt> if conference focus is in the room and ready to
     *         handle session participants.
     * @throws Exception if for any reason we have failed to create
     *                   the conference
     */
    public boolean conferenceRequest(EntityBareJid room, Map<String, String> properties)
        throws Exception
    {
        return conferenceRequest(room, properties, Level.ALL);
    }

    /**
     * Allocates new focus for given MUC room, including this conference
     * in statistics.
     *
     * @param room the name of MUC room for which new conference has to be
     *             allocated.
     * @param properties configuration properties map included in the request.
     * @return <tt>true</tt> if conference focus is in the room and ready to
     *         handle session participants.
     * @throws Exception if for any reason we have failed to create
     *                   the conference
     */
    public boolean conferenceRequest(
        EntityBareJid          room,
        Map<String, String>    properties,
        Level                  loggingLevel)
        throws Exception
    {
        return conferenceRequest(room, properties, loggingLevel, true);
    }


    /**
     * Allocates new focus for given MUC room.
     *
     * @param room the name of MUC room for which new conference has to be
     *             allocated.
     * @param properties configuration properties map included in the request.
     * @param loggingLevel the logging level which should be used by the new
     * {@link JitsiMeetConference}
     * @param includeInStatistics whether or not this conference should be
     *                            included in statistics
     *
     * @return <tt>true</tt> if conference focus is in the room and ready to
     *         handle session participants.
     * @throws Exception if for any reason we have failed to create
     *                   the conference
     */
    public boolean conferenceRequest(
            EntityBareJid          room,
            Map<String, String>    properties,
            Level                  loggingLevel,
            boolean                includeInStatistics)
        throws Exception
    {
        if (room == null)
            return false;

        JitsiMeetConferenceImpl conference;
        boolean isConferenceCreator;
        synchronized (conferencesSyncRoot)
        {
            conference = conferences.get(room);
            isConferenceCreator = conference == null;
            if (isConferenceCreator)
            {
                conference = createConference(room, properties, loggingLevel, includeInStatistics);
            }
        }

        try
        {
            if (isConferenceCreator)
            {
                conference.start();
            }
        }
        catch (Exception e)
        {
            logger.warn("Exception while trying to start the conference", e);

            // stop() method is called by the conference automatically in order
            // to not release the lock on JitsiMeetConference instance and avoid
            // a deadlock. It may happen when this thread is about to call
            // conference.stop() and another thread has entered the method
            // before us. That other thread will try to call
            // FocusManager.conferenceEnded, but we're still holding the lock
            // on FocusManager instance.

            //conference.stop();

            throw e;
        }


        return conference.isStarted();
    }

    /**
     * Allocates conference for given <tt>room</tt>.
     *
     * Note: this should only be called by threads which hold the lock on
     * {@link #conferencesSyncRoot}.
     *
     * @param room name of the MUC room of Jitsi Meet conference.
     * @param properties configuration properties, see {@link JitsiMeetConfig}
     *                   for the list of valid properties.
     *
     * @return new {@link JitsiMeetConferenceImpl} instance
     */
    private JitsiMeetConferenceImpl createConference(
            @NotNull EntityBareJid room, Map<String, String> properties,
            Level logLevel, boolean includeInStatistics)
    {
        JitsiMeetConfig config = new JitsiMeetConfig(properties);

        JitsiMeetConferenceImpl conference;
        synchronized (conferencesSyncRoot)
        {
            String jvbVersion = getBridgeVersionForConference(room);
            conference
                    = new JitsiMeetConferenceImpl(
                        room,
                        this, config, logLevel,
                        jvbVersion, includeInStatistics);

            conferences.put(room, conference);
            conferencesCache.add(conference);
        }

        if (includeInStatistics)
        {
            conferenceCount.inc();
            ConferenceMetrics.totalConferencesCreated.inc();
        }

        return conference;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void conferenceEnded(JitsiMeetConferenceImpl conference)
    {
        EntityBareJid roomName = conference.getRoomName();

        synchronized (conferencesSyncRoot)
        {
            conferences.remove(roomName);
            conferencesCache.remove(conference);
            if (conference.includeInStatistics())
            {
                conferenceCount.dec();
            }

            // It is not clear whether the code below necessarily needs to
            // hold the lock or not.
            Iterable<ConferenceStore.Listener> listeners;

            synchronized (this.listeners)
            {
                listeners = new ArrayList<>(this.listeners);
            }

            for (ConferenceStore.Listener listener : listeners)
            {
                listener.conferenceEnded(roomName);
            }
        }
    }

    /**
     * Returns {@link JitsiMeetConference} for given MUC {@code roomName} or
     * {@code null} if no conference has been allocated yet.
     *
     * @param roomName the name of MUC room for which we want get the
     * {@code JitsiMeetConference} instance.
     * @return the {@code JitsiMeetConference} for the specified
     * {@code roomName} or {@code null} if no conference has been allocated yet
     */
    @Override
    public JitsiMeetConferenceImpl getConference(@NotNull EntityBareJid roomName)
    {
        synchronized (conferencesSyncRoot)
        {
            return conferences.get(roomName);
        }
    }

    @Override
    @NotNull
    public List<JitsiMeetConference> getAllConferences()
    {
        return getConferences();
    }

    /**
     * Get the conferences of this Jicofo.  Note that the
     * List returned is a snapshot of the conference
     * references at the time of the call.
     * @return the list of conferences
     */
    public List<JitsiMeetConference> getConferences()
    {
        return conferencesCache;
    }

    /**
     * Add the listener that will be notified about conference focus
     * allocation/disposal.
     * @param listener the listener instance to be registered.
     */
    @Override
    public void addListener(@NotNull ConferenceStore.Listener listener)
    {
        synchronized (listeners)
        {
            listeners.add(listener);
        }
    }

    /**
     * Remove the listener that will be notified about conference focus
     * allocation/disposal.
     * @param listener the listener instance to be registered.
     */
    @Override
    public void removeListener(@NotNull ConferenceStore.Listener listener)
    {
        synchronized (listeners)
        {
            listeners.remove(listener);
        }
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we'll merge stats from different "child" objects here.
        JSONObject stats = new JSONObject();
        stats.put("total_participants", ConferenceMetrics.totalParticipants.get());
        stats.put("total_participants_no_multi_stream", ConferenceMetrics.totalParticipantsNoMultiStream.get());
        stats.put("total_participants_no_source_name", ConferenceMetrics.totalParticipantsNoSourceName.get());
        stats.put("total_conferences_created", ConferenceMetrics.totalConferencesCreated.get());
        stats.put("conferences", conferenceCount.get());

        JSONObject bridgeFailures = new JSONObject();
        bridgeFailures.put("participants_moved", ConferenceMetrics.totalParticipantsMoved.get());
        stats.put("bridge_failures", bridgeFailures);

        JSONObject participantNotifications = new JSONObject();
        participantNotifications.put("ice_failed", ConferenceMetrics.totalParticipantsIceFailed.get());
        participantNotifications.put("request_restart", ConferenceMetrics.totalParticipantsRequestedRestart.get());
        stats.put("participant_notifications", participantNotifications);

        // Calculate the number of participants and conference size distribution
        int numParticipants = 0;
        int largestConferenceSize = 0;
        ConferenceSizeBuckets conferenceSizes = new ConferenceSizeBuckets();
        // The sum of squares of conference sizes.
        int endpointPairs = 0;
        Set<BaseJibri> jibriRecordersAndGateways = new HashSet<>();
        for (JitsiMeetConference conference : getConferences())
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

            numParticipants += confSize;
            endpointPairs += confSize * confSize;
            largestConferenceSize = Math.max(largestConferenceSize, confSize);

            conferenceSizes.addValue(confSize);

            jibriRecordersAndGateways.add(conference.getJibriRecorder());
            jibriRecordersAndGateways.add(conference.getJibriSipGateway());
        }

        stats.put("largest_conference", largestConferenceSize);
        stats.put("participants", numParticipants);
        stats.put("conference_sizes", conferenceSizes.toJson());
        stats.put("endpoint_pairs", endpointPairs);

        stats.put("jibri", JibriStats.getStats(jibriRecordersAndGateways));

        stats.put("queues", QueueStatistics.Companion.getStatistics());

        return stats;
    }

    @NotNull
    OrderedJsonObject getDebugState(boolean full)
    {
        OrderedJsonObject o = new OrderedJsonObject();
        for (JitsiMeetConference conference : getConferences())
        {
            if (full)
            {
                o.put(conference.getRoomName().toString(), conference.getDebugState());
            }
            else
            {
                o.put(conference.getRoomName().toString(), conference.getParticipantCount());
            }

        }
        return o;
    }

    /**
     * Create or update the pinning for the specified conference.
     */
    public void pinConference(@NotNull EntityBareJid roomName,
                              @NotNull String jvbVersion,
                              @NotNull Duration duration)
    {
        PinnedConference pc = new PinnedConference(jvbVersion, duration);

        synchronized (conferencesSyncRoot)
        {
            PinnedConference prev = pinnedConferences.remove(roomName);
            if (prev != null)
            {
                logger.info(() -> "Modifying pin for " + roomName + ":");
            }

            pinnedConferences.put(roomName, pc);
        }

        logger.info(() -> {
            long minutes = duration.toMinutes();
            return "Pinning " + roomName + " to version \"" + jvbVersion + "\" for " +
                    minutes + (minutes == 1 ? " minute." : " minutes.");
        });
    }

    /**
     * Remove any existing pinning for the specified conference.
     */
    public void unpinConference(@NotNull EntityBareJid roomName)
    {
        synchronized (conferencesSyncRoot)
        {
            PinnedConference prev = pinnedConferences.remove(roomName);
            if (prev != null)
            {
                logger.info(() -> "Removing pin for " + roomName);
            }
            else
            {
                logger.info(() -> "Unpin failed: " + roomName);
            }
        }
    }

    private void expirePins(Instant curTime)
    {
        if (pinnedConferences.values().removeIf(v -> v.expiresAt.isBefore(curTime)))
        {
            logger.info("Some pins have expired.");
        }
    }

    /**
     * Return the requested bridge version of a pinned conference.
     * Returns null if the conference is not currently pinned.
     */
    public String getBridgeVersionForConference(@NotNull EntityBareJid roomName)
    {
        synchronized (conferencesSyncRoot)
        {
            expirePins(clock.instant());
            PinnedConference pc = pinnedConferences.get(roomName);
            return pc != null ? pc.jvbVersion : null;
        }
    }

    /**
     * Get the set of current pinned conferences.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getPinnedConferences()
    {
        Instant curTime = clock.instant();
        ZoneId tz = ZoneId.systemDefault();
        JSONArray pins = new JSONArray();

        synchronized (conferencesSyncRoot)
        {
            expirePins(curTime);
            pinnedConferences.forEach((k, v) -> {
                JSONObject pin = new JSONObject();
                pin.put("conference-id", k.toString());
                pin.put("jvb-version", v.jvbVersion);
                pin.put("expires-at", v.expiresAt.atZone(tz).toString());
                pins.add(pin);
            });
        }

        JSONObject stats = new JSONObject();
        stats.put("pins", pins);
        return stats;
    }

    /**
     * Takes care of stopping {@link JitsiMeetConference} if no participant ever joins.
     *
     * TODO: this would be cleaner if it maintained a list of conferences to check, with conferences firing a
     * "participant joined" event.
     */
    private class FocusExpireThread
    {
        private static final long POLL_INTERVAL = 5000;

        private final Duration timeout = ConferenceConfig.config.getConferenceStartTimeout();

        private Thread timeoutThread;

        private final Object sleepLock = new Object();

        private boolean enabled;

        void start()
        {
            if (timeoutThread != null)
            {
                throw new IllegalStateException();
            }

            timeoutThread = new Thread(this::expireLoop, "FocusExpireThread");
            enabled = true;
            timeoutThread.start();
        }

        void stop()
        {
            if (timeoutThread == null)
            {
                return;
            }

            enabled = false;

            synchronized (sleepLock)
            {
                sleepLock.notifyAll();
            }

            try
            {
                if (Thread.currentThread() != timeoutThread)
                {
                    timeoutThread.join();
                }
                timeoutThread = null;
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        private void expireLoop()
        {
            while (enabled)
            {
                // Sleep
                try
                {
                    synchronized (sleepLock)
                    {
                        sleepLock.wait(POLL_INTERVAL);
                    }
                }
                catch (InterruptedException e)
                {
                    // Continue to check the enabled flag
                    // if we're still supposed to run
                }

                if (!enabled)
                {
                    break;
                }

                try
                {
                    ArrayList<JitsiMeetConferenceImpl> conferenceCopy;
                    synchronized (FocusManager.this.conferencesSyncRoot)
                    {
                        conferenceCopy = new ArrayList<>(conferences.values());
                    }

                    // Loop over conferences
                    for (JitsiMeetConferenceImpl conference : conferenceCopy)
                    {
                        if (conference.hasHadAtLeastOneParticipant())
                        {
                            continue;
                        }

                        if (Duration.between(conference.getCreationTime(), Instant.now()).compareTo(timeout) > 0)
                        {
                            conference.stop();
                        }
                    }
                }
                catch (Exception ex)
                {
                    logger.warn("Error while checking for timed out conference", ex);
                }
            }
        }
    }

    /**
     * Holds pinning information for one conference.
     */
    private class PinnedConference
    {
        /**
         * The version of the bridge that this conference must use.
         */
        @NotNull final String jvbVersion;

        /**
         * When this pinning expires.
         */
        @NotNull final Instant expiresAt;

        /**
         * Constructor
         */
        public PinnedConference(@NotNull String jvbVersion, @NotNull Duration duration)
        {
            this.jvbVersion = jvbVersion;
            this.expiresAt = clock.instant().plus(duration).truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
