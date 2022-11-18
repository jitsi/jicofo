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
package org.jitsi.jicofo.conference

import org.jitsi.impl.protocol.xmpp.ChatRoomMember
import org.jitsi.jicofo.ConferenceConfig
import org.jitsi.jicofo.TaskPools.Companion.scheduledPool
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.VideoType
import org.jitsi.jicofo.discovery.DiscoveryUtil
import org.jitsi.jicofo.util.Cancelable
import org.jitsi.jicofo.util.RateLimit
import org.jitsi.jicofo.xmpp.jingle.JingleSession
import org.jitsi.jicofo.xmpp.muc.SourceInfo
import org.jitsi.jicofo.xmpp.muc.hasModeratorRights
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import java.time.Clock
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Class represent Jitsi Meet conference participant. Stores information about
 * Colibri channels allocated, Jingle session and media sources.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
open class Participant @JvmOverloads constructor(
    /** The associated chat member. */
    val chatMember: ChatRoomMember,
    /** The conference in which this participant participates. */
    private val conference: JitsiMeetConferenceImpl,
    parentLogger: Logger? = null,
    /** The list of XMPP features supported by this participant. */
    private val supportedFeatures: List<String> = DiscoveryUtil.getDefaultParticipantFeatureSet(),
    /** The [Clock] used by this participant. */
    private val clock: Clock = Clock.systemUTC()
) {
    /** The endpoint ID for this participant in the videobridge (Colibri) context. */
    val endpointId: String = chatMember.name

    private val logger: Logger = (parentLogger?.createChildLogger(Participant::javaClass.name)
        ?: LoggerImpl(Participant::javaClass.name)).apply {
        addContext("participant", endpointId)
    }

    /**
     * The layer which keeps track of which sources have been signaled to this participant.
     */
    private val sourceSignaling = SourceSignaling(
        hasAudioSupport(),
        hasVideoSupport(),
        ConferenceConfig.config.stripSimulcast(),
        supportsReceivingMultipleVideoStreams() || !ConferenceConfig.config.multiStreamBackwardCompat
    )

    /**
     * Used to synchronize access to [.inviteRunnable].
     */
    private val inviteRunnableSyncRoot = Any()

    /**
     * The cancelable thread, if any, which is currently allocating channels for this participant.
     */
    private var inviteRunnable: Cancelable? = null

    private val restartRequestsRateLimit = RateLimit(clock = clock)

    /**
     * The Jingle session (if any) established with this peer.
     */
    var jingleSession: JingleSession? = null

    /**
     * The task, if any, currently scheduled to signal queued remote sources.
     */
    private var signalQueuedSourcesTask: ScheduledFuture<*>? = null

    /**
     * The lock used when queueing remote sources to be signaled with a delay, i.e. when setting
     * [.signalQueuedSourcesTask].
     */
    private val signalQueuedSourcesTaskSyncRoot = Any()

    /**
     * Whether the screensharing source of this participant (if it exists) is muted. If a screensharing source doesn't
     * exists this stays false (though the source and the mute status are communicated separately so they may not
     * always be in sync)
     */
    private var desktopSourceIsMuted = false

    init {
        updateDesktopSourceIsMuted(chatMember.sourceInfos)
    }

    /**
     * Notify this [Participant] that the underlying [ChatRoomMember]'s presence changed.
     */
    fun presenceChanged() {
        if (updateDesktopSourceIsMuted(chatMember.sourceInfos)) {
            conference.desktopSourceIsMutedChanged(this, desktopSourceIsMuted)
        }
    }

    /**
     * Update the value of [.desktopSourceIsMuted] based on the advertised [SourceInfo]s.
     * @return true if the value of [.desktopSourceIsMuted] changed as a result of this call.
     */
    private fun updateDesktopSourceIsMuted(sourceInfos: Set<SourceInfo>): Boolean {
        val newValue = sourceInfos.stream()
            .anyMatch { (_, muted, videoType): SourceInfo -> videoType === VideoType.Desktop && muted }
        if (desktopSourceIsMuted != newValue) {
            desktopSourceIsMuted = newValue
            return true
        }
        return false
    }

    /**
     * Notify this participant that another participant's (identified by `owner`) screensharing source was muted
     * or unmuted.
     */
    fun remoteDesktopSourceIsMutedChanged(owner: Jid, muted: Boolean) {
        // This is only needed for backwards compatibility with clients that don't support receiving multiple streams.
        if (supportsReceivingMultipleVideoStreams()) {
            return
        }
        sourceSignaling.remoteDesktopSourceIsMutedChanged(owner, muted)
        // Signal updates, if any, immediately.
        synchronized(signalQueuedSourcesTaskSyncRoot) { scheduleSignalingOfQueuedSources() }
    }

    /**
     * Replaces the channel allocator thread, which is currently allocating channels for this participant (if any)
     * with the specified channel allocator (if any).
     * @param inviteRunnable the channel allocator to set, or `null` to clear it.
     */
    fun setInviteRunnable(inviteRunnable: Cancelable?) {
        synchronized(inviteRunnableSyncRoot) {
            if (this.inviteRunnable != null) {
                // There is an ongoing thread allocating channels and sending
                // an invite for this participant. Tell it to stop.
                logger.warn("Canceling " + this.inviteRunnable)
                this.inviteRunnable!!.cancel()
            }
            this.inviteRunnable = inviteRunnable
        }
    }

    /**
     * Signals to this [Participant] that a specific channel allocator has completed its task and its thread
     * is about to terminate.
     * @param channelAllocator the channel allocator which has completed its task and its thread is about to terminate.
     */
    fun inviteRunnableCompleted(channelAllocator: Cancelable) {
        synchronized(inviteRunnableSyncRoot) {
            if (inviteRunnable === channelAllocator) {
                inviteRunnable = null
            }
        }
    }

    /** Return `true` if this participant supports source name signaling. */
    fun hasSourceNameSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_SOURCE_NAMES)
    /** Return `true` if this participant supports SSRC rewriting functionality. */
    fun hasSsrcRewritingSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_SSRC_REWRITING)
    /** Return `true` if SSRC rewriting should be used for this participant. */
    fun useSsrcRewriting() = ConferenceConfig.config.useSsrcRewriting && hasSsrcRewritingSupport()
    /** Return `true` if this participant supports receiving Jingle sources encoded in JSON. */
    fun supportsJsonEncodedSources() = supportedFeatures.contains(DiscoveryUtil.FEATURE_JSON_SOURCES)
    fun supportsReceivingMultipleVideoStreams() =
        supportedFeatures.contains(DiscoveryUtil.FEATURE_RECEIVE_MULTIPLE_STREAMS)
    /** Returns `true` iff this participant supports REMB. */
    fun hasRembSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_REMB)
    /** Returns `true` iff this participant supports TCC. */
    fun hasTccSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_TCC)
    /** Returns `true` iff this participant supports RTX. */
    fun hasRtxSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_RTX)
    /** Returns `true` iff this participant supports RED for opus. */
    fun hasOpusRedSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_OPUS_RED)
    /** Returns true if RTP audio is supported by this peer. */
    fun hasAudioSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO)
    /** Returns true if RTP video is supported by this peer. */
    fun hasVideoSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_VIDEO)
    /** Returns true if RTP audio can be muted for this peer. */
    fun hasAudioMuteSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO_MUTE)
    /** Returns <tt>true</tt> if this peer supports DTLS/SCTP. */
    fun hasSctpSupport() = supportedFeatures.contains(DiscoveryUtil.FEATURE_SCTP)

    /** Return true if a restart request should be accepted and false otherwise. */
    fun acceptRestartRequest(): Boolean = restartRequestsRateLimit.accept()

    /** The stats ID of the participant. */
    val statId: String?
        get() = chatMember.statsId

    /** The MUC JID of this <tt>Participant</tt>. */
    val mucJid: EntityFullJid = chatMember.occupantJid

    /** The sources advertised by this participant. They are stored in a common map by the conference. */
    val sources: ConferenceSourceMap
        get() = conference.getSourcesForParticipant(this)

    /**
     * Add a set of remote sources, which are to be signaled to the remote side. The sources may be signaled
     * immediately, or queued to be signaled later.
     */
    fun addRemoteSources(sources: ConferenceSourceMap) {
        if (useSsrcRewriting()) {
            // Bridge will signal sources in this case.
            return
        }
        synchronized(sourceSignaling) { sourceSignaling.addSources(sources) }
        if (jingleSession == null) {
            logger.debug("No Jingle session yet, queueing source-add.")
            // No need to schedule, the sources will be signaled when the session is established.
            return
        }
        synchronized(signalQueuedSourcesTaskSyncRoot) { scheduleSignalingOfQueuedSources() }
    }

    /**
     * Reset the set of sources that have been signaled to the participant.
     * @param sources set of remote sources to be signaled to the participant (pre-filtering!)
     * @return the set of sources that should be signaled in the initial offer (after filtering is applied!)
     */
    fun resetSignaledSources(sources: ConferenceSourceMap): ConferenceSourceMap {
        synchronized(sourceSignaling) { return sourceSignaling.reset(sources) }
    }

    /**
     * Schedule a task to signal all queued remote sources to the remote side. If a task is already scheduled, does
     * not schedule a new one (the existing task will send all latest queued sources).
     */
    private fun scheduleSignalingOfQueuedSources() {
        val delayMs = ConferenceConfig.config.getSourceSignalingDelayMs(conference.participantCount)
        synchronized(signalQueuedSourcesTaskSyncRoot) {
            if (signalQueuedSourcesTask == null) {
                logger.debug("Scheduling a task to signal queued remote sources after $delayMs ms.")
                signalQueuedSourcesTask = scheduledPool.schedule(
                    {
                        synchronized(signalQueuedSourcesTaskSyncRoot) {
                            sendQueuedRemoteSources()
                            signalQueuedSourcesTask = null
                        }
                    },
                    delayMs.toLong(),
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /**
     * Remove a set of remote sources, which are to be signaled as removed to the remote side. The sources may be
     * signaled immediately, or queued to be signaled later.
     */
    fun removeRemoteSources(sources: ConferenceSourceMap?) {
        if (useSsrcRewriting()) {
            // Bridge will signal sources in this case.
            return
        }
        synchronized(sourceSignaling) { sourceSignaling.removeSources(sources!!) }
        if (jingleSession == null) {
            logger.debug("No Jingle session yet, queueing source-remove.")
            // No need to schedule, the sources will be signaled when the session is established.
            return
        }
        synchronized(signalQueuedSourcesTaskSyncRoot) { scheduleSignalingOfQueuedSources() }
    }

    /**
     * Signal any queued remote source modifications (either addition or removal) to the remote side.
     */
    fun sendQueuedRemoteSources() {
        val jingleSession = jingleSession
        if (jingleSession == null) {
            logger.warn("Can not signal remote sources, Jingle session not established.")
            return
        }
        for ((action, sources) in sourceSignaling.update()) {
            logger.info(
                "Sending a queued source-" + action.toString().lowercase(Locale.getDefault()) + ", sources:" + sources
            )
            if (action === AddOrRemove.Add) {
                jingleSession.addSource(sources)
            } else if (action === AddOrRemove.Remove) {
                jingleSession.removeSource(sources)
            }
        }
    }

    /**
     * Whether force-muting should be suppressed for this participant (it is a trusted participant and doesn't
     * support unmuting).
     */
    fun shouldSuppressForceMute() = chatMember.isJigasi && !hasAudioMuteSupport() || chatMember.isJibri
    /** Checks whether this [Participant]'s role has moderator rights. */
    fun hasModeratorRights() = chatMember.role.hasModeratorRights()
    override fun toString() = "Participant[$mucJid]"

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            this["id"] = endpointId
            this["source_signaling"] = sourceSignaling.debugState
            this["invite_runnable"] = if (inviteRunnable != null) "Running" else "Not running"
            this["jingle_session"] = if (jingleSession == null) "null" else "not null"
        }

    /**
     * Create a new [JingleSession] instance for this participant. Defined here and left open for easier testing.
     */
    open fun createNewJingleSession(): JingleSession = JingleSession(
        JingleIQ.generateSID(),
        mucJid,
        chatMember.chatRoom.xmppProvider.jingleIqRequestHandler,
        chatMember.chatRoom.xmppProvider.xmppConnection,
        conference,
        ConferenceConfig.config.useJsonEncodedSources && supportsJsonEncodedSources()
    )
}