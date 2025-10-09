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
package org.jitsi.jicofo

import org.jitsi.jicofo.conference.ConferenceMetrics
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl.ConferenceListener
import org.jitsi.jicofo.jibri.JibriSession
import org.jitsi.jicofo.jibri.JibriStats
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.QueueStatistics.Companion.getStatistics
import org.jitsi.utils.stats.ConferenceSizeBuckets
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import org.jitsi.jicofo.metrics.JicofoMetricsContainer.Companion.instance as metricsContainer

/**
 * Manages the set of [JitsiMeetConference]s in this instance.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
class FocusManager(
    private val jicofoServices: JicofoServices,
    /** Clock to use for pin timeouts. */
    private val clock: Clock = Clock.systemUTC(),
) : ConferenceListener, ConferenceStore, XmppProvider.Listener {

    val logger = createLogger()

    /**
     * Jitsi Meet conferences mapped by MUC room names.
     *
     * Note that access to this field is almost always protected by a lock on `this`. However, `#getConferenceCount()`
     * executes [Map.size] on it, which wouldn't be safe with a [HashMap] (as opposed to a [ConcurrentHashMap].
     * I've chosen this solution, because I don't know whether the cleaner solution of synchronizing on
     * [.conferencesSyncRoot] in `#getConferenceCount()` is safe.
     */
    private val conferences: MutableMap<EntityBareJid, JitsiMeetConferenceImpl> = ConcurrentHashMap()
    private val conferencesCache: MutableList<JitsiMeetConference> = CopyOnWriteArrayList()
    private val conferencesByMeetingId: MutableMap<String, JitsiMeetConferenceImpl> = ConcurrentHashMap()

    /** The object used to synchronize access to [.conferences]. */
    private val conferencesSyncRoot = Any()
    private val listeners: MutableList<ConferenceStore.Listener> = ArrayList()

    /** Holds the conferences that are currently pinned to a specific bridge version. */
    private val pinnedConferences: MutableMap<EntityBareJid, PinnedConferenceState> = HashMap()

    fun start() {
        metricsContainer.metricsUpdater.addUpdateTask { updateMetrics() }
    }

    /**
     * Whether there are any breakout conferences whose main room is [jid].
     */
    fun hasBreakoutRooms(jid: EntityBareJid) = conferences.values.any { it.mainRoomJid == jid }

    /**
     * @return <tt>true</tt> if conference focus is in the room and ready to handle session participants.
     * @throws Exception if for any reason we have failed to create the conference.
     */
    @JvmOverloads
    @Throws(Exception::class)
    fun conferenceRequest(
        /** The name of MUC room for which new conference has to be allocated. */
        room: EntityBareJid,
        /** Configuration properties map included in the request. */
        properties: Map<String, String>,
        /** The logging level which should be used by the new conference. */
        loggingLevel: Level = Level.ALL,
        /** Whether this conference should be included in statistics. */
        includeInStatistics: Boolean = true
    ): JitsiMeetConference {
        var conference: JitsiMeetConferenceImpl
        var isConferenceCreator: Boolean
        synchronized(conferencesSyncRoot) {
            val existingConference = conferences[room]
            conference = existingConference ?: createConference(room, properties, loggingLevel, includeInStatistics)
            isConferenceCreator = existingConference == null
            if (!isConferenceCreator) conference.rescheduleConferenceStartTimeout()
        }
        try {
            if (isConferenceCreator) {
                conference.start()
            }
        } catch (e: Exception) {
            logger.warn("Exception while trying to start the conference", e)
            throw e
        }
        return conference
    }

    /** Creates a new conference and registers it in [conferences]. */
    private fun createConference(
        room: EntityBareJid,
        properties: Map<String, String>,
        logLevel: Level,
        includeInStatistics: Boolean
    ): JitsiMeetConferenceImpl {
        var conference: JitsiMeetConferenceImpl
        synchronized(conferencesSyncRoot) {
            val jvbVersion = getBridgeVersionForConference(room)
            conference = JitsiMeetConferenceImpl(
                room,
                this,
                properties,
                logLevel,
                jvbVersion,
                includeInStatistics,
                jicofoServices
            )
            conferences[room] = conference
            conferencesCache.add(conference)
        }
        if (includeInStatistics) {
            ConferenceMetrics.conferenceCount.inc()
            ConferenceMetrics.conferencesCreated.inc()
        }
        return conference
    }

    /** {@inheritDoc} */
    override fun conferenceEnded(conference: JitsiMeetConferenceImpl) {
        val roomName = conference.roomName
        synchronized(conferencesSyncRoot) {
            conferences.remove(roomName)
            conferencesCache.remove(conference)
            conferencesByMeetingId.remove(conference.meetingId)
            if (conference.includeInStatistics()) {
                ConferenceMetrics.conferenceCount.dec()
            }

            // If this was a breakout room, tell the main conference that it ended.
            conference.mainRoomJid?.let { mainRoomJid ->
                conferences[mainRoomJid]?.let {
                    TaskPools.ioPool.submit { it.breakoutConferenceEnded() }
                }
            }

            // It is not clear whether the code below necessarily needs to hold the lock or not.
            var listeners: Iterable<ConferenceStore.Listener>
            synchronized(this.listeners) {
                listeners = ArrayList(this.listeners)
            }
            for (listener in listeners) {
                listener.conferenceEnded(roomName)
            }
        }
    }

    override fun meetingIdSet(conference: JitsiMeetConferenceImpl, meetingId: String): Boolean =
        synchronized(conferencesSyncRoot) {
            conferencesByMeetingId[meetingId]?.let {
                // If there is already a conference with this meeting ID, we don't allow setting it.
                logger.warn("Meeting ID $meetingId already exists for ${it.roomName}.")
                false
            } ?: run {
                // Otherwise, we set the meeting ID and register the conference.
                conferencesByMeetingId[meetingId] = conference
                true
            }
        }

    /** {@inheritDoc} */
    override fun getConference(jid: EntityBareJid): JitsiMeetConference? = synchronized(conferencesSyncRoot) {
        return conferences[jid]
    }

    /** {@inheritDoc} */
    override fun getAllConferences() = getConferences()

    /** {@inheritDoc} */
    fun getConferences() = conferencesCache

    /** {@inheritDoc} */
    override fun addListener(listener: ConferenceStore.Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /** {@inheritDoc} */
    override fun removeListener(listener: ConferenceStore.Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun updateMetrics() {
        // Calculate the number of participants and conference size distribution
        var numParticipants = 0
        var largestConferenceSize = 0
        val conferenceSizes = ConferenceSizeBuckets()
        // The sum of squares of conference sizes.
        var endpointPairs = 0
        val jibriSessions: MutableSet<JibriSession> = HashSet()
        var conferencesWithVisitors = 0
        var visitors = 0L

        for (conference in getConferences()) {
            if (!conference.includeInStatistics()) {
                continue
            }
            var confSize = conference.participantCount
            val conferenceVisitors = conference.visitorCount
            visitors += conferenceVisitors
            if (conferenceVisitors > 0) {
                conferencesWithVisitors++
            }
            // getParticipantCount only includes endpoints with allocated media
            // channels, so if a single participant is waiting in a meeting
            // they wouldn't be counted.  In stats, calling this a conference
            // with size 0 would be misleading, so we add 1 in this case to
            // properly show it as a conference of size 1.  (If there really
            // weren't any participants in there at all, the conference
            // wouldn't have existed in the first place).
            if (confSize == 0) {
                confSize = 1
            }
            numParticipants += confSize
            endpointPairs += confSize * confSize
            largestConferenceSize = largestConferenceSize.coerceAtLeast(confSize)
            conferenceSizes.addValue(confSize.toLong())
            conference.jibriRecorder?.let { jibriSessions.addAll(it.jibriSessions) }
            conference.jibriSipGateway?.let { jibriSessions.addAll(it.jibriSessions) }
        }

        ConferenceMetrics.largestConference.set(largestConferenceSize.toLong())
        ConferenceMetrics.currentParticipants.set(numParticipants.toLong())
        ConferenceMetrics.conferenceSizes = conferenceSizes
        ConferenceMetrics.participantPairs.set(endpointPairs.toLong())
        ConferenceMetrics.conferencesWithVisitors.set(conferencesWithVisitors.toLong())
        ConferenceMetrics.currentVisitors.set(visitors.toLong())

        JibriStats.liveStreamingActive.set(
            jibriSessions.count { it.jibriType == JibriSession.Type.LIVE_STREAMING && it.isActive }.toLong()
        )
        JibriStats.recordingActive.set(
            jibriSessions.count { it.jibriType == JibriSession.Type.RECORDING && it.isActive }.toLong()
        )
        JibriStats.sipActive.set(
            jibriSessions.count { it.jibriType == JibriSession.Type.SIP_CALL && it.isActive }.toLong()
        )
    }

    // We want to avoid exposing unnecessary hierarchy levels in the stats,
    // so we'll merge stats from different "child" objects here.
    val stats: OrderedJsonObject
        get() {
            // We want to avoid exposing unnecessary hierarchy levels in the stats,
            // so we'll merge stats from different "child" objects here.
            val stats = OrderedJsonObject()
            stats["total_participants"] = ConferenceMetrics.participants.get()
            stats["total_conferences_created"] = ConferenceMetrics.conferencesCreated.get()
            stats["conferences"] = ConferenceMetrics.conferenceCount.get()
            stats["conferences_with_visitors"] = ConferenceMetrics.conferencesWithVisitors.get()
            val bridgeFailures = JSONObject()
            bridgeFailures["participants_moved"] = ConferenceMetrics.participantsMoved.get()
            bridgeFailures["bridges_removed"] = ConferenceMetrics.bridgesRemoved.get()
            stats["bridge_failures"] = bridgeFailures
            val participantNotifications = JSONObject()
            participantNotifications["ice_failed"] = ConferenceMetrics.participantsIceFailed.get()
            participantNotifications["request_restart"] = ConferenceMetrics.participantsRequestedRestart.get()
            stats["participant_notifications"] = participantNotifications
            stats["largest_conference"] = ConferenceMetrics.largestConference.get()
            stats["participants"] = ConferenceMetrics.currentParticipants.get()
            stats["visitors"] = ConferenceMetrics.currentVisitors.get()
            stats["conference_sizes"] = ConferenceMetrics.conferenceSizes.toJson()
            stats["endpoint_pairs"] = ConferenceMetrics.participantPairs.get()
            stats["jibri"] = JSONObject().apply {
                put("total_sip_call_failures", JibriStats.sipFailures.get())
                put("total_live_streaming_failures", JibriStats.liveStreamingFailures.get())
                put("total_recording_failures", JibriStats.recordingFailures.get())
                put("live_streaming_active", JibriStats.liveStreamingActive.get())
                put("recording_active", JibriStats.recordingActive.get())
                put("sip_call_active", JibriStats.sipActive.get())
            }
            stats["queues"] = getStatistics()
            return stats
        }

    fun getDebugState(full: Boolean) = OrderedJsonObject().apply {
        for (conference in getConferences()) {
            if (full) {
                this[conference.roomName.toString()] = conference.debugState
            } else {
                this[conference.roomName.toString()] = conference.participantCount
            }
        }
    }

    /** Create or update the pinning for the specified conference. */
    override fun pinConference(roomName: EntityBareJid, jvbVersion: String, duration: Duration) {
        val pc = PinnedConferenceState(jvbVersion, duration)
        synchronized(conferencesSyncRoot) {
            val prev = pinnedConferences.remove(roomName)
            if (prev != null) {
                logger.info("Modifying pin for $roomName")
            }
            pinnedConferences.put(roomName, pc)
        }
        logger.info("Pinning $roomName to version \"$jvbVersion\" for ${duration.toMinutes()} minute(s).")
    }

    /**
     * Remove any existing pinning for the specified conference.
     */
    override fun unpinConference(roomName: EntityBareJid) = synchronized(conferencesSyncRoot) {
        val prev = pinnedConferences.remove(roomName)
        logger.info(if (prev != null) "Removing pin for $roomName" else "Unpin failed: $roomName")
    }

    private fun expirePins(curTime: Instant) {
        if (pinnedConferences.values.removeIf { it.expiresAt.isBefore(curTime) }) {
            logger.info("Some pins have expired.")
        }
    }

    /**
     * Return the requested bridge version of a pinned conference.
     * Returns null if the conference is not currently pinned.
     */
    fun getBridgeVersionForConference(roomName: EntityBareJid): String? {
        synchronized(conferencesSyncRoot) {
            expirePins(clock.instant())
            return pinnedConferences[roomName]?.jvbVersion
        }
    }

    /** Get the set of current pinned conferences. */
    override fun getPinnedConferences(): List<PinnedConference> = buildList {
        synchronized(conferencesSyncRoot) {
            expirePins(clock.instant())
            pinnedConferences.forEach { (conferenceId, p) ->
                add(PinnedConference(conferenceId.toString(), p.jvbVersion, p.expiresAt.toString()))
            }
        }
    }

    /** Holds pinning information for one conference. */
    private inner class PinnedConferenceState(
        /** The version of the bridge that this conference must use. */
        val jvbVersion: String,
        duration: Duration
    ) {
        /** When this pinning expires. */
        val expiresAt: Instant = clock.instant().plus(duration).truncatedTo(ChronoUnit.SECONDS)
    }

    override fun registrationChanged(registered: Boolean) {
        conferencesCache.forEach { it.registrationChanged(registered) }
    }
}
