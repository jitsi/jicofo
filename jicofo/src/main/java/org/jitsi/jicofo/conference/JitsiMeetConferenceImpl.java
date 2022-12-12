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
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.bridge.colibri.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.version.*;
import org.jitsi.jicofo.visitors.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.UtilKt;
import org.jitsi.jicofo.xmpp.jingle.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.jibri.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import java.util.stream.*;

import static org.jitsi.jicofo.xmpp.IqProcessingResult.*;

/**
 * Represents a Jitsi Meet conference. Manages the Jingle sessions with the
 * participants, as well as the COLIBRI session with the jitsi-videobridge
 * instances used for the conference.
 * <p/>
 * A note on synchronization: this class uses a lot of 'synchronized' blocks,
 * on 3 different objects {@link #participantLock}, {@code this} and {@code BridgeSession#octoParticipant}).
 * <p/>
 * This seems safe, but it is hard to maintain this way, and we should
 * re-factor to simplify.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class JitsiMeetConferenceImpl
    implements JitsiMeetConference, RegistrationListener
{
    /**
     * Name of MUC room that is hosting Jitsi Meet conference.
     */
    @NotNull
    private final EntityBareJid roomName;

    /**
     * {@link ConferenceListener} that will be notified
     * about conference events.
     */
    private final JitsiMeetConferenceImpl.ConferenceListener listener;

    @NotNull
    private final Logger logger;

    /**
     * The instance of conference configuration.
     */
    @NotNull
    private final JitsiMeetConfig config;

    private final ChatRoomListener chatRoomListener = new ChatRoomListenerImpl();

    /**
     * Conference room chat instance.
     */
    private volatile ChatRoom chatRoom;

    /**
     * Map of occupant JID to Participant.
     */
    private final Map<Jid, Participant> participants = new ConcurrentHashMap<>();

    /**
     * This lock is used to synchronise write access to {@link #participants}.
     */
    private final Object participantLock = new Object();

    /**
     * The {@link JibriRecorder} instance used to provide live streaming through
     * Jibri.
     */
    private JibriRecorder jibriRecorder;

    /**
     * The {@link JibriSipGateway} instance which provides video SIP gateway
     * services for this conference.
     */
    private JibriSipGateway jibriSipGateway;

    /**
     * The {@link TranscriberManager} who listens for participants requesting
     * transcription and, when necessary, dialing the transcriber instance.
     */
    private TranscriberManager transcriberManager;

    private ChatRoomRoleManager chatRoomRoleManager;

    /**
     * Indicates if this instance has been started (initialized).
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * The time at which this conference was created.
     */
    private final Instant creationTime = Instant.now();

    /**
     * Whether at least one participant has joined this conference. This is exposed because to facilitate pruning
     * conferences without any participants (which uses a separate code path than conferences with participants).
     */
    private boolean hasHadAtLeastOneParticipant = false;

    /**
     * A timeout task which will terminate media session of the user who is
     * sitting alone in the room for too long.
     */
    private Future<?> singleParticipantTout;

    /**
     * Whether participants being invited to the conference as a result of joining (as opposed to having already
     * joined) should be invited with the "start audio muted" option.
     */
    private boolean startAudioMuted = false;

    /**
     * Whether participants being invited to the conference as a result of joining (as opposed to having already
     * joined) should be invited with the "start video muted" option.
     */
    private boolean startVideoMuted = false;

    /**
     * The name of shared Etherpad document. Is advertised through MUC Presence
     * by Jicofo user.
     */
    private final String etherpadName;

    /**
     * Maintains all colibri sessions for this conference.
     */
    private ColibriSessionManager colibriSessionManager;

    /**
     * Listener for events from {@link #colibriSessionManager}.
     */
    private final ColibriSessionManagerListener colibriSessionManagerListener = new ColibriSessionManagerListener();

    /**
     * The conference properties that we advertise in presence in the XMPP MUC.
     */
    private final ConcurrentHashMap<String, String> conferenceProperties = new ConcurrentHashMap<>();

    /**
     * See {@link JitsiMeetConference#includeInStatistics()}
     */
    private final boolean includeInStatistics;

    private final BridgeSelectorEventHandler bridgeSelectorEventHandler = new BridgeSelectorEventHandler();

    @NotNull private final JicofoServices jicofoServices;

    /**
     * Stores the sources advertised by all participants in the conference, mapped by their JID.
     */
    private final ValidatingConferenceSourceMap conferenceSources = new ValidatingConferenceSourceMap(
            ConferenceConfig.config.getMaxSsrcsPerUser(),
            ConferenceConfig.config.getMaxSsrcGroupsPerUser()
    );

    /**
     * Whether the limit on the number of audio senders is currently hit.
     */
    private boolean audioLimitReached = false;

    /**
     * Whether the limit on the number of video senders is currently hit.
     */
    private boolean videoLimitReached = false;

    /**
     * Requested bridge version from a pin. null if not pinned.
     */
    private final String jvbVersion;

    /**
     * Creates new instance of {@link JitsiMeetConferenceImpl}.
     *
     * @param roomName name of MUC room that is hosting the conference.
     * @param listener the listener that will be notified about this instance
     *        events.
     * @param config the conference configuration instance.
     * @param logLevel (optional) the logging level to be used by this instance.
     *        See {@link #logger} for more details.
     */
    public JitsiMeetConferenceImpl(
            @NotNull EntityBareJid roomName,
            ConferenceListener listener,
            @NotNull JitsiMeetConfig config,
            Level logLevel,
            String jvbVersion,
            boolean includeInStatistics,
            @NotNull JicofoServices jicofoServices)
    {
        logger = new LoggerImpl(JitsiMeetConferenceImpl.class.getName(), logLevel);
        logger.addContext("room", roomName.toString());

        this.config = config;

        this.roomName = roomName;
        this.listener = listener;
        this.etherpadName = createSharedDocumentName();
        this.includeInStatistics = includeInStatistics;

        this.jicofoServices = jicofoServices;
        this.jvbVersion = jvbVersion;

        logger.info("Created new conference.");
    }

    /**
     * @return the colibri session manager, late init.
     */
    private ColibriSessionManager getColibriSessionManager()
    {
        if (colibriSessionManager == null)
        {
            String meetingId = chatRoom == null ? null : chatRoom.getMeetingId();
            if (meetingId == null)
            {
                logger.warn("No meetingId set for the MUC. Generating one locally.");
                meetingId = UUID.randomUUID().toString();
            }

            colibriSessionManager = new ColibriV2SessionManager(
                    jicofoServices.getXmppServices().getServiceConnection().getXmppConnection(),
                    jicofoServices.getBridgeSelector(),
                    getRoomName().toString(),
                    meetingId,
                    config.getRtcStatsEnabled(),
                    jvbVersion,
                    logger);
            colibriSessionManager.addListener(colibriSessionManagerListener);
        }
        return colibriSessionManager;
    }

    /**
     * Starts conference focus processing, bind listeners and so on...
     *
     * @throws Exception if error occurs during initialization. Instance is
     *         considered broken in that case. Its stop method will be called
     *         before throwing the exception to perform deinitialization where
     *         possible. {@link ConferenceListener}s will be notified that this
     *         conference has ended.
     */
    public void start()
        throws Exception
    {
        if (!started.compareAndSet(false, true))
        {
            return;
        }

        try
        {
            XmppProvider clientXmppProvider = getClientXmppProvider();

            BridgeSelector bridgeSelector = jicofoServices.getBridgeSelector();
            bridgeSelector.addHandler(bridgeSelectorEventHandler);

            if (clientXmppProvider.isRegistered())
            {
                joinTheRoom();
            }

            JibriDetector jibriDetector = jicofoServices.getJibriDetector();
            if (jibriDetector != null)
            {
                jibriRecorder
                    = new JibriRecorder(
                            this,
                            jibriDetector,
                            logger);
            }

            JibriDetector sipJibriDetector = jicofoServices.getSipJibriDetector();
            if (sipJibriDetector != null)
            {
                jibriSipGateway
                    = new JibriSipGateway(
                            this,
                            sipJibriDetector,
                            logger);
            }
        }
        catch (Exception e)
        {
            try
            {
                stop();
            }
            catch (Exception x)
            {
                logger.warn("An exception was caught while invoking stop()", x);
            }

            throw e;
        }
    }

    /**
     * Stops the conference, disposes colibri channels and releases all
     * resources used by the focus.
     */
    public void stop()
    {
        if (!started.compareAndSet(true, false))
        {
            return;
        }

        if (jibriSipGateway != null)
        {
            try
            {
                jibriSipGateway.shutdown();
            }
            catch (Exception e)
            {
                logger.error("jibriSipGateway.shutdown error", e);
            }
            jibriSipGateway = null;
        }

        if (jibriRecorder != null)
        {
            try
            {
                jibriRecorder.shutdown();
            }
            catch (Exception e)
            {
                logger.error("jibriRecorder.shutdown error", e);
            }
            jibriRecorder = null;
        }

        BridgeSelector bridgeSelector = jicofoServices.getBridgeSelector();
        bridgeSelector.removeHandler(bridgeSelectorEventHandler);

        if (colibriSessionManager != null)
        {
            colibriSessionManager.removeListener(colibriSessionManagerListener);
        }

        try
        {
            expireBridgeSessions();
        }
        catch (Exception e)
        {
            logger.error("disposeConference error", e);
        }

        try
        {
            leaveTheRoom();
        }
        catch (Exception e)
        {
            logger.error("leaveTheRoom error", e);
        }

        logger.info("Stopped.");
        if (listener != null)
        {
            listener.conferenceEnded(this);
        }
    }

    /**
     * Returns <tt>true</tt> if the conference has been successfully started.
     */
    public boolean isStarted()
    {
        return started.get();
    }

    /**
     * Joins the conference room.
     *
     * @throws Exception if we have failed to join the room for any reason
     */
    private void joinTheRoom()
        throws Exception
    {
        logger.info("Joining " + roomName);

        ChatRoom chatRoom = getClientXmppProvider().findOrCreateRoom(roomName);
        this.chatRoom = chatRoom;
        chatRoom.addListener(chatRoomListener);

        AuthenticationAuthority authenticationAuthority = jicofoServices.getAuthenticationAuthority();
        if (authenticationAuthority != null)
        {
            chatRoomRoleManager = new AuthenticationRoleManager(chatRoom, authenticationAuthority);
            chatRoom.addListener(chatRoomRoleManager);
        }
        else if (ConferenceConfig.config.enableAutoOwner())
        {
            chatRoomRoleManager = new AutoOwnerRoleManager(chatRoom);
            chatRoom.addListener(chatRoomRoleManager);
        }

        transcriberManager = new TranscriberManager(
            jicofoServices.getXmppServices().getXmppConnectionByName(
                JigasiConfig.config.xmppConnectionName()
            ),
            this,
            chatRoom,
            jicofoServices.getXmppServices().getJigasiDetector(),
            logger);

        chatRoom.join();
        if (chatRoom.getMeetingId() != null)
        {
            logger.addContext("meeting_id", chatRoom.getMeetingId());
        }

        Collection<ExtensionElement> presenceExtensions = new ArrayList<>();

        // Advertise shared Etherpad document
        presenceExtensions.add(EtherpadPacketExt.forDocumentName(etherpadName));

        ComponentVersionsExtension versionsExtension = new ComponentVersionsExtension();
        versionsExtension.addComponentVersion(
                ComponentVersionsExtension.COMPONENT_FOCUS,
                CurrentVersionImpl.VERSION.toString());
        presenceExtensions.add(versionsExtension);

        setConferenceProperty(
            ConferenceProperties.KEY_SUPPORTS_SESSION_RESTART,
            Boolean.TRUE.toString(),
            false);

        presenceExtensions.add(createConferenceProperties());

        // updates presence with presenceExtensions and sends it
        chatRoom.modifyPresence(null, presenceExtensions);
    }

    /**
     * Sets a conference property and sends an updated presence stanza in the
     * MUC.
     * @param key the key of the property.
     * @param value the value of the property.
     */
    private void setConferenceProperty(@NotNull String key, @NotNull String value)
    {
        setConferenceProperty(key, value, true);
    }

    /**
     * Sets a conference property and optionally (depending on
     * {@code updatePresence}) sends an updated presence stanza in the
     * MUC.
     * @param key the key of the property.
     * @param value the value of the property.
     * @param updatePresence {@code true} to send an updated presence stanza,
     * and {@code false} to only add the property locally. This is useful to
     * allow updating multiple properties but sending a single presence update.
     */
    private void setConferenceProperty(@NotNull String key, @NotNull String value, boolean updatePresence)
    {
        String oldValue = conferenceProperties.put(key, value);
        if (updatePresence && chatRoom != null && !value.equals(oldValue))
        {
            chatRoom.setPresenceExtension(createConferenceProperties(), false);
        }
    }

    private ConferenceProperties createConferenceProperties()
    {
        ConferenceProperties conferenceProperties = new ConferenceProperties();
        this.conferenceProperties.forEach(conferenceProperties::put);
        return conferenceProperties;
    }

    /**
     * Process the new number of audio senders reported by the chat room.
     */
    private void onNumAudioSendersChanged(int numAudioSenders)
    {
        boolean newValue = numAudioSenders >= ConferenceConfig.config.getMaxAudioSenders();
        if (audioLimitReached != newValue)
        {
            audioLimitReached = newValue;
            setConferenceProperty("audio-limit-reached", String.valueOf(audioLimitReached));
        }
    }

    /**
     * Process the new number of video senders reported by the chat room.
     */
    private void onNumVideoSendersChanged(int numVideoSenders)
    {
        boolean newValue = numVideoSenders >= ConferenceConfig.config.getMaxVideoSenders();
        if (videoLimitReached != newValue)
        {
            videoLimitReached = newValue;
            setConferenceProperty("video-limit-reached", String.valueOf(videoLimitReached));
        }
    }

    /**
     * Leaves the conference room.
     */
    private void leaveTheRoom()
    {
        if (chatRoom == null)
        {
            logger.error("Chat room already left!");
            return;
        }

        if (chatRoomRoleManager != null)
        {
            chatRoom.removeListener(chatRoomRoleManager);
            chatRoomRoleManager.stop();
        }
        if (transcriberManager != null)
        {
            transcriberManager.dispose();
            transcriberManager = null;
        }

        chatRoom.leave();

        chatRoom.removeListener(chatRoomListener);
        chatRoom = null;
    }

    /**
     * Handles a new {@link ChatRoomMember} joining the {@link ChatRoom}: invites it as a {@link Participant} to the
     * conference if there are enough members.
     */
    private void onMemberJoined(@NotNull ChatRoomMember chatRoomMember)
    {
        synchronized (participantLock)
        {
            if (chatRoomMember.getRole() == MemberRole.VISITOR && !VisitorsConfig.config.getEnabled())
            {
                logger.warn("Ignoring a visitor because visitors are not configured:" + chatRoomMember.getName());
                return;
            }

            logger.info(
                    "Member joined:" + chatRoomMember.getName()
                            + " stats-id=" + chatRoomMember.getStatsId()
                            + " region=" + chatRoomMember.getRegion()
                            + " audioMuted=" + chatRoomMember.isAudioMuted()
                            + " videoMuted=" + chatRoomMember.isVideoMuted()
                            + " role=" + chatRoomMember.getRole()
                            + " isJibri=" + chatRoomMember.isJibri()
                            + " isJigasi=" + chatRoomMember.isJigasi());
            hasHadAtLeastOneParticipant = true;

            // Are we ready to start ?
            if (!checkMinParticipants())
            {
                return;
            }

            // Cancel single participant timeout when someone joins ?
            cancelSingleParticipantTimeout();

            // Invite all not invited yet
            if (participants.size() == 0)
            {
                for (final ChatRoomMember member : chatRoom.getMembers())
                {
                    inviteChatMember(member, member == chatRoomMember);
                }
            }
            // Only the one who has just joined
            else
            {
                inviteChatMember(chatRoomMember, true);
            }
        }
    }


    /**
     * Adds a {@link ChatRoomMember} to the conference. Creates the
     * {@link Participant} instance corresponding to the {@link ChatRoomMember}.
     * established and videobridge channels being allocated.
     *
     * @param chatRoomMember the chat member to be invited into the conference.
     * @param justJoined whether the chat room member should be invited as a
     * result of just having joined (as opposed to e.g. another participant
     * joining triggering the invite).
     */
    private void inviteChatMember(ChatRoomMember chatRoomMember, boolean justJoined)
    {
        synchronized (participantLock)
        {
            // Participant already connected ?
            if (participants.get(chatRoomMember.getOccupantJid()) != null)
            {
                return;
            }

            // Discover the supported features early, so that any code that depends on the Participant's features works
            // with the correct values. Note that this operation will block waiting for a disco#info response when
            // the hash is not cached. In practice this should happen rarely (once for each unique set of features),
            // and when it does happen we only block the Smack thread processing presence *for this conference/MUC*.
            List<String> features = chatRoomMember.getFeatures();
            final Participant participant = new Participant(
                    chatRoomMember,
                    this,
                    jicofoServices.getXmppServices().getJingleHandler(),
                    logger,
                    features);

            ConferenceMetrics.participants.inc();
            if (!participant.supportsReceivingMultipleVideoStreams() && !participant.getChatMember().isJigasi())
            {
                ConferenceMetrics.participantsNoMultiStream.inc();
            }
            if (!participant.hasSourceNameSupport() && !participant.getChatMember().isJigasi())
            {
                ConferenceMetrics.participantsNoSourceName.inc();
            }

            participants.put(chatRoomMember.getOccupantJid(), participant);
            inviteParticipant(participant, false, justJoined);
        }
    }

    /**
     * Invites a {@link Participant} to the conference. Selects the bridge to use and starts a new
     * {@link ParticipantInviteRunnable} to allocate COLIBRI channels and initiate
     * a Jingle session with the {@link Participant}.
     * @param participant the participant to invite.
     * @param reInvite whether the participant is to be re-invited or invited for the first time.
     */
    private void inviteParticipant(@NotNull Participant participant, boolean reInvite, boolean justJoined)
    {
        // Colibri channel allocation and jingle invitation take time, so schedule them on a separate thread.
        ParticipantInviteRunnable channelAllocator = new ParticipantInviteRunnable(
                this,
                getColibriSessionManager(),
                participant,
                hasToStartAudioMuted(justJoined),
                hasToStartVideoMuted(justJoined),
                reInvite,
                logger
        );

        participant.setInviteRunnable(channelAllocator);
        TaskPools.getIoPool().execute(channelAllocator);
    }

    @NotNull EndpointSourceSet getSourcesForParticipant(@NotNull Participant participant)
    {
        EndpointSourceSet s = conferenceSources.get(participant.getEndpointId());
        return s != null ? s : EndpointSourceSet.EMPTY;
    }

    /**
     * Returns true if a participant should be invited with the "start audio muted" option given that they just
     * joined or are being re-invited (depending on the value of {@code justJoined}.
     */
    private boolean hasToStartAudioMuted(boolean justJoined)
    {
        if (startAudioMuted && justJoined)
        {
            return true;
        }

        int limit = ConferenceConfig.config.getMaxAudioSenders();
        Integer startAudioMutedInt = config.getStartAudioMuted();
        if (startAudioMutedInt != null)
        {
            limit = Math.min(limit, startAudioMutedInt);
        }
        return getParticipantCount() > limit;
    }

    /**
     * Returns true if a participant should be invited with the "start video muted" option given that they just
     * joined or are being re-invited (depending on the value of {@code justJoined}.
     */
    private boolean hasToStartVideoMuted(boolean justJoined)
    {
        if (startVideoMuted && justJoined)
        {
            return true;
        }

        int limit = ConferenceConfig.config.getMaxVideoSenders();
        Integer startVideoMutedInt = config.getStartVideoMuted();
        if (startVideoMutedInt != null)
        {
            limit = Math.min(limit, startVideoMutedInt);
        }
        return getParticipantCount() > limit;
    }

    /**
     * Returns {@code true} if there are enough participants in the room to start a conference.
     */
    private boolean checkMinParticipants()
    {
        int minParticipants = ConferenceConfig.config.getMinParticipants();
        ChatRoom chatRoom = getChatRoom();
        return chatRoom != null && chatRoom.getMembersCount() >= minParticipants;
    }

    /**
     * Expires all COLIBRI conferences.
     */
    private void expireBridgeSessions()
    {
        // If the conference is being disposed the timeout is not needed
        // anymore
        cancelSingleParticipantTimeout();

        if (colibriSessionManager != null)
        {
            colibriSessionManager.expire();
        }
    }

    private void onMemberKicked(ChatRoomMember chatRoomMember)
    {
        synchronized (participantLock)
        {
            logger.info("Member kicked: " + chatRoomMember.getName());

            onMemberLeft(chatRoomMember);
        }
    }

    private void onMemberLeft(ChatRoomMember chatRoomMember)
    {
        synchronized (participantLock)
        {
            logger.info("Member left:" + chatRoomMember.getName());
            Participant leftParticipant = participants.get(chatRoomMember.getOccupantJid());
            if (leftParticipant != null)
            {
                // We don't send source-remove, because the participant leaving the MUC will notify other participants
                // that the sources need to be removed (and we want to minimize signaling in large conferences).
                terminateParticipant(
                        leftParticipant,
                        Reason.GONE,
                        null,
                        /* no need to send session-terminate - gone */ false,
                        /* no need to send source-remove */ false);
            }
            else
            {
                logger.warn("Participant not found for " + chatRoomMember.getName()
                        + ". Terminated already or never started?");
            }

            if (participants.size() == 1)
            {
                rescheduleSingleParticipantTimeout();
            }
            else if (participants.size() == 0)
            {
                expireBridgeSessions();
            }
        }

        if (chatRoom == null || chatRoom.getMembersCount() == 0)
        {
            stop();
        }
    }

    private void terminateParticipant(
            Participant participant,
            @NotNull Reason reason,
            String message,
            boolean sendSessionTerminate,
            boolean sendSourceRemove)
    {
        logger.info(String.format(
                "Terminating %s, reason: %s, send session-terminate: %s",
                participant.getChatMember().getName(),
                reason,
                sendSessionTerminate));

        synchronized (participantLock)
        {
            participant.terminateJingleSession(reason, message, sendSessionTerminate);

            removeParticipantSources(participant, sendSourceRemove);

            Participant removed = participants.remove(participant.getChatMember().getOccupantJid());
            logger.info(
                    "Removed participant " + participant.getChatMember().getName() + " removed=" + (removed != null));
        }

        getColibriSessionManager().removeParticipant(participant.getEndpointId());
    }

    @Override
    public void registrationChanged(boolean registered)
    {
        if (registered)
        {
            logger.info("XMPP reconnected");
            if (chatRoom == null)
            {
                try
                {
                    joinTheRoom();
                }
                catch (Exception e)
                {
                    logger.error("Failed to join the room: " + roomName, e);

                    stop();
                }
            }
        }
        else
        {
            logger.info("XMPP disconnected.");
            stop();
        }
    }

    @Nullable
    public Participant getParticipant(@NotNull Jid occupantJid)
    {
        return participants.get(occupantJid);
    }

    @Override
    public MemberRole getRoleForMucJid(Jid mucJid)
    {
        if (chatRoom == null)
        {
            return null;
        }

        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (member.getOccupantJid().equals(mucJid))
            {
                return member.getRole();
            }
        }
        return null;
    }

    /**
     * Handles a notification that a participant's ICE session failed.
     *
     * @param participant the participant whose ICE session failed.
     * @param bridgeSessionId the ID of the bridge session that failed.
     */
    public void iceFailed(@NotNull Participant participant, String bridgeSessionId)
    {
        String existingBridgeSessionId = getColibriSessionManager().getBridgeSessionId(participant.getEndpointId());
        if (Objects.equals(bridgeSessionId, existingBridgeSessionId))
        {
            logger.info(String.format(
                    "Received ICE failed notification from %s, bridge-session ID: %s",
                    participant.getEndpointId(),
                    bridgeSessionId));
            reInviteParticipant(participant);
        }
        else
        {
            logger.info(String.format(
                    "Ignored ICE failed notification for invalid session, participant: %s, bridge session ID: %s",
                    participant.getEndpointId(),
                    bridgeSessionId));
        }
    }

    /**
     * Handles a request from a {@link Participant} to terminate its session and optionally start it again.
     *
     * @param bridgeSessionId the ID of the bridge session that the participant requested to be terminated.
     * @param reinvite whether to start a new session after the current one is terminated.
     *
     * @throws InvalidBridgeSessionIdException if bridgeSessionId doesn't match the ID of the (colibri) session that
     * the participant currently has.
     */
    void terminateSession(
            @NotNull Participant participant,
            String bridgeSessionId,
            boolean reinvite)
    throws InvalidBridgeSessionIdException
    {
        // TODO: maybe move the bridgeSessionId logic to Participant
        String existingBridgeSessionId = getColibriSessionManager().getBridgeSessionId(participant.getEndpointId());
        if (!Objects.equals(bridgeSessionId, existingBridgeSessionId))
        {
            throw new InvalidBridgeSessionIdException(bridgeSessionId + " is not a currently active session");
        }

        synchronized (participantLock)
        {
            terminateParticipant(
                    participant,
                    Reason.SUCCESS,
                    (reinvite) ? "reinvite requested" : null,
                    /* do not send session-terminate */ false,
                    /* do send source-remove */ true);

            if (reinvite)
            {
                participants.put(participant.getChatMember().getOccupantJid(), participant);
                inviteParticipant(participant, false, false);
            }
        }
    }

    /**
     * Advertises new sources across all conference participants by using
     * 'source-add' Jingle notification.
     *
     * @param sourceOwner the <tt>Participant</tt> who owns the sources.
     * @param sources the sources to propagate.
     */
    private void propagateNewSources(Participant sourceOwner, EndpointSourceSet sources)
    {
        if (sources.isEmpty())
        {
            logger.debug("No new sources to propagate.");
            return;
        }

        final ConferenceSourceMap conferenceSourceMap = new ConferenceSourceMap(sourceOwner.getEndpointId(), sources);

        participants.values().stream()
            .filter(otherParticipant -> otherParticipant != sourceOwner)
            .forEach(participant -> participant.addRemoteSources(conferenceSourceMap));
    }


    /**
     * Update the transport information for a participant. Callback called when we receive a 'transport-info', the info
     * is forwarded to the videobridge.
     */
    public void updateTransport(@NotNull Participant participant, @NotNull IceUdpTransportPacketExtension transport)
    {
        getColibriSessionManager().updateParticipant(
                participant.getEndpointId(),
                transport,
                null /* no change in sources, just transport */);
    }

    /**
     * Attempts to add sources from {@code participant} to the conference.
     *
     * @param participant the participant that is adding the sources.
     * @param sourcesAdvertised the sources that the participant is adding
     *
     * @throws SenderCountExceededException if the sender limits in the conference have been exceeded
     * @throws ValidationFailedException if the addition of the sources would result in an invalid state of the
     * conference sources (e.g. if there is a conflict with another participant, or the resulting source set for the
     * participant is invalid).
     */
    public void addSource(
            @NotNull Participant participant,
            @NotNull EndpointSourceSet sourcesAdvertised)
    throws SenderCountExceededException, ValidationFailedException
    {
        boolean rejectedAudioSource = sourcesAdvertised.getHasAudio() &&
                chatRoom.getAudioSendersCount() >= ConferenceConfig.config.getMaxAudioSenders();
        boolean rejectedVideoSource = sourcesAdvertised.getHasVideo() &&
                chatRoom.getVideoSendersCount() >= ConferenceConfig.config.getMaxVideoSenders();

        if (rejectedAudioSource || rejectedVideoSource)
        {
            throw new SenderCountExceededException(
                    "Sender count exceeded for: " + (rejectedAudioSource ? "audio " : "")
                            + (rejectedVideoSource ? "video" : ""));
        }

        EndpointSourceSet sourcesAccepted = conferenceSources.tryToAdd(participant.getEndpointId(), sourcesAdvertised);
        logger.debug(() -> "Accepted sources from " + participant.getEndpointId() + ": " + sourcesAccepted);

        if (sourcesAccepted.isEmpty())
        {
            // This shouldn't happen as the sources were non-empty, but none were accepted (there should have been an
            // exception above)
            logger.warn("Stop processing source-add, no new sources added: " + participant.getEndpointId());
            return;
        }

        // Updates source groups on the bridge
        // We may miss the notification, but the state will be synced up after conference has been relocated to the new
        // bridge
        getColibriSessionManager().updateParticipant(participant.getEndpointId(), null, participant.getSources());
        propagateNewSources(participant, sourcesAccepted);
    }

    /**
     * Handles a request from a participant to remove sources.
     * @throws ValidationFailedException if the request failed because the resulting source set for the participant
     * is invalid, or the participant was not allowed to remove some of the sources.
     */
    public void removeSources(
            @NotNull Participant participant,
            @NotNull EndpointSourceSet sourcesRequestedToBeRemoved)
        throws ValidationFailedException
    {
        String participantId = participant.getEndpointId();
        EndpointSourceSet sourcesAcceptedToBeRemoved
                = conferenceSources.tryToRemove(participantId, sourcesRequestedToBeRemoved);

        logger.debug(
                () -> "Received source removal request from " + participantId + ": " + sourcesRequestedToBeRemoved);
        logger.debug(() -> "Accepted sources to remove from " + participantId + ": " + sourcesAcceptedToBeRemoved);

        if (sourcesAcceptedToBeRemoved.isEmpty())
        {
            logger.warn(
                    "No sources or groups to be removed from " + participantId
                            + ". The requested sources to remove: " + sourcesRequestedToBeRemoved);
            return;
        }

        getColibriSessionManager().updateParticipant(
                participant.getEndpointId(),
                null,
                participant.getSources(),
                false);

        sendSourceRemove(new ConferenceSourceMap(participantId, sourcesAcceptedToBeRemoved), participant);
    }

    /**
     * Handles a "session-accept" or "transport-accept" request from a participant.
     */
    void acceptSession(
            @NotNull Participant participant,
            @NotNull EndpointSourceSet sourcesAdvertised,
            IceUdpTransportPacketExtension transport)
    throws ValidationFailedException
    {
        String participantId = participant.getEndpointId();

        EndpointSourceSet sourcesAccepted = EndpointSourceSet.EMPTY;
        if (!sourcesAdvertised.isEmpty())
        {
            sourcesAccepted = conferenceSources.tryToAdd(participantId, sourcesAdvertised);
        }

        getColibriSessionManager().updateParticipant(participantId, transport, getSourcesForParticipant(participant));

        if (!sourcesAccepted.isEmpty())
        {
            logger.info("Accepted initial sources from " + participantId + ": " + sourcesAccepted);
            // Propagate [participant]'s sources to the other participants.
            propagateNewSources(participant, sourcesAccepted);
        }
        else
        {
            logger.debug("Session accepted with no sources.");
        }

        // Now that the Jingle session is ready, signal any sources from other participants to [participant].
        participant.sendQueuedRemoteSources();
    }

    /**
     * Removes a participant's sources from the conference.
     *
     * @param participant the participant whose sources are to be removed.
     * @param sendSourceRemove Whether to send source-remove IQs to the remaining participants.
     */
    private void removeParticipantSources(@NotNull Participant participant, boolean sendSourceRemove)
    {
        String participantId = participant.getEndpointId();
        EndpointSourceSet sourcesRemoved = conferenceSources.remove(participantId);

        if (sourcesRemoved != null && !sourcesRemoved.isEmpty())
        {
            getColibriSessionManager().updateParticipant(
                participant.getEndpointId(),
                null,
                participant.getSources(),
                true);

            if (sendSourceRemove)
            {
                sendSourceRemove(new ConferenceSourceMap(participantId, sourcesRemoved), participant);
            }
        }
    }

    /**
     * Send a source-remove message to all participant except for {@code except}.
     * @param sources the sources to be contained in the source-remove message.
     * @param except a participant to not send a source-remove to.
     */
    private void sendSourceRemove(ConferenceSourceMap sources, Participant except)
    {
        if (sources.isEmpty())
        {
            logger.debug("No sources to remove.");
            return;
        }

        participants.values().stream()
                .filter(participant -> participant != except)
                .forEach(participant -> participant.removeRemoteSources(sources));
    }

    /**
     * @return all sources in the conference.
     */
    @NotNull
    public ConferenceSourceMap getSources()
    {
        return conferenceSources.unmodifiable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public EntityBareJid getRoomName()
    {
        return roomName;
    }

    @NotNull
    public XmppProvider getClientXmppProvider()
    {
        return jicofoServices.getXmppServices().getClientConnection();
    }

    /**
     * Checks if this conference has a member with a specific occupant JID. Note that we check for the existence of a
     * member in the chat room instead of a {@link Participant} (it's not clear whether the distinction is important).
     * @param jid the occupant JID of the member.
     * @return true if the conference has a member with occupant JID {@code jid}.
     */
    public boolean hasMember(Jid jid)
    {
        ChatRoom chatRoom = this.chatRoom;
        return chatRoom != null
                && (jid instanceof EntityFullJid)
                && chatRoom.getChatMember((EntityFullJid) jid) != null;
    }

    /**
     * Return the time this conference was created.
     */
    public Instant getCreationTime()
    {
        return creationTime;
    }

    public boolean hasHadAtLeastOneParticipant()
    {
        return hasHadAtLeastOneParticipant;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public MuteResult handleMuteRequest(
            @NotNull Jid muterJid,
            @NotNull Jid toBeMutedJid,
            boolean doMute,
            @NotNull MediaType mediaType)
    {
        Participant muter = getParticipant(muterJid);
        if (muter == null)
        {
            logger.warn("Muter participant not found, jid=" + muterJid);
            return MuteResult.ERROR;
        }
        // Only moderators can mute others
        if (!muterJid.equals(toBeMutedJid) && !MemberRoleKt.hasModeratorRights(muter.getChatMember().getRole()))
        {
            logger.warn("Mute not allowed for non-moderator " + muterJid);
            return MuteResult.NOT_ALLOWED;
        }

        Participant participant = getParticipant(toBeMutedJid);
        if (participant == null)
        {
            logger.warn("Participant to be muted not found, jid=" + toBeMutedJid);
            return MuteResult.ERROR;
        }

        // process unmuting
        if (!doMute)
        {
            // do not allow unmuting other participants even for the moderator
            if (!muterJid.equals(toBeMutedJid))
            {
                logger.warn("Unmute not allowed, muterJid=" + muterJid + ", toBeMutedJid=" + toBeMutedJid);
                return MuteResult.NOT_ALLOWED;
            }
            // Moderators are allowed to unmute without being in the whitelist
            else if (!participant.hasModeratorRights()
                && !this.chatRoom.isMemberAllowedToUnmute(toBeMutedJid, mediaType))
            {
                logger.warn("Unmute not allowed due to av moderation for jid=" + toBeMutedJid);
                return MuteResult.NOT_ALLOWED;
            }
        }

        if (participant.shouldSuppressForceMute())
        {
            logger.warn("Force mute suppressed, returning NOT_ALLOWED:" + participant);
            return MuteResult.NOT_ALLOWED;
        }

        logger.info("Will " + (doMute ? "mute" : "unmute") + " " + toBeMutedJid + " on behalf of " + muterJid
            + " for " + mediaType);

        getColibriSessionManager().mute(participant.getEndpointId(), doMute, mediaType);
        return MuteResult.SUCCESS;
    }

    @Override
    @NotNull
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject o = new OrderedJsonObject();
        o.put("name", roomName.toString());
        o.put("config", config.getDebugState());
        ChatRoom chatRoom = this.chatRoom;
        o.put("chat_room", chatRoom == null ? "null" : chatRoom.getDebugState());
        OrderedJsonObject participantsJson = new OrderedJsonObject();
        for (Participant participant : participants.values())
        {
            participantsJson.put(participant.getEndpointId(), participant.getDebugState());
        }
        o.put("participants", participantsJson);
        //o.put("jibri_recorder", jibriRecorder.getDebugState());
        //o.put("jibri_sip_gateway", jibriSipGateway.getDebugState());
        //o.put("transcriber_manager", transcriberManager.getDebugState());
        ChatRoomRoleManager chatRoomRoleManager = this.chatRoomRoleManager;
        o.put("chat_room_role_manager", chatRoomRoleManager == null ? "null" : chatRoomRoleManager.getDebugState());
        o.put("started", started.get());
        o.put("creation_time", creationTime.toString());
        o.put("has_had_at_least_one_participant", hasHadAtLeastOneParticipant);
        o.put("start_audio_muted", startAudioMuted);
        o.put("start_video_muted", startVideoMuted);
        if (colibriSessionManager != null)
        {
            o.put("colibri_session_manager", colibriSessionManager.getDebugState());
        }
        OrderedJsonObject conferencePropertiesJson = new OrderedJsonObject();
        conferencePropertiesJson.putAll(conferenceProperties);
        o.put("conference_properties", conferencePropertiesJson);
        o.put("include_in_statistics", includeInStatistics);
        o.put("conference_sources", conferenceSources.toJson());
        o.put("audio_limit_reached", audioLimitReached);
        o.put("video_limit_reached", videoLimitReached);

        return o;
    }

    /**
     * Mutes all participants (except jibri or jigasi without "audioMute" support). Will block for colibri responses.
     */
    public void muteAllParticipants(MediaType mediaType)
    {
        Set<Participant> participantsToMute = new HashSet<>();
        synchronized (participantLock)
        {
            for (Participant participant : participants.values())
            {
                if (participant.shouldSuppressForceMute())
                {
                    logger.info("Will not mute a trusted participant without unmute support (jibri, jigasi): "
                            + participant);
                    continue;
                }

                participantsToMute.add(participant);
            }
        }

        // Force mute at the backend. We assume this was successful. If for some reason it wasn't the colibri layer
        // should handle it (e.g. remove a broken bridge).
        getColibriSessionManager().mute(
                participantsToMute.stream().map(Participant::getEndpointId).collect(Collectors.toSet()),
                true,
                mediaType);

        // Signal to the participants that they are being muted.
        for (Participant participant : participantsToMute)
        {
            IQ muteIq = null;
            if (mediaType == MediaType.AUDIO)
            {
                MuteIq muteStatusUpdate = new MuteIq();
                muteStatusUpdate.setType(IQ.Type.set);
                muteStatusUpdate.setTo(participant.getMucJid());

                muteStatusUpdate.setMute(true);

                muteIq = muteStatusUpdate;
            }
            else if (mediaType == MediaType.VIDEO)
            {
                MuteVideoIq muteStatusUpdate = new MuteVideoIq();
                muteStatusUpdate.setType(IQ.Type.set);
                muteStatusUpdate.setTo(participant.getMucJid());

                muteStatusUpdate.setMute(true);

                muteIq = muteStatusUpdate;
            }

            if (muteIq != null)
            {
                UtilKt.tryToSendStanza(getClientXmppProvider().getXmppConnection(), muteIq);
            }
        }
    }

    /**
     * Returns current participants count. A participant is chat member who has
     * some videobridge and media state assigned(not just raw chat room member).
     * For example chat member which belongs to the focus never becomes
     * a participant.
     */
    public int getParticipantCount()
    {
        return participants.size();
    }

    private void onBridgeUp(Jid bridgeJid)
    {
        // Check if we're not shutting down
        if (!started.get())
        {
            return;
        }

        //TODO: if one of our bridges failed, we should have invited its
        // participants to another one. Here we should re-invite everyone if
        // the conference is not running (e.g. there was a single bridge and
        // it failed, then in was brought up).
        if (chatRoom != null && checkMinParticipants() && getColibriSessionManager().getBridgeCount() == 0)
        {
            logger.info("New bridge available, will try to restart: " + bridgeJid);

            synchronized (participantLock)
            {
                reInviteParticipants(participants.values());
            }
        }
    }

    private void reInviteParticipantsById(@NotNull List<String> participantIdsToReinvite)
    {
        if (!participantIdsToReinvite.isEmpty())
        {
            ConferenceMetrics.participantsMoved.addAndGet(participantIdsToReinvite.size());
            synchronized (participantLock)
            {
                List<Participant> participantsToReinvite = new ArrayList<>();
                for (Participant participant : participants.values())
                {
                    if (participantIdsToReinvite.contains(participant.getEndpointId()))
                    {
                        participantsToReinvite.add(participant);
                    }
                }
                if (participantsToReinvite.size() != participantIdsToReinvite.size())
                {
                    logger.error("Can not re-invite all participants, no Participant object for some of them.");
                }
                reInviteParticipants(participantsToReinvite);
            }
        }
    }

    /**
     * A callback called by {@link ParticipantInviteRunnable} when
     * establishing the Jingle session with its participant fails.
     * @param channelAllocator the channel allocator which failed.
     */
    public void onInviteFailed(ParticipantInviteRunnable channelAllocator)
    {
        terminateParticipant(
                channelAllocator.getParticipant(),
                Reason.GENERAL_ERROR,
                "jingle session failed",
                /* send session-terminate */ true,
                /* send source-remove */ true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public ChatRoom getChatRoom()
    {
        return chatRoom;
    }

    /**
     * Returns config for this conference.
     * @return <tt>JitsiMeetConfig</tt> instance used in this conference.
     */
    @NotNull
    public JitsiMeetConfig getConfig()
    {
        return config;
    }

    /**
     * Creates the shared document name by either using the conference room name
     * or a random string, depending on configuration.
     *
     * @return the shared document name.
     */
    private String createSharedDocumentName()
    {
        if (ConferenceConfig.config.useRandomSharedDocumentName())
        {
            return UUID.randomUUID().toString().replaceAll("-", "");
        }
        else
        {
            return roomName.getLocalpart().toString();
        }
    }

    /**
     * An adapter for {@link #reInviteParticipants(Collection)}.
     *
     * @param participant the {@link Participant} to be re invited into the
     * conference.
     */
    private void reInviteParticipant(Participant participant)
    {
        ArrayList<Participant> l = new ArrayList<>(1);

        l.add(participant);

        reInviteParticipants(l);
    }

    /**
     * Re-invites {@link Participant}s into the conference.
     *
     * @param participants the list of {@link Participant}s to be re-invited.
     */
    private void reInviteParticipants(Collection<Participant> participants)
    {
        synchronized (participantLock)
        {
            for (Participant participant : participants)
            {
                participant.setInviteRunnable(null);
                boolean restartJingle = ConferenceConfig.config.getReinviteMethod() == ReinviteMethod.RestartJingle;

                if (restartJingle)
                {
                    removeParticipantSources(participant, true);
                    participant.terminateJingleSession(Reason.SUCCESS, "moving", true);
                }

                // If were restarting the jingle session it's a fresh invite (reInvite = false), otherwise it's a
                // transport-replace (reInvite = true)
                inviteParticipant(participant, !restartJingle, false);
            }
        }
    }

    /**
     * (Re)schedules {@link SinglePersonTimeout}.
     */
    private void rescheduleSingleParticipantTimeout()
    {
        cancelSingleParticipantTimeout();

        long timeout = ConferenceConfig.config.getSingleParticipantTimeout().toMillis();

        singleParticipantTout = TaskPools.getScheduledPool().schedule(
                new SinglePersonTimeout(), timeout, TimeUnit.MILLISECONDS);

        logger.info("Scheduled single person timeout.");
    }

    /**
     * Cancels {@link SinglePersonTimeout}.
     */
    private void cancelSingleParticipantTimeout()
    {
        if (singleParticipantTout != null)
        {
            // This log is printed also when it's executed by the timeout thread itself
            logger.debug("Cancelling single person timeout.");

            singleParticipantTout.cancel(false);
            singleParticipantTout = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public Set<String> getBridgeRegions()
    {
        return colibriSessionManager != null ? colibriSessionManager.getBridgeRegions() : Collections.emptySet();
    }

    @Override
    public boolean includeInStatistics()
    {
        return includeInStatistics;
    }

    @Override
    @NotNull
    public IqProcessingResult handleJibriRequest(@NotNull IqRequest<JibriIq> request)
    {
        IqProcessingResult result = new NotProcessed();
        if (started.get())
        {
            if (jibriRecorder != null)
            {
                result = jibriRecorder.handleJibriRequest(request);
            }
            if (result instanceof NotProcessed && jibriSipGateway != null)
            {
                result = jibriSipGateway.handleJibriRequest(request);
            }
        }
        return result;
    }

    @Override
    public boolean acceptJigasiRequest(@NotNull Jid from)
    {
        return MemberRoleKt.hasModeratorRights(getRoleForMucJid(from));
    }

    @Override
    public JibriRecorder getJibriRecorder()
    {
        return jibriRecorder;
    }

    @Override
    public JibriSipGateway getJibriSipGateway()
    {
        return jibriSipGateway;
    }

    /**
     * Notifies this conference that one of the participants' screensharing source has changed its "mute" status.
     */
    void desktopSourceIsMutedChanged(Participant participant, boolean desktopSourceIsMuted)
    {
        if (!ConferenceConfig.config.getMultiStreamBackwardCompat())
        {
            return;
        }

        participants.values().stream()
                .filter(p -> p != participant)
                .filter(p -> !p.supportsReceivingMultipleVideoStreams())
                .forEach(p -> p.remoteDesktopSourceIsMutedChanged(participant.getEndpointId(), desktopSourceIsMuted));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return String.format("JitsiMeetConferenceImpl[name=%s]", getRoomName());
    }

    /**
     * The interface used to listen for conference events.
     */
    public interface ConferenceListener
    {
        /**
         * Event fired when conference has ended.
         * @param conference the conference instance that has ended.
         */
        void conferenceEnded(JitsiMeetConferenceImpl conference);
    }

    /**
     * The task is scheduled with some delay when we end up with single
     * <tt>Participant</tt> in the room to terminate its media session. There
     * is no point in streaming media to the videobridge and using
     * the bandwidth when nobody is receiving it.
     */
    private class SinglePersonTimeout
            implements Runnable
    {
        @Override
        public void run()
        {
            synchronized (participantLock)
            {
                if (participants.size() == 1)
                {
                    Participant p = participants.values().stream().findFirst().orElse(null);
                    logger.info("Timing out single participant: " + p.getChatMember().getName());

                    terminateParticipant(
                            p,
                            Reason.EXPIRED,
                            "Idle session timeout",
                            /* send session-terminate */ true,
                            /* send source-remove */ false);

                    expireBridgeSessions();
                }
                else
                {
                    logger.error("Should never execute if more than 1 participant?");
                }
                singleParticipantTout = null;
            }
        }
    }

    private class BridgeSelectorEventHandler implements BridgeSelector.EventHandler
    {
        @Override
        public void bridgeIsShuttingDown(@NotNull Bridge bridge)
        {
            List<String> participantIdsToReinvite
                    = colibriSessionManager != null
                        ? colibriSessionManager.removeBridge(bridge) : Collections.emptyList();
            if (!participantIdsToReinvite.isEmpty())
            {
                logger.info("Bridge " + bridge.getJid() + " is shutting down, re-inviting " + participantIdsToReinvite);
                reInviteParticipantsById(participantIdsToReinvite);
            }
        }

        @Override
        public void bridgeRemoved(@NotNull Bridge bridge)
        {
            List<String> participantIdsToReinvite
                    = colibriSessionManager != null
                        ? colibriSessionManager.removeBridge(bridge) : Collections.emptyList();
            if (!participantIdsToReinvite.isEmpty())
            {
                logger.info("Removed " + bridge.getJid() + ", re-inviting " + participantIdsToReinvite);
                reInviteParticipantsById(participantIdsToReinvite);
            }
        }

        @Override
        public void bridgeAdded(Bridge bridge)
        {
            onBridgeUp(bridge.getJid());
        }
    }

    private class ChatRoomListenerImpl implements ChatRoomListener
    {
        @Override
        public void roomDestroyed(String reason)
        {
            logger.info("Room destroyed with reason=" + reason);
            stop();
        }

        @Override
        public void startMutedChanged(boolean startAudioMuted, boolean startVideoMuted)
        {
            JitsiMeetConferenceImpl.this.startAudioMuted = startAudioMuted;
            JitsiMeetConferenceImpl.this.startVideoMuted = startVideoMuted;
        }

        @Override
        public void memberJoined(@NotNull ChatRoomMember member)
        {
            onMemberJoined(member);
        }

        @Override
        public void memberKicked(@NotNull ChatRoomMember member)
        {
            onMemberKicked(member);
        }

        @Override
        public void memberLeft(@NotNull ChatRoomMember member)
        {
            onMemberLeft(member);
        }

        @Override
        public void localRoleChanged(@NotNull MemberRole newRole, @Nullable MemberRole oldRole)
        {
            if (newRole != MemberRole.OWNER)
            {
                logger.warn(
                    "Stopping, because the local role changed to " + newRole + " (owner privileges are required).");
                stop();
            }
        }

        @Override
        public void memberPresenceChanged(@NotNull ChatRoomMember member)
        {
            Participant participant = getParticipant(member.getOccupantJid());
            if (participant != null)
            {
                participant.presenceChanged();
            }
        }

        @Override
        public void numAudioSendersChanged(int numAudioSenders)
        {
            onNumAudioSendersChanged(numAudioSenders);
        }

        @Override
        public void numVideoSendersChanged(int numVideoSenders)
        {
            onNumVideoSendersChanged(numVideoSenders);
        }
    }

    /**
     * Listener for events from {@link ColibriSessionManager}.
     */
    private class ColibriSessionManagerListener implements ColibriSessionManager.Listener
    {
        @Override
        public void bridgeCountChanged(int bridgeCount)
        {
            // Update the state in presence.
            setConferenceProperty(
                    ConferenceProperties.KEY_BRIDGE_COUNT,
                    Integer.toString(bridgeCount)
            );
        }

        /**
         * Bridge selection failed, update jicofo's presence in the room to reflect it.
         */
        @Override
        public void bridgeSelectionFailed()
        {
            ChatRoom chatRoom = getChatRoom();
            if (chatRoom != null
                    && !chatRoom.containsPresenceExtension(
                    BridgeNotAvailablePacketExt.ELEMENT,
                    BridgeNotAvailablePacketExt.NAMESPACE))
            {
                chatRoom.setPresenceExtension(new BridgeNotAvailablePacketExt(), false);
            }
        }

        /**
         * Bridge selection was successful, update jicofo's presence in the room to reflect it.
         */
        @Override
        public void bridgeSelectionSucceeded()
        {
            // Remove "bridge not available" from Jicofo's presence
            ChatRoom chatRoom = JitsiMeetConferenceImpl.this.chatRoom;
            if (chatRoom != null)
            {
                chatRoom.setPresenceExtension(new BridgeNotAvailablePacketExt(), true);
            }
        }

        @Override
        public void bridgeRemoved(@NotNull Bridge bridge, @NotNull List<String> participantIds)
        {
            logger.info("Bridge " + bridge + " was removed from the conference. Re-inviting its participants: "
                    + participantIds);
            reInviteParticipantsById(participantIds);
        }
    }

    public static class SenderCountExceededException extends Exception
    {
        SenderCountExceededException(String message)
        {
            super(message);
        }
    }

    static class InvalidBridgeSessionIdException extends Exception
    {
        InvalidBridgeSessionIdException(String message)
        {
            super(message);
        }
    }
}
