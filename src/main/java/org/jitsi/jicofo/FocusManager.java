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
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.health.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.stats.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.json.simple.*;
import org.jxmpp.jid.*;

import java.time.Duration;
import java.time.Instant;
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
    implements JitsiMeetConferenceImpl.ConferenceListener
{
    /**
     * The logger used by this instance.
     */
    private final static Logger logger = new LoggerImpl(FocusManager.class.getName());

    /**
     * The pseudo-random generator which is to be used when generating IDs.
     */
    private static final Random RANDOM = new Random();

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

    /**
     * The set of the IDs of conferences in {@link #conferences}.
     */
    private final Set<Long> conferenceGids = new HashSet<>();

    /**
     * The object used to synchronize access to {@link #conferences} and
     * {@link #conferenceGids}.
     */
    private final Object conferencesSyncRoot = new Object();

    /**
     * The list of {@link FocusAllocationListener}.
     */
    private final List<FocusAllocationListener> focusAllocListeners = new ArrayList<>();

    /**
     * The XMPP provider for the connection to clients (endpoints).
     */
    private XmppProvider clientXmppProvider;

    /**
     * The XMPP provider for the service connection (for bridges). This may be the same instance as
     * {@link #clientXmppProvider}.
     */
    private XmppProvider serviceXmppProvider;

    /**
     * A class that holds Jicofo-wide statistics
     */
    private final Statistics statistics = new Statistics();

    /**
     * TODO: refactor to avoid the reference.
     */
    private JicofoHealthChecker healthChecker;

    /**
     * The ID of this Jicofo instance, used to generate conference GIDs. The special value 0 is valid in the Octo
     * protocol, but only used when no value is explicitly configured.
     */
    private int octoId;

    /**
     * Starts this manager.
     */
    public void start(XmppProvider clientXmppProvider, XmppProvider serviceXmppProvider)
    {
        expireThread.start();

        int octoId = 0;
        Integer configuredId = OctoConfig.config.getId();
        if (configuredId != null)
        {
            octoId = configuredId;
        }
        if (octoId < 1 || octoId > 0xffff)
        {
            logger.warn(
                "Jicofo ID is not set correctly set (value=" + octoId + "). Configure a valid value [1-65535] by "
                + "setting org.jitsi.jicofo.SHORT_ID in sip-communicator.properties or jicofo.octo.id in jicofo.conf. "
                + "Future versions will require this for Octo.");
            this.octoId = 0;
        }
        else
        {
            logger.info("Initialized octoId=" + octoId);
            this.octoId = octoId;
        }

        this.clientXmppProvider = clientXmppProvider;
        this.serviceXmppProvider = serviceXmppProvider;
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


        return conference.isInTheRoom();
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
            long id = generateConferenceId();
            conference
                    = new JitsiMeetConferenceImpl(
                        room,
                        clientXmppProvider,
                        serviceXmppProvider,
                        this, config, logLevel,
                        id, includeInStatistics);

            conferences.put(room, conference);
            conferenceGids.add(id);
        }

        if (includeInStatistics)
        {
            statistics.totalConferencesCreated.incrementAndGet();
        }

        return conference;
    }

    /**
     * Generates a conference ID which is currently not used by an existing
     * conference in a specific format (6 hexadecimal symbols).
     * @return the generated ID.
     */
    private long generateConferenceId()
    {
        long id;

        synchronized (conferencesSyncRoot)
        {
            do
            {
                id = (octoId << 16) | RANDOM.nextInt(0x1_0000);
            }
            while (conferenceGids.contains(id));
        }

        return id;
    }

    /**
     * Destroys the conference for given room name.
     * @param roomName full MUC room name to destroy.
     * @param reason optional reason string that will be advertised to the
     *               users upon exit.
     */
    public void destroyConference(EntityBareJid roomName, String reason)
    {
        synchronized (conferencesSyncRoot)
        {
            JitsiMeetConferenceImpl conference = getConference(roomName);
            if (conference == null)
            {
                logger.error(
                    "Unable to destroy the conference - not found: " + roomName);

                return;
            }

            // It is unclear whether this needs to execute while holding the
            // lock or not.
            conference.destroy(reason);
        }
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
            conferenceGids.remove(conference.getId());

            // It is not clear whether the code below necessarily needs to
            // hold the lock or not.
            Iterable<FocusAllocationListener> listeners;

            synchronized (focusAllocListeners)
            {
                listeners = new ArrayList<>(focusAllocListeners);
            }

            for (FocusAllocationListener listener : listeners)
            {
                listener.onFocusDestroyed(roomName);
            }
        }
    }

    @Override
    public void participantsMoved(int count)
    {
        statistics.totalParticipantsMoved.addAndGet(count);
    }

    @Override
    public void participantIceFailed()
    {
        statistics.totalParticipantsIceFailed.incrementAndGet();
    }

    @Override
    public void participantRequestedRestart()
    {
        statistics.totalParticipantsRequestedRestart.incrementAndGet();
    }

    @Override
    public void bridgeRemoved()
    {
        statistics.totalBridgesRemoved.incrementAndGet();
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
    public JitsiMeetConferenceImpl getConference(EntityBareJid roomName)
    {
        synchronized (conferencesSyncRoot)
        {
            return conferences.get(roomName);
        }
    }

    /**
     * Get the conferences of this Jicofo.  Note that the
     * List returned is a snapshot of the conference
     * references at the time of the call.
     * @return the list of conferences
     */
    public List<JitsiMeetConference> getConferences()
    {
        synchronized (conferencesSyncRoot)
        {
            return new ArrayList<>(conferences.values());
        }
    }

    private int getNonHealthCheckConferenceCount()
    {
        return (int)conferences.values().stream()
            .filter(JitsiMeetConferenceImpl::includeInStatistics)
            .count();
    }

    /**
     * Add the listener that will be notified about conference focus
     * allocation/disposal.
     * @param listener the listener instance to be registered.
     */
    public void addFocusAllocationListener(FocusAllocationListener listener)
    {
        synchronized (focusAllocListeners)
        {
            focusAllocListeners.add(listener);
        }
    }

    /**
     * Remove the listener that will be notified about conference focus
     * allocation/disposal.
     * @param listener the listener instance to be registered.
     */
    public void removeFocusAllocationListener(FocusAllocationListener listener)
    {
        synchronized (focusAllocListeners)
        {
            focusAllocListeners.remove(listener);
        }
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we'll merge stats from different "child" objects here.
        JSONObject stats = new JSONObject();
        stats.put("total_participants", statistics.totalParticipants.get());
        stats.put("total_conferences_created", statistics.totalConferencesCreated.get());
        stats.put("conferences", getNonHealthCheckConferenceCount());

        JSONObject bridgeFailures = new JSONObject();
        bridgeFailures.put("bridges_removed", statistics.totalBridgesRemoved.get());
        bridgeFailures.put("participants_moved", statistics.totalParticipantsMoved.get());
        stats.put("bridge_failures", bridgeFailures);

        JSONObject participantNotifications = new JSONObject();
        participantNotifications.put("ice_failed", statistics.totalParticipantsIceFailed.get());
        participantNotifications.put("request_restart", statistics.totalParticipantsRequestedRestart.get());
        stats.put("participant_notifications", participantNotifications);

        // Calculate the number of participants and conference size distribution
        int numParticipants = 0;
        int largestConferenceSize = 0;
        int[] conferenceSizes = new int[22];
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
            largestConferenceSize = Math.max(largestConferenceSize, confSize);

            int conferenceSizeIndex = confSize < conferenceSizes.length
                    ? confSize
                    : conferenceSizes.length - 1;
            conferenceSizes[conferenceSizeIndex]++;

            jibriRecordersAndGateways.add(conference.getJibriRecorder());
            jibriRecordersAndGateways.add(conference.getJibriSipGateway());
        }

        stats.put("largest_conference", largestConferenceSize);
        stats.put("participants", numParticipants);
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : conferenceSizes)
        {
            conferenceSizesJson.add(size);
        }
        stats.put("conference_sizes", conferenceSizesJson);

        // XMPP traffic stats
        stats.put("xmpp", clientXmppProvider.getStats());
        stats.put("xmpp_service", serviceXmppProvider.getStats());

        if (healthChecker != null)
        {
            stats.put("slow_health_check", healthChecker.getTotalSlowHealthChecks());
        }

        stats.put("jibri", JibriStats.getStats(jibriRecordersAndGateways));

        return stats;
    }

    public void setHealth(JicofoHealthChecker jicofoHealthChecker)
    {
        this.healthChecker = jicofoHealthChecker;
    }

    public @NotNull Statistics getStatistics()
    {
        return statistics;
    }

    boolean isJicofoIdConfigured()
    {
        return octoId != 0;
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
     * Interface used to listen for focus lifecycle events.
     */
    public interface FocusAllocationListener
    {
        /**
         * Method fired when focus is destroyed.
         * @param roomName the name of the conference room for which focus
         *                 has been destroyed.
         */
        void onFocusDestroyed(EntityBareJid roomName);

        // Add focus allocated method if needed
    }
}
