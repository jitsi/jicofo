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

import org.jitsi.jicofo.ConferenceConfig
import org.jitsi.jicofo.TaskPools.Companion.scheduledPool
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl.InvalidBridgeSessionIdException
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl.SenderCountExceededException
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.EndpointSourceSet.Companion.fromJingle
import org.jitsi.jicofo.conference.source.ValidationFailedException
import org.jitsi.jicofo.util.Cancelable
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.jingle.JingleIqRequestHandler
import org.jitsi.jicofo.xmpp.jingle.JingleRequestHandler
import org.jitsi.jicofo.xmpp.jingle.JingleSession
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.jicofo.xmpp.muc.hasModeratorRights
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.RateLimit
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.colibri2.InitialLastN
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.Reason
import org.jitsi.xmpp.extensions.jitsimeet.BridgeSessionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.IceStatePacketExtension
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.EntityFullJid
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    /** The [JingleIqRequestHandler] with which to register the Jingle session. */
    private val jingleIqRequestHandler: JingleIqRequestHandler,
    parentLogger: Logger? = null,
    /** The list of XMPP features supported by this participant. */
    val supportedFeatures: Set<Features> = Features.defaultFeatures,
    /** The [Clock] used by this participant. */
    private val clock: Clock = Clock.systemUTC()
) {
    private val createdInstant: Instant = clock.instant()

    fun durationSeconds(): Double = Duration.between(createdInstant, clock.instant()).toMillis() / 1000.0

    /** The endpoint ID for this participant in the videobridge (Colibri) context. */
    val endpointId: String = chatMember.name

    private val logger: Logger = (
        parentLogger?.createChildLogger(Participant::javaClass.name)
            ?: LoggerImpl(Participant::javaClass.name)
        ).apply {
        addContext("participant", endpointId)
    }

    fun terminateJingleSession(reason: Reason, message: String?, sendIq: Boolean) {
        jingleSession?.terminate(reason, message, sendIq)
        jingleSession = null
    }

    /**
     * The layer which keeps track of which sources have been signaled to this participant.
     */
    private val sourceSignaling = SourceSignaling(
        audio = hasAudioSupport(),
        video = hasVideoSupport(),
        ConferenceConfig.config.stripSimulcast()
    )

    /**
     * Used to synchronize access to [.inviteRunnable].
     */
    private val inviteRunnableSyncRoot = Any()

    /**
     * The cancelable thread, if any, which is currently allocating channels for this participant.
     */
    private var inviteRunnable: Cancelable? = null

    private val restartRequestsRateLimit = RateLimit(
        defaultMinInterval = ConferenceConfig.config.restartRequestMinInterval,
        maxRequests = ConferenceConfig.config.restartRequestMaxRequests,
        interval = ConferenceConfig.config.restartRequestInterval,
        clock = clock
    )

    /**
     * The Jingle session (if any) established with this peer.
     */
    var jingleSession: JingleSession? = null
        private set

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
     * Whether this participant is a "user participant" for the purposes of
     * [JitsiMeetConferenceImpl.getUserParticipantCount].
     * Needs to be unchanging so counts don't get out of sync.
     */
    val isUserParticipant = !chatMember.isJibri && !chatMember.isTranscriber && chatMember.role != MemberRole.VISITOR

    /**
     * Replaces the channel allocator thread, which is currently allocating channels for this participant (if any)
     * with the specified channel allocator (if any).
     * @param inviteRunnable the channel allocator to set, or `null` to clear it.
     */
    fun setInviteRunnable(inviteRunnable: Cancelable?) {
        synchronized(inviteRunnableSyncRoot) {
            this.inviteRunnable?.let {
                // There is an ongoing thread allocating channels and sending an invite for this participant. Tell it to
                // stop.
                logger.warn("Canceling $it")
                it.cancel()
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

    /** Return `true` if this participant supports SSRC rewriting functionality. */
    fun hasSsrcRewritingSupport() = supportedFeatures.contains(Features.SSRC_REWRITING_V1)

    /** Return `true` if SSRC rewriting should be used for this participant. */
    fun useSsrcRewriting() = ConferenceConfig.config.useSsrcRewriting && hasSsrcRewritingSupport()

    /** Return `true` if this participant supports receiving Jingle sources encoded in JSON. */
    fun supportsJsonEncodedSources() = supportedFeatures.contains(Features.JSON_SOURCES)

    /** Returns `true` iff this participant supports REMB. */
    fun hasRembSupport() = supportedFeatures.contains(Features.REMB)

    /** Returns `true` iff this participant supports TCC. */
    fun hasTccSupport() = supportedFeatures.contains(Features.TCC)

    /** Returns `true` iff this participant supports RTX. */
    fun hasRtxSupport() = supportedFeatures.contains(Features.RTX)

    /** Returns `true` iff this participant supports RED for opus. */
    fun hasOpusRedSupport() = supportedFeatures.contains(Features.OPUS_RED)

    /** Returns true if RTP audio is supported by this peer. */
    fun hasAudioSupport() = supportedFeatures.contains(Features.AUDIO)

    /** Returns true if RTP video is supported by this peer. */
    fun hasVideoSupport() = supportedFeatures.contains(Features.VIDEO)

    /** Returns true if RTP audio can be muted for this peer. */
    fun hasAudioMuteSupport() = supportedFeatures.contains(Features.AUDIO_MUTE)

    /** Returns <tt>true</tt> if this peer supports DTLS/SCTP. */
    fun hasSctpSupport() = supportedFeatures.contains(Features.SCTP)

    /** Return true if a restart request should be accepted and false otherwise. */
    fun acceptRestartRequest(): Boolean = restartRequestsRateLimit.accept()

    /** The stats ID of the participant. */
    val statId: String?
        get() = chatMember.statsId

    /** The MUC JID of this <tt>Participant</tt>. */
    val mucJid: EntityFullJid = chatMember.occupantJid

    /** The sources advertised by this participant. They are stored in a common map by the conference. */
    val sources: EndpointSourceSet
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
        if (jingleSession?.isActive() == true) {
            synchronized(signalQueuedSourcesTaskSyncRoot) { scheduleSignalingOfQueuedSources() }
        }
        // No need to schedule, the queued sources will be signaled when the session becomes active.
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
                val timeOfSchedule = Instant.now()
                signalQueuedSourcesTask = scheduledPool.schedule(
                    {
                        val actualDelayMs = Duration.between(timeOfSchedule, Instant.now()).toMillis()
                        if (actualDelayMs > delayMs + 3000) {
                            logger.warn("Scheduling of sources was delayed by $actualDelayMs ms (expected $delayMs)")
                        }
                        synchronized(signalQueuedSourcesTaskSyncRoot) {
                            sendQueuedRemoteSources()
                            signalQueuedSourcesTask = null
                        }
                    },
                    delayMs.toLong(),
                    TimeUnit.MILLISECONDS
                )
                if (signalQueuedSourcesTask?.isDone == true) {
                    // In case the executor ran immediately in the same thread (i.e. in tests).
                    signalQueuedSourcesTask = null
                }
            }
        }
    }

    /**
     * Remove a set of remote sources, which are to be signaled as removed to the remote side. The sources may be
     * signaled immediately, or queued to be signaled later.
     */
    fun removeRemoteSources(sources: ConferenceSourceMap) {
        if (useSsrcRewriting()) {
            // Bridge will signal sources in this case.
            return
        }
        synchronized(sourceSignaling) { sourceSignaling.removeSources(sources) }
        if (jingleSession?.isActive() == true) {
            synchronized(signalQueuedSourcesTaskSyncRoot) { scheduleSignalingOfQueuedSources() }
        }
        // No need to schedule, the queued sources will be signaled when the session becomes active.
    }

    /**
     * Signal any queued remote source modifications (either addition or removal) to the remote side.
     */
    fun sendQueuedRemoteSources() {
        val jingleSession = jingleSession
        if (jingleSession?.isActive() != true) {
            logger.warn("Can not signal remote sources, Jingle session not established.")
            return
        }
        var modifiedSources: List<SourcesToAddOrRemove>
        synchronized(sourceSignaling) {
            modifiedSources = sourceSignaling.update()
        }
        for ((action, sources) in modifiedSources) {
            logger.info("Sending a queued source-${action.toString().lowercase()}, sources=$sources")
            if (action === AddOrRemove.Add) {
                jingleSession.addSource(sources)
            } else if (action === AddOrRemove.Remove) {
                jingleSession.removeSource(sources)
            }
        }
    }

    /**
     * Whether force-muting should be suppressed for this participant (it is a trusted participant and doesn't
     * support unmuting, or is a visitor and muting is redundant).
     */
    fun shouldSuppressForceMute() = (chatMember.isJigasi && !hasAudioMuteSupport()) || chatMember.isJibri ||
        chatMember.role == MemberRole.VISITOR

    /** Checks whether this [Participant]'s role has moderator rights. */
    fun hasModeratorRights() = chatMember.role.hasModeratorRights()
    override fun toString() = "Participant[$mucJid]"

    fun getDebugState(full: Boolean) = OrderedJsonObject().apply {
        this["id"] = endpointId
        if (full) {
            this["source_signaling"] = sourceSignaling.debugState
        }
        statId?.let { this["stats_id"] = it }
        this["invite_runnable"] = if (inviteRunnable != null) "Running" else "Not running"
        this["jingle_session"] = jingleSession?.debugState() ?: "null"
    }

    /**
     * Create a new [JingleSession] instance for this participant. Defined here and left open for easier testing.
     */
    open fun createNewJingleSession(): JingleSession {
        jingleSession?.let {
            logger.info("Terminating existing jingle session")
            it.terminate(Reason.UNDEFINED, null, false)
        }

        return JingleSession(
            JingleIQ.generateSID(),
            mucJid,
            jingleIqRequestHandler,
            chatMember.chatRoom.xmppProvider.xmppConnection,
            JingleRequestHandlerImpl(),
            ConferenceConfig.config.useJsonEncodedSources && supportsJsonEncodedSources()
        ).also {
            jingleSession = it
        }
    }

    private inner class JingleRequestHandlerImpl : JingleRequestHandler {
        private fun checkJingleSession(jingleSession: JingleSession): StanzaError? =
            if (this@Participant.jingleSession != jingleSession) {
                StanzaError.from(StanzaError.Condition.item_not_found, "jingle session no longer active").build()
            } else {
                null
            }

        override fun onAddSource(jingleSession: JingleSession, contents: List<ContentPacketExtension>): StanzaError? {
            checkJingleSession(jingleSession)?.let { return it }

            if (chatMember.role === MemberRole.VISITOR) {
                return StanzaError.from(StanzaError.Condition.forbidden, "add-source not allowed for visitors").build()
            }

            val sourcesAdvertised = fromJingle(contents)
            logger.debug { "Received source-add: $sourcesAdvertised" }
            if (sourcesAdvertised.isEmpty()) {
                logger.warn("Received source-add with empty sources, ignoring")
                return null
            }

            try {
                conference.addSource(this@Participant, sourcesAdvertised)
            } catch (e: SenderCountExceededException) {
                logger.warn("Rejecting source-add: ${e.message}")
                return StanzaError.from(StanzaError.Condition.resource_constraint, e.message).build()
            } catch (e: ValidationFailedException) {
                logger.warn("Rejecting source-add: ${e.message}")
                return StanzaError.from(StanzaError.Condition.bad_request, e.message).build()
            }

            return null
        }

        override fun onRemoveSource(
            jingleSession: JingleSession,
            contents: List<ContentPacketExtension>
        ): StanzaError? {
            checkJingleSession(jingleSession)?.let { return it }

            val sources = fromJingle(contents)
            if (sources.isEmpty()) {
                logger.info("Ignoring source-remove with no sources specified.")
                return null
            }

            try {
                conference.removeSources(this@Participant, sources)
            } catch (e: ValidationFailedException) {
                return StanzaError.from(StanzaError.Condition.bad_request, e.message).build()
            }

            return null
        }

        override fun onSessionAccept(jingleSession: JingleSession, contents: List<ContentPacketExtension>) =
            onSessionOrTransportAccept(jingleSession, contents, JingleAction.SESSION_ACCEPT)

        private fun onSessionOrTransportAccept(
            jingleSession: JingleSession,
            contents: List<ContentPacketExtension>,
            action: JingleAction
        ): StanzaError? {
            if (this@Participant.jingleSession != null && this@Participant.jingleSession != jingleSession) {
                logger.error("Rejecting $action for a session that has been replaced.")
                return StanzaError.from(StanzaError.Condition.gone, "session has been replaced").build()
            }

            logger.info("Received $action")
            val sourcesAdvertised = fromJingle(contents)
            if (!sourcesAdvertised.isEmpty() && this@Participant.chatMember.role == MemberRole.VISITOR) {
                return StanzaError.from(StanzaError.Condition.forbidden, "sources not allowed for visitors").build()
            }
            val initialLastN: InitialLastN? =
                contents.find { it.name == "video" }?.getChildExtension(InitialLastN::class.java)

            try {
                conference.acceptSession(this@Participant, sourcesAdvertised, contents.getTransport(), initialLastN)
            } catch (e: ValidationFailedException) {
                return StanzaError.from(StanzaError.Condition.bad_request, e.message).build()
            }

            return null
        }

        override fun onSessionInfo(jingleSession: JingleSession, iq: JingleIQ): StanzaError? {
            checkJingleSession(jingleSession)?.let { return it }

            val iceState = iq.getExtension(IceStatePacketExtension::class.java)?.text
            if (!iceState.equals("failed", ignoreCase = true)) {
                logger.info("Ignored unknown ice-state: $iceState")
                return null
            }
            ConferenceMetrics.participantsIceFailed.inc()

            val bridgeSessionId = iq.getExtension(BridgeSessionPacketExtension::class.java)?.id

            conference.iceFailed(this@Participant, bridgeSessionId)
            return null
        }

        override fun onSessionTerminate(jingleSession: JingleSession, iq: JingleIQ): StanzaError? {
            checkJingleSession(jingleSession)?.let { return it }

            val bridgeSessionPacketExtension = iq.getExtension(BridgeSessionPacketExtension::class.java)
            val restartRequested = bridgeSessionPacketExtension?.isRestart ?: false
            val bridgeSessionId = bridgeSessionPacketExtension?.id
            if (restartRequested) {
                ConferenceMetrics.participantsRequestedRestart.inc()
            }
            val reinvite = restartRequested && acceptRestartRequest()
            logger.info("Received session-terminate, bsId=$bridgeSessionId, restartRequested=$restartRequested")

            try {
                conference.terminateSession(this@Participant, bridgeSessionPacketExtension?.id, reinvite)
            } catch (e: InvalidBridgeSessionIdException) {
                return StanzaError.from(StanzaError.Condition.item_not_found, e.message).build()
            }

            // Note we still handle the termination above, we just don't start a new session.
            if (restartRequested && !reinvite) {
                logger.warn("Rate limiting restart request.")
                return StanzaError.from(StanzaError.Condition.resource_constraint, "rate-limited").build()
            }

            return null
        }

        override fun onTransportInfo(
            jingleSession: JingleSession,
            contents: List<ContentPacketExtension>
        ): StanzaError? {
            logger.info("Received transport-info")
            if (chatMember.isJigasi) {
                // Jigasi insists on using trickle and only includes ICE credentials in a transport-info sent before
                // session-accept. So we need to always accept transport-info.
                if (this@Participant.jingleSession != null && this@Participant.jingleSession != jingleSession) {
                    // If we somehow end up with more than one session we can't use the normal check to ignore requests
                    // for the old one. Ideally the flow that sets Participant.jingleSession should be cleaned up
                    // to simply contain the latest session, but until then just log a warning.
                    logger.warn("Accepting transport-info for a different (new?) jingle session")
                }
            } else {
                checkJingleSession(jingleSession)?.let {
                    // It's technically allowed to send transport-info before the session is active (XEPs 166, 176), but
                    // we prefer to avoid trickle at all and don't expect to see it.
                    logger.warn("Received an early or stale transport-info from non-jigasi.")
                }
            }

            val transport = contents.getTransport()
                ?: return StanzaError.from(StanzaError.Condition.bad_request, "missing transport").build()
            conference.updateTransport(this@Participant, transport)

            return null
        }

        override fun onTransportAccept(jingleSession: JingleSession, contents: List<ContentPacketExtension>) =
            onSessionOrTransportAccept(jingleSession, contents, JingleAction.TRANSPORT_ACCEPT)

        override fun onTransportReject(jingleSession: JingleSession, iq: JingleIQ) {
            checkJingleSession(jingleSession)?.let { return }

            logger.warn("Received transport-reject: ${iq.toXML()}")
        }
    }
}
