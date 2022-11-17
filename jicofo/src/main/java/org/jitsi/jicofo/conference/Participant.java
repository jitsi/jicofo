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
import org.jitsi.jicofo.xmpp.jingle.*;
import org.jitsi.jicofo.xmpp.muc.*;
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
     * The layer which keeps track of which sources have been signaled to this participant.
     */
    private final SourceSignaling sourceSignaling;

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
     * Whether the screensharing source of this participant (if it exists) is muted. If a screensharing source doesn't
     * exists this stays false (though the source and the mute status are communicated separately so they may not
     * always be in sync)
     */
    private boolean desktopSourceIsMuted = false;

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
        updateDesktopSourceIsMuted(roomMember.getSourceInfos());
        this.logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("participant", getEndpointId());
        sourceSignaling = new SourceSignaling(
                hasAudioSupport(),
                hasVideoSupport(),
                ConferenceConfig.config.stripSimulcast(),
                supportsReceivingMultipleVideoStreams() || !ConferenceConfig.config.getMultiStreamBackwardCompat()
        );
    }

    /**
     * Notify this {@link Participant} that the underlying {@link ChatRoomMember}'s presence changed.
     */
    void presenceChanged()
    {
        if (updateDesktopSourceIsMuted(roomMember.getSourceInfos()))
        {
            conference.desktopSourceIsMutedChanged(this, desktopSourceIsMuted);
        }
    }

    /**
     * Update the value of {@link #desktopSourceIsMuted} based on the advertised {@link SourceInfo}s.
     * @return true if the value of {@link #desktopSourceIsMuted} changed as a result of this call.
     */
    private boolean updateDesktopSourceIsMuted(@NotNull Set<SourceInfo> sourceInfos)
    {
        boolean newValue = sourceInfos.stream().anyMatch(si -> si.getVideoType() == VideoType.Desktop && si.getMuted());
        if (desktopSourceIsMuted != newValue)
        {
            desktopSourceIsMuted = newValue;
            return true;
        }
        return false;
    }

    /**
     * Notify this participant that another participant's (identified by {@code owner}) screensharing source was muted
     * or unmuted.
     */
    void remoteDesktopSourceIsMutedChanged(Jid owner, Boolean muted)
    {
        // This is only needed for backwards compatibility with clients that don't support receiving multiple streams.
        if (supportsReceivingMultipleVideoStreams())
        {
            return;
        }

        sourceSignaling.remoteDesktopSourceIsMutedChanged(owner, muted);
        // Signal updates, if any, immediately.
        synchronized (signalQueuedSourcesTaskSyncRoot)
        {
            scheduleSignalingOfQueuedSources();
        }
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
     * @return {@code true} if this participant supports source name signaling.
     */
    public boolean hasSourceNameSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_SOURCE_NAMES);
    }

    /**
     * @return {@code true} if this participant supports SSRC rewriting functionality.
     */
    public boolean hasSsrcRewritingSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_SSRC_REWRITING);
    }

    /**
     * @return {@code true} if SSRC rewriting should be used for this participant.
     */
    public boolean useSsrcRewriting()
    {
        return ConferenceConfig.config.getUseSsrcRewriting() && hasSsrcRewritingSupport();
    }

    /**
     * @return {@code true} if this participant supports receiving Jingle sources encoded as JSON instead of the
     * standard Jingle encoding.
     */
    public boolean supportsJsonEncodedSources()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_JSON_SOURCES);
    }

    public boolean supportsReceivingMultipleVideoStreams()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_RECEIVE_MULTIPLE_STREAMS);
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
        if (useSsrcRewriting())
        {
            // Bridge will signal sources in this case.
            return;
        }

        synchronized (sourceSignaling)
        {
            sourceSignaling.addSources(sources);
        }

        JingleSession jingleSession = getJingleSession();
        if (jingleSession == null)
        {
            logger.debug("No Jingle session yet, queueing source-add.");
            // No need to schedule, the sources will be signaled when the session is established.
            return;
        }

        synchronized (signalQueuedSourcesTaskSyncRoot)
        {
            scheduleSignalingOfQueuedSources();
        }
    }

    /**
     * Reset the set of sources that have been signaled to the participant.
     * @param sources set of remote sources to be signaled to the participant (pre-filtering!)
     * @return the set of sources that should be signaled in the initial offer (after filtering is applied!)
     */
    @NotNull
    public ConferenceSourceMap resetSignaledSources(@NotNull ConferenceSourceMap sources)
    {
        synchronized (sourceSignaling)
        {
            return sourceSignaling.reset(sources);
        }
    }

    /**
     * Schedule a task to signal all queued remote sources to the remote side. If a task is already scheduled, does
     * not schedule a new one (the existing task will send all latest queued sources).
     */
    private void scheduleSignalingOfQueuedSources()
    {
        int delayMs = ConferenceConfig.config.getSourceSignalingDelayMs(conference.getParticipantCount());
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

    /**
     * Remove a set of remote sources, which are to be signaled as removed to the remote side. The sources may be
     * signaled immediately, or queued to be signaled later.
     *
     * @param sources the sources to remove.
     */
    public void removeRemoteSources(ConferenceSourceMap sources)
    {
        if (useSsrcRewriting())
        {
            // Bridge will signal sources in this case.
            return;
        }

        synchronized (sourceSignaling)
        {
            sourceSignaling.removeSources(sources);
        }

        JingleSession jingleSession = getJingleSession();
        if (jingleSession == null)
        {
            logger.debug("No Jingle session yet, queueing source-remove.");
            // No need to schedule, the sources will be signaled when the session is established.
            return;
        }

        synchronized (signalQueuedSourcesTaskSyncRoot)
        {
            scheduleSignalingOfQueuedSources();
        }
    }

    @NotNull JingleApi getJingleApi()
    {
        return roomMember.getChatRoom().getXmppProvider().getJingleApi();
    }

    /**
     * Signal any queued remote source modifications (either addition or removal) to the remote side.
     */
    public void sendQueuedRemoteSources()
    {
        JingleSession jingleSession = getJingleSession();

        if (jingleSession == null)
        {
            logger.warn("Can not signal remote sources, Jingle session not established.");
            return;
        }

        for (SourcesToAddOrRemove sourcesToAddOrRemove : sourceSignaling.update())
        {
            AddOrRemove action = sourcesToAddOrRemove.getAction();
            ConferenceSourceMap sources = sourcesToAddOrRemove.getSources();
            logger.info("Sending a queued source-" + action.toString().toLowerCase() + ", sources:" + sources);
            if (action == AddOrRemove.Add)
            {
                jingleSession.addSource(sources);
            }
            else if (action == AddOrRemove.Remove)
            {
                jingleSession.removeSource(sources);
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
        o.put("source_signaling", sourceSignaling.getDebugState());
        o.put("invite_runnable", inviteRunnable != null ? "Running" : "Not running");
        //o.put("room_member", roomMember.getDebugState());
        o.put("jingle_session", jingleSession == null ? "null" : "not null");
        return o;
    }
}
