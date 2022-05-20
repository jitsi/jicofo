/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.conference;

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jxmpp.jid.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static java.time.temporal.ChronoUnit.SECONDS;

/**
 * Class represent Jitsi Meet conference participant. Stores information about
 * Colibri channels allocated, Jingle session and media sources.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class Participant
{
    /**
     * Returns the endpoint ID for a participant in the videobridge (Colibri)
     * context. This method can be used before <tt>Participant</tt> instance is
     * created for the <tt>ChatRoomMember</tt>.
     *
     * @param chatRoomMember XMPP MUC chat room member which represents a
     *                       <tt>Participant</tt>.
     */
    public static String getEndpointId(ChatRoomMember chatRoomMember)
    {
        return chatRoomMember.getName(); // XMPP MUC Nickname
    }

    /**
     * List of remote source addition or removal operations that have not yet been signaled to this participant.
     */
    private final SourceAddRemoveQueue remoteSourcesQueue = new SourceAddRemoveQueue();

    /**
     * Used to synchronize access to {@link #inviteRunnable}.
     */
    private final Object inviteRunnableSyncRoot = new Object();

    /**
     * The cancelable thread, if any, which is currently allocating channels for this participant.
     */
    private Cancelable inviteRunnable = null;

    /**
     * The {@link Clock} used by this participant.
     */
    private Clock clock = Clock.systemUTC();

    /**
     * The list stored the timestamp when the last restart requests have been received for this participant and is used
     * for rate limiting. See {@link #incrementAndCheckRestartRequests()} for more details.
     */
    private final Deque<Instant> restartRequests = new LinkedList<>();

    /**
     * MUC chat member of this participant.
     */
    @NotNull
    private final ChatRoomMember roomMember;

    /**
     * Jingle session (if any) established with this peer.
     */
    private JingleSession jingleSession;

    private final Logger logger;

    /**
     * The list of XMPP features supported by this participant.
     */
    @NotNull
    private final List<String> supportedFeatures;

    /**
     * The conference in which this participant participates.
     */
    private final JitsiMeetConferenceImpl conference;

    /**
     * The task, if any, currently scheduled to signal queued remote sources.
     */
    private ScheduledFuture<?> signalQueuedSourcesTask;

    /**
     * The lock used when queueing remote sources to be signaled with a delay, i.e. when setting
     * {@link #signalQueuedSourcesTask}.
     */
    private final Object signalQueuedSourcesTaskSyncRoot = new Object();

    /**
     * Creates new {@link Participant} for given chat room member.
     *
     * @param roomMember the {@link ChatRoomMember} that represent this
     *                   participant in MUC conference room.
     */
    public Participant(
            @NotNull ChatRoomMember roomMember,
            @NotNull List<String> supportedFeatures,
            Logger parentLogger,
            JitsiMeetConferenceImpl conference)
    {
        this.supportedFeatures = supportedFeatures;
        this.conference = conference;
        this.roomMember = roomMember;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("participant", getEndpointId());
    }

    public Participant(
            @NotNull ChatRoomMember roomMember,
            Logger parentLogger,
            JitsiMeetConferenceImpl conference)
    {
        this(roomMember, DiscoveryUtil.getDefaultParticipantFeatureSet(), parentLogger, conference);
    }

    /**
     * Replaces the channel allocator thread, which is currently allocating channels for this participant (if any)
     * with the specified channel allocator (if any).
     * @param inviteRunnable the channel allocator to set, or {@code null} to clear it.
     */
    void setInviteRunnable(Cancelable inviteRunnable)
    {
        synchronized (inviteRunnableSyncRoot)
        {
            if (this.inviteRunnable != null)
            {
                // There is an ongoing thread allocating channels and sending
                // an invite for this participant. Tell it to stop.
                logger.warn("Canceling " + this.inviteRunnable);
                this.inviteRunnable.cancel();
            }

            this.inviteRunnable = inviteRunnable;
        }
    }

    /**
     * Signals to this {@link Participant} that a specific channel allocator has completed its task and its thread
     * is about to terminate.
     * @param channelAllocator the channel allocator which has completed its task and its thread is about to terminate.
     */
    public void inviteRunnableCompleted(Cancelable channelAllocator)
    {
        synchronized (inviteRunnableSyncRoot)
        {
            if (this.inviteRunnable == channelAllocator)
            {
                this.inviteRunnable = null;
            }
        }
    }

    /**
     * Returns {@link JingleSession} established with this conference
     * participant or <tt>null</tt> if there is no session yet.
     */
    public JingleSession getJingleSession()
    {
        return jingleSession;
    }

    /**
     * Sets the new clock instance to be used by this participant. Meant for testing.
     *
     * @param newClock - the new {@link Clock}
     */
    public void setClock(Clock newClock)
    {
        this.clock = newClock;
    }

    /**
     * Sets {@link JingleSession} established with this peer.
     *
     * @param jingleSession the new Jingle session to be assigned to this peer.
     */
    public void setJingleSession(JingleSession jingleSession)
    {
        this.jingleSession = jingleSession;
    }

    /**
     * Returns {@link ChatRoomMember} that represents this participant in
     * conference multi-user chat room.
     */
    @NotNull
    public ChatRoomMember getChatMember()
    {
        return roomMember;
    }

    /**
     * @return {@link Clock} used by this participant instance.
     */
    public Clock getClock()
    {
        return clock;
    }

    /**
     * Returns <tt>true</tt> if this participant supports RTP bundle and RTCP
     * mux.
     */
    public boolean hasBundleSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_RTCP_MUX)
                && supportedFeatures.contains(DiscoveryUtil.FEATURE_RTP_BUNDLE);
    }

    /**
     * @return {@code true} if this participant supports source name signaling.
     */
    public boolean hasSourceNameSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_SOURCE_NAMES);
    }

    /**
     * @return {@code true} if this participant supports receiving Jingle sources encoded as JSON instead of the
     * standard Jingle encoding.
     */
    public boolean supportsJsonEncodedSources()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_JSON_SOURCES);
    }

    /**
     * Returns <tt>true</tt> if this participant supports 'lip-sync' or
     * <tt>false</tt> otherwise.
     */
    public boolean hasLipSyncSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_LIPSYNC);
    }

    /**
     * Returns {@code true} iff this participant supports REMB.
     */
    public boolean hasRembSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_REMB);
    }

    /**
     * Returns {@code true} iff this participant supports TCC.
     */
    public boolean hasTccSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_TCC);
    }

    /**
     * Returns {@code true} iff this participant supports RTX.
     */
    public boolean hasRtxSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_RTX);
    }

    /**
     * Returns {@code true} iff this participant supports RED for opus.
     */
    public boolean hasOpusRedSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_OPUS_RED);
    }

    /**
     * Rate limiting mechanism for session restart requests received from participants.
     * The rules ar as follows:
     * - must be at least 10 second gap between the requests
     * - no more than 3 requests within the last minute
     *
     * @return {@code true} if it's okay to process the request, as in it doesn't violate the current rate limiting
     * policy, or {@code false} if the request should be denied.
     */
    public boolean incrementAndCheckRestartRequests()
    {
        final Instant now = Instant.now(clock);
        Instant previousRequest = this.restartRequests.peekLast();

        if (previousRequest == null)
        {
            this.restartRequests.add(now);

            return true;
        }

        if (previousRequest.until(now, SECONDS) < 10)
        {
            return false;
        }

        // Allow only 3 requests within the last minute
        this.restartRequests.removeIf(requestTime -> requestTime.until(now, SECONDS) > 60);
        if (this.restartRequests.size() > 2)
        {
            return false;
        }

        this.restartRequests.add(now);

        return true;
    }

    /**
     * Returns <tt>true</tt> if RTP audio is supported by this peer.
     */
    public boolean hasAudioSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO);
    }

    /**
     * Returns <tt>true</tt> if RTP audio can be muted for this peer.
     */
    public boolean hasAudioMuteSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO_MUTE);
    }

    /**
     * Returns <tt>true</tt> if RTP video is supported by this peer.
     */
    public boolean hasVideoSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_VIDEO);
    }

    /**
     * Returns <tt>true</tt> if this peer supports DTLS/SCTP.
     */
    public boolean hasSctpSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_SCTP);
    }

    /**
     * Returns the endpoint ID for this participant in the videobridge(Colibri)
     * context.
     */
    public String getEndpointId()
    {
        return getEndpointId(roomMember);
    }

    /**
     * Returns the stats ID of the participant.
     *
     * @return the stats ID of the participant.
     */
    public String getStatId()
    {
        return roomMember.getStatsId();
    }

    /**
     * Returns the MUC JID of this <tt>Participant</tt>.
     *
     * @return full MUC address e.g. "room1@muc.server.net/nickname"
     */
    public EntityFullJid getMucJid()
    {
        return roomMember.getOccupantJid();
    }

    /**
     * Gets the sources advertised by this participant. They are stored in a common map by the conference.
     *
     * @return
     */
    @NotNull
    public ConferenceSourceMap getSources()
    {
        return conference == null ? new ConferenceSourceMap() : conference.getSourcesForParticipant(this);
    }

    /**
     * Add a set of remote sources, which are to be signaled to the remote side. The sources may be signaled
     * immediately, or queued to be signaled later.
     *
     * @param sources the sources to add.
     */
    public void addRemoteSources(ConferenceSourceMap sources)
    {
        if (!hasAudioSupport() || !hasVideoSupport())
        {
            sources = sources.copy().stripByMediaType(getSupportedMediaTypes());
        }

        JingleSession jingleSession = getJingleSession();
        if (jingleSession == null)
        {
            logger.debug("No Jingle session yet, queueing source-add.");
            remoteSourcesQueue.sourceAdd(sources);
            // No need to schedule, the sources will be signaled when the session is established.
            return;
        }

        int delayMs = ConferenceConfig.config.getSourceSignalingDelayMs(conference.getParticipantCount());
        if (delayMs > 0)
        {
            synchronized (signalQueuedSourcesTaskSyncRoot)
            {
                remoteSourcesQueue.sourceAdd(sources);
                scheduleSignalingOfQueuedSources(delayMs);
            }
        }
        else
        {
            OperationSetJingle jingle = conference.getJingle();
            if (jingle == null)
            {
                logger.error("Can not send Jingle source-add, no Jingle API available.");
                return;
            }
            jingle.sendAddSourceIQ(
                    sources,
                    jingleSession,
                    ConferenceConfig.config.getUseJsonEncodedSources() && supportsJsonEncodedSources());
        }
    }

    /**
     * Schedule a task to signal all queued remote sources to the remote side. If a task is already scheduled, does
     * not schedule a new one (the existing task will send all latest queued sources).
     *
     * @param delayMs the delay in milliseconds after which the task is to execute.
     */
    private void scheduleSignalingOfQueuedSources(int delayMs)
    {
        synchronized (signalQueuedSourcesTaskSyncRoot)
        {
            if (signalQueuedSourcesTask == null)
            {
                logger.debug("Scheduling a task to signal queued remote sources after " + delayMs + " ms.");
                signalQueuedSourcesTask = TaskPools.getScheduledPool().schedule(() ->
                        {
                            synchronized (signalQueuedSourcesTaskSyncRoot)
                            {
                                sendQueuedRemoteSources();
                                signalQueuedSourcesTask = null;
                            }
                        },
                        delayMs,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    public Set<MediaType> getSupportedMediaTypes()
    {
        Set<MediaType> supportedMediaTypes = new HashSet<>();
        if (hasVideoSupport())
        {
            supportedMediaTypes.add(MediaType.VIDEO);
        }
        if (hasAudioSupport())
        {
            supportedMediaTypes.add(MediaType.AUDIO);
        }

        return supportedMediaTypes;
    }

    /**
     * Remove a set of remote sources, which are to be signaled as removed to the remote side. The sources may be
     * signaled immediately, or queued to be signaled later.
     *
     * @param sources the sources to remove.
     */
    public void removeRemoteSources(ConferenceSourceMap sources)
    {
        if (!hasAudioSupport() || !hasVideoSupport())
        {
            sources = sources.copy().stripByMediaType(getSupportedMediaTypes());
        }

        JingleSession jingleSession = getJingleSession();
        if (jingleSession == null)
        {
            logger.debug("No Jingle session yet, queueing source-remove.");
            remoteSourcesQueue.sourceRemove(sources);
            // No need to schedule, the sources will be signaled when the session is established.
            return;
        }

        int delayMs = ConferenceConfig.config.getSourceSignalingDelayMs(conference.getParticipantCount());
        if (delayMs > 0)
        {
            synchronized (signalQueuedSourcesTaskSyncRoot)
            {
                remoteSourcesQueue.sourceRemove(sources);
                scheduleSignalingOfQueuedSources(delayMs);
            }
        }
        else
        {
            OperationSetJingle jingle = conference.getJingle();
            if (jingle == null)
            {
                logger.error("Can not send Jingle source-remove, no Jingle API available.");
                return;
            }
            jingle.sendRemoveSourceIQ(
                    sources,
                    jingleSession,
                    ConferenceConfig.config.getUseJsonEncodedSources() && supportsJsonEncodedSources());
        }
    }

    /**
     * Signal any queued remote source modifications (either addition or removal) to the remote side.
     */
    public void sendQueuedRemoteSources()
    {
        OperationSetJingle jingle = conference.getJingle();
        if (jingle == null)
        {
            logger.error("Can not signal remote sources, no Jingle API available");
            return;
        }

        JingleSession jingleSession = getJingleSession();
        if (jingleSession == null)
        {
            logger.warn("Can not signal remote sources, Jingle session not established.");
            return;
        }

        boolean encodeSourcesAsJson
                = ConferenceConfig.config.getUseJsonEncodedSources() && supportsJsonEncodedSources();

        for (SourcesToAddOrRemove sourcesToAddOrRemove : remoteSourcesQueue.clear())
        {
            AddOrRemove action = sourcesToAddOrRemove.getAction();
            ConferenceSourceMap sources = sourcesToAddOrRemove.getSources();
            logger.info("Sending a queued source-" + action.toString().toLowerCase() + ", sources:" + sources);
            if (action == AddOrRemove.Add)
            {
                jingle.sendAddSourceIQ(
                        sourcesToAddOrRemove.getSources(),
                        jingleSession,
                        encodeSourcesAsJson);
            }
            else if (action == AddOrRemove.Remove)
            {
                jingle.sendRemoveSourceIQ(sourcesToAddOrRemove.getSources(), jingleSession, encodeSourcesAsJson);
            }
        }
    }

    /**
     * Whether force-muting should be suppressed for this participant (it is a trusted participant and doesn't
     * support unmuting).
     */
    public boolean shouldSuppressForceMute()
    {
        return (getChatMember().isJigasi() && !hasAudioMuteSupport()) || getChatMember().isJibri();
    }

    /**
     * Checks whether this {@link Participant}'s role has moderator rights.
     */
    public boolean hasModeratorRights()
    {
        return MemberRoleKt.hasModeratorRights(roomMember.getRole());
    }

    @Override
    public String toString()
    {
        return "Participant[" + getMucJid() + "]@" + hashCode();
    }

    @NotNull
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject o = new OrderedJsonObject();
        o.put("id", getEndpointId());
        o.put("remote_sources_queue", remoteSourcesQueue.getDebugState());
        o.put("invite_runnable", inviteRunnable != null ? "Running" : "Not running");
        //o.put("room_member", roomMember.getDebugState());
        o.put("jingle_session", jingleSession == null ? "null" : "not null");
        return o;
    }
}
