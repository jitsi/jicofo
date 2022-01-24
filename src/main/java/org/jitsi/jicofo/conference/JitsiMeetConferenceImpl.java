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
import org.jetbrains.annotations.Nullable;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.conference.colibri.*;
import org.jitsi.jicofo.conference.colibri.v1.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.lipsynchack.*;
import org.jitsi.jicofo.version.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import static org.jitsi.jicofo.xmpp.IqProcessingResult.*;

/**
 * Represents a Jitsi Meet conference. Manages the Jingle sessions with the
 * participants, as well as the COLIBRI session with the jitsi-videobridge
 * instances used for the conference.
 *
 * A note on synchronization: this class uses a lot of 'synchronized' blocks,
 * on 3 different objects {@link #participantLock}, {@code this} and {@code BridgeSession#octoParticipant}).
 *
 * This seems safe, but it is hard to maintain this way, and we should
 * re-factor to simplify.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class JitsiMeetConferenceImpl
    implements JitsiMeetConference,
               RegistrationListener,
               JingleRequestHandler
{
    /**
     * A random generator.
     */
    private final static Random RANDOM = new Random();

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
     * Operation set used to handle Jingle sessions with conference participants.
     */
    private OperationSetJingle jingle;

    /**
     * The list of all conference participants.
     */
    private final List<Participant> participants = new CopyOnWriteArrayList<>();

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
    private final ColibriSessionManager colibriSessionManager;

    /**
     * Listener for events from {@link #colibriSessionManager}.
     */
    private final ColibriSessionManagerListener colibriSessionManagerListener = new ColibriSessionManagerListener();

    /**
     * The conference properties that we advertise in presence in the XMPP MUC.
     */
    private final ConferenceProperties conferenceProperties = new ConferenceProperties();

    /**
     * See {@link JitsiMeetConference#includeInStatistics()}
     */
    private final boolean includeInStatistics;

    private final BridgeSelectorEventHandler bridgeSelectorEventHandler = new BridgeSelectorEventHandler();

    @NotNull private final JicofoServices jicofoServices;

    /**
     * Stores the sources advertised by all participants in the conference, mapped by their JID.
     */
    private final ValidatingConferenceSourceMap conferenceSources = new ValidatingConferenceSourceMap();

    /**
     * Whether the limit on the number of audio senders is currently hit.
     */
    private boolean audioLimitReached = false;

    /**
     * Whether the limit on the number of video senders is currently hit.
     */
    private boolean videoLimitReached = false;

    private final long gid;

    /**
     * Callback for colibri requests failing/succeeding.
     */
    private final ColibriRequestCallback colibriRequestCallback = new ColibriRequestCallbackImpl();

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
            long gid,
            boolean includeInStatistics)
    {
        logger = new LoggerImpl(JitsiMeetConferenceImpl.class.getName(), logLevel);
        logger.addContext("room", roomName.toString());

        this.config = config;

        this.roomName = roomName;
        this.listener = listener;
        this.etherpadName = createSharedDocumentName();
        this.includeInStatistics = includeInStatistics;

        this.jicofoServices = Objects.requireNonNull(JicofoServices.jicofoServicesSingleton);
        this.gid = gid;
        this.colibriSessionManager
                = new ColibriV1SessionManager(jicofoServices, gid, this, colibriRequestCallback, logger);
        colibriSessionManager.addListener(colibriSessionManagerListener);

        logger.info("Created new conference.");
    }

    public JitsiMeetConferenceImpl(
            @NotNull EntityBareJid roomName,
            ConferenceListener listener,
            @NotNull JitsiMeetConfig config,
            Level logLevel,
            long gid)
    {
       this(roomName, listener, config, logLevel, gid, false);
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
            jingle = clientXmppProvider.getJingleApi();

            // Wraps OperationSetJingle in order to introduce our nasty "lip-sync" hack. Note that lip-sync will only
            // be used for clients that signal support (see Participant.hasLipSyncSupport).
            if (ConferenceConfig.config.enableLipSync())
            {
                jingle = new LipSyncHack(this, jingle, logger);
            }

            BridgeSelector bridgeSelector = jicofoServices.getBridgeSelector();
            bridgeSelector.addHandler(bridgeSelectorEventHandler);

            if (clientXmppProvider.isRegistered())
            {
                joinTheRoom();
            }

            clientXmppProvider.addRegistrationListener(this);

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

        getClientXmppProvider().removeRegistrationListener(this);

        BridgeSelector bridgeSelector = jicofoServices.getBridgeSelector();
        bridgeSelector.removeHandler(bridgeSelectorEventHandler);

        colibriSessionManager.removeListener(colibriSessionManagerListener);

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

        if (jingle != null)
        {
            try
            {
                jingle.terminateHandlersSessions(this);
            }
            catch (Exception e)
            {
                logger.error("terminateHandlersSessions error", e);
            }
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

        presenceExtensions.add(ConferenceProperties.clone(conferenceProperties));

        // updates presence with presenceExtensions and sends it
        chatRoom.modifyPresence(null, presenceExtensions);
    }

    /**
     * Sets a conference property and sends an updated presence stanza in the
     * MUC.
     * @param key the key of the property.
     * @param value the value of the property.
     */
    private void setConferenceProperty(String key, String value)
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
    private void setConferenceProperty(String key, String value, boolean updatePresence)
    {
        conferenceProperties.put(key, value);
        if (updatePresence && chatRoom != null)
        {
            chatRoom.setPresenceExtension(ConferenceProperties.clone(conferenceProperties), false);
        }
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

        if (chatRoom.isJoined())
        {
            chatRoom.leave();
        }

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
            logger.info("Member joined:" + chatRoomMember.getName());
            getFocusManager().getStatistics().totalParticipants.incrementAndGet();
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
            if (findParticipantForChatMember(chatRoomMember) != null)
            {
                return;
            }

            final Participant participant = new Participant(chatRoomMember, logger, this);

            participants.add(participant);
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
                colibriRequestCallback,
                colibriSessionManager,
                participant,
                hasToStartAudioMuted(participant, justJoined),
                hasToStartVideoMuted(participant, justJoined),
                reInvite,
                logger
        );

        participant.setInviteRunnable(channelAllocator);
        TaskPools.getIoPool().execute(channelAllocator);
    }

    @NotNull
    ConferenceSourceMap getSourcesForParticipant(@NotNull Participant participant)
    {
        EndpointSourceSet participantSourcesSet = conferenceSources.get(participant.getMucJid());
        ConferenceSourceMap participantSourceMap
                = participantSourcesSet == null
                    ? new ConferenceSourceMap()
                    : new ConferenceSourceMap(participant.getMucJid(), participantSourcesSet);
        return participantSourceMap.unmodifiable();
    }

    /**
     * Returns true if {@code participant} should be invited with the "start audio muted" option given that they just
     * joined or are being re-invited (depending on the value of {@code justJoined}.
     */
    private boolean hasToStartAudioMuted(@NotNull Participant participant, boolean justJoined)
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
        return participant.getChatMember().getJoinOrderNumber() > limit;
    }

    /**
     * Returns true if {@code participant} should be invited with the "start video muted" option given that they just
     * joined or are being re-invited (depending on the value of {@code justJoined}.
     */
    private boolean hasToStartVideoMuted(@NotNull Participant participant, boolean justJoined)
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
        return participant.getChatMember().getJoinOrderNumber() > limit;
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
     * Destroys the MUC room and deletes the conference which results in all
     * participant being removed from the XMPP chat room.
     * @param reason the reason text that will be advertised to all
     *               participants upon exit.
     */
    public void destroy(String reason)
    {
        if (chatRoom == null)
        {
            logger.error("Unable to destroy conference MUC room, not joined");
            return;
        }

        chatRoom.destroy(reason, null);
    }

    /**
     * Expires all COLIBRI conferences.
     */
    private void expireBridgeSessions()
    {
        // If the conference is being disposed the timeout is not needed
        // anymore
        cancelSingleParticipantTimeout();

        colibriSessionManager.expire();
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
            Participant leftParticipant = findParticipantForChatMember(chatRoomMember);
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
            Reason reason,
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
            JingleSession jingleSession = participant.getJingleSession();
            if (jingleSession != null)
            {
                jingle.terminateSession(jingleSession, reason, message, sendSessionTerminate);
            }

            EndpointSourceSet participantSources = participant.getSources().get(participant.getMucJid());
            if (participantSources != null)
            {
                removeSources(participant, participantSources, false, sendSourceRemove);
            }

            participant.setJingleSession(null);

            boolean removed = participants.remove(participant);
            logger.info("Removed participant " + participant.getChatMember().getName() + " removed=" + removed);
        }

        colibriSessionManager.removeParticipant(participant);
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

    private Participant findParticipantForJingleSession(JingleSession jingleSession)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember().getOccupantJid().equals(jingleSession.getAddress()))
            {
                return participant;
            }
        }
        return null;
    }

    private Participant findParticipantForChatMember(ChatRoomMember chatMember)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember().equals(chatMember))
            {
                return participant;
            }
        }
        return null;
    }

    public Participant findParticipantForRoomJid(Jid roomJid)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember().getOccupantJid().equals(roomJid))
            {
                return participant;
            }
        }
        return null;
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
     * Callback called when 'session-accept' is received from invited
     * participant.
     *
     * {@inheritDoc}
     */
    @Override
    public StanzaError onSessionAccept(@NotNull JingleSession jingleSession, List<ContentPacketExtension> answer)
    {
        logger.info("Receive session-accept from " + jingleSession.getAddress());

        return onSessionAcceptInternal(jingleSession, answer);
    }

    /**
     * Will re-allocate channels on the bridge for participant who signals ICE
     * state 'failed'. New transport is sent in the 'transport-info' message
     * similar to the conference migration scenario.
     *
     * {@inheritDoc}
     */
    @Override
    public StanzaError onSessionInfo(@NotNull JingleSession session, JingleIQ iq)
    {
        Jid address = session.getAddress();
        Participant participant = findParticipantForJingleSession(session);

        // FIXME: (duplicate) there's very similar logic in onSessionAccept
        if (participant == null)
        {
            String errorMsg = "No session for " + address;
            logger.warn(errorMsg);
            return StanzaError.from(StanzaError.Condition.item_not_found, errorMsg).build();
        }

        IceStatePacketExtension iceStatePE = iq.getExtension(IceStatePacketExtension.class);
        String iceState = iceStatePE != null ? iceStatePE.getText() : null;

        if (!"failed".equalsIgnoreCase(iceState))
        {
            logger.info(String.format("Ignored ice-state %s from %s", iceState, address));

            return null;
        }

        BridgeSessionPacketExtension bsPE = getBridgeSessionPacketExtension(iq);
        String bridgeSessionId = bsPE != null ? bsPE.getId() : null;
        ColibriAllocation colibriAllocation = colibriSessionManager.getAllocation(participant);
        String existingBridgeSessionId = colibriAllocation == null ? null : colibriAllocation.getBridgeSessionId();
        if (Objects.equals(bridgeSessionId, existingBridgeSessionId))
        {
            logger.info(String.format(
                    "Received ICE failed notification from %s, bridge-session ID: %s",
                    address,
                    bridgeSessionId));
            reInviteParticipant(participant);
        }
        else
        {
            logger.info(String.format(
                    "Ignored ICE failed notification for invalid session, participant: %s, bridge session ID: %s",
                    address,
                    bridgeSessionId));
        }
        listener.participantIceFailed();

        return null;
    }

    private BridgeSessionPacketExtension getBridgeSessionPacketExtension(@NotNull IQ iq)
    {
        return iq.getExtension(BridgeSessionPacketExtension.class);
    }

    /**
     * Handles 'session-terminate' received from the client.
     *
     * {@inheritDoc}
     */
    @Override
    public StanzaError onSessionTerminate(JingleSession session, JingleIQ iq)
    {
        Participant participant = findParticipantForJingleSession(session);

        // FIXME: (duplicate) there's very similar logic in onSessionAccept/onSessionInfo
        if (participant == null)
        {
            String errorMsg = "No participant for " + session.getAddress();
            logger.warn(errorMsg);
            return StanzaError.from(StanzaError.Condition.item_not_found, errorMsg).build();
        }

        BridgeSessionPacketExtension bsPE = getBridgeSessionPacketExtension(iq);
        String bridgeSessionId = bsPE != null ? bsPE.getId() : null;
        ColibriAllocation colibriAllocation = colibriSessionManager.getAllocation(participant);
        String existingBridgeSessionId = colibriAllocation == null ? null : colibriAllocation.getBridgeSessionId();
        boolean restartRequested = bsPE != null && bsPE.isRestart();

        if (restartRequested)
        {
            listener.participantRequestedRestart();
        }

        if (!Objects.equals(bridgeSessionId, existingBridgeSessionId))
        {
            logger.info(String.format(
                    "Ignored session-terminate for invalid session: %s, bridge session ID: %s restart: %s",
                    participant,
                    bridgeSessionId,
                    restartRequested));

            return StanzaError.from(StanzaError.Condition.item_not_found, "invalid bridge session ID").build();
        }

        logger.info(String.format(
                "Received session-terminate from %s, bridge-session ID: %s, restart: %s",
                participant,
                bridgeSessionId,
                restartRequested));

        synchronized (participantLock)
        {
            terminateParticipant(
                    participant,
                    null,
                    null,
                    /* do not send session-terminate */ false,
                    /* do send source-remove */ true);

            if (restartRequested)
            {
                if (participant.incrementAndCheckRestartRequests())
                {
                    participants.add(participant);
                    inviteParticipant(participant, false, false);
                }
                else
                {
                    logger.warn(String.format("Rate limiting %s for restart requests", participant));

                    return StanzaError.from(StanzaError.Condition.resource_constraint, "rate-limited").build();
                }
            }
        }

        return null;
    }

    /**
     * Advertises new sources across all conference participants by using
     * 'source-add' Jingle notification.
     *
     * @param sourceOwner the <tt>Participant</tt> who owns the sources.
     * @param sources the sources to propagate.
     */
    private void propagateNewSources(Participant sourceOwner, ConferenceSourceMap sources)
    {
        final ConferenceSourceMap finalSources = sources
                .copy()
                .strip(ConferenceConfig.config.stripSimulcast(), true)
                .unmodifiable();
        if (finalSources.isEmpty())
        {
            logger.debug("No new sources to propagate.");
            return;
        }

        participants.stream()
            .filter(otherParticipant -> otherParticipant != sourceOwner)
            .forEach(participant -> participant.addRemoteSources(finalSources));
    }


    /**
     * Callback called when we receive 'transport-info' from conference
     * participant. The info is forwarded to the videobridge at this point.
     *
     * {@inheritDoc}
     */
    @Override
    public void onTransportInfo(JingleSession session, List<ContentPacketExtension> contentList)
    {
        Participant participant = findParticipantForJingleSession(session);
        if (participant == null)
        {
            logger.warn("Failed to process transport-info, no session for: " + session.getAddress());
            return;
        }

        colibriSessionManager.updateParticipant(participant, getTransport(contentList), null, null);
    }

    /**
     * 'transport-accept' message is received by the focus after it has sent
     * 'transport-replace' which is supposed to move the conference to another
     * bridge. It means that the client has accepted new transport.
     *
     * {@inheritDoc}
     */
    @Override
    public StanzaError onTransportAccept(@NotNull JingleSession jingleSession, List<ContentPacketExtension> contents)
    {
        logger.info("Received transport-accept from " + jingleSession.getAddress());

        // We basically do the same processing as with session-accept by just
        // forwarding transport/rtp information to the bridge + propagate the
        // participants sources & source groups to remote bridges.
        return onSessionAcceptInternal(jingleSession, contents);
    }

    /**
     * Message sent by the client when for any reason it's unable to handle
     * 'transport-replace' message.
     *
     * {@inheritDoc}
     */
    @Override
    public void onTransportReject(@NotNull JingleSession jingleSession, JingleIQ reply)
    {
        Participant p = findParticipantForJingleSession(jingleSession);
        if (p == null)
        {
            logger.warn("No participant for " + jingleSession);
            return;
        }

        // We could expire channels immediately here, but we're leaving them to
        // auto expire on the bridge or we're going to do that when user leaves
        // the MUC anyway
        logger.error("Participant has rejected our transport offer: " + p.getChatMember().getName()
                + ", response: " + reply.toXML());
    }

    /**
     * Callback called when we receive 'source-add' notification from conference
     * participant. New sources received are advertised to active participants.
     * If some participant does not have Jingle session established yet then
     * those sources are scheduled for future update.
     *
     * {@inheritDoc}
     */
    @Override
    public StanzaError onAddSource(@NotNull JingleSession jingleSession, List<ContentPacketExtension> contents)
    {
        Jid address = jingleSession.getAddress();
        Participant participant = findParticipantForJingleSession(jingleSession);
        if (participant == null)
        {
            String errorMsg = "no session for " + address;
            logger.warn(errorMsg);
            return StanzaError.from(StanzaError.Condition.item_not_found, errorMsg).build();
        }

        String participantId = participant.getEndpointId();
        EndpointSourceSet sourcesAdvertised = EndpointSourceSet.fromJingle(contents);
        logger.debug(() -> "Received source-add from " + participantId + ": " + sourcesAdvertised);

        boolean rejectedAudioSource = sourcesAdvertised.getHasAudio() &&
                chatRoom.getAudioSendersCount() >= ConferenceConfig.config.getMaxAudioSenders();

        if (rejectedAudioSource ||
                sourcesAdvertised.getHasVideo() &&
                chatRoom.getVideoSendersCount() >= ConferenceConfig.config.getMaxVideoSenders())
        {
            String errorMsg = "Source add rejected. Maximum number of " +
                    (rejectedAudioSource ? "audio" : "video") + " senders reached.";
            logger.warn(() -> participantId + ": " + errorMsg);
            return StanzaError.from(StanzaError.Condition.resource_constraint, errorMsg).build();
        }

        ConferenceSourceMap sourcesAccepted;
        try
        {
            sourcesAccepted = conferenceSources.tryToAdd(participant.getMucJid(), sourcesAdvertised);
        }
        catch (ValidationFailedException e)
        {
            logger.error("Error adding SSRCs from: " + address + ": " + e.getMessage());
            return StanzaError.from(StanzaError.Condition.bad_request, e.getMessage()).build();
        }

        logger.debug(() -> "Accepted sources from " + participantId + ": " + sourcesAccepted);

        if (sourcesAccepted.isEmpty())
        {
            logger.warn("Stop processing source-add, no new sources added: " + participantId);
            return null;
        }

        // Updates source groups on the bridge
        // We may miss the notification, but the state will be synced up
        // after conference has been relocated to the new bridge
        colibriSessionManager.addSources(participant, sourcesAccepted);

        propagateNewSources(participant, sourcesAccepted);

        return null;
    }

    /**
     * Callback called when we receive 'source-remove' notification from
     * conference participant. New sources received are advertised to active
     * participants. If some participant does not have Jingle session
     * established yet then those sources are scheduled for future update.
     *
     * {@inheritDoc}
     */
    @Override
    public StanzaError onRemoveSource(@NotNull JingleSession sourceJingleSession, List<ContentPacketExtension> contents)
    {
        EndpointSourceSet sourcesRequestedToBeRemoved = EndpointSourceSet.fromJingle(contents);

        Participant participant = findParticipantForJingleSession(sourceJingleSession);
        if (participant == null)
        {
            logger.warn("No participant for jingle-session: " + sourceJingleSession);
            return StanzaError.from(StanzaError.Condition.bad_request, "No associated participant").build();
        }
        else
        {
            return removeSources(participant, sourcesRequestedToBeRemoved, true, true);
        }
    }

    /**
     * Updates the RTP description, transport and propagates sources and source
     * groups of a participant that sends the session-accept or transport-accept
     * Jingle IQs.
     */
    private StanzaError onSessionAcceptInternal(
            @NotNull JingleSession jingleSession, List<ContentPacketExtension> contents)
    {
        Participant participant = findParticipantForJingleSession(jingleSession);
        Jid participantJid = jingleSession.getAddress();

        if (participant == null)
        {
            String errorMsg = "No participant found for: " + participantJid;
            logger.warn(errorMsg);
            return StanzaError.from(StanzaError.Condition.item_not_found, errorMsg).build();
        }

        if (participant.getJingleSession() != null && participant.getJingleSession() != jingleSession)
        {
            //FIXME: we should reject it ?
            logger.error("Reassigning jingle session for participant: " + participantJid);
        }

        participant.setJingleSession(jingleSession);

        String participantId = participant.getEndpointId();
        EndpointSourceSet sourcesAdvertised = EndpointSourceSet.fromJingle(contents);
        if (logger.isDebugEnabled())
        {
            logger.debug("Received initial sources from " + participantId + ": " + sourcesAdvertised);
        }
        if (sourcesAdvertised.isEmpty() && ConferenceConfig.config.injectSsrcForRecvOnlyEndpoints())
        {
            // We inject an SSRC in order to ensure that the participant has
            // at least one SSRC advertised. Otherwise, non-local bridges in the
            // conference will not be aware of the participant.
            long ssrc = RANDOM.nextInt() & 0xffff_ffffL;
            logger.info(participant + " did not advertise any SSRCs. Injecting " + ssrc);
            sourcesAdvertised = new EndpointSourceSet(new Source(ssrc, MediaType.AUDIO, null, null, true));
        }
        ConferenceSourceMap sourcesAccepted;
        try
        {
            sourcesAccepted = conferenceSources.tryToAdd(participantJid, sourcesAdvertised);
        }
        catch (ValidationFailedException e)
        {
            logger.error("Error processing session-accept from: " + participantJid +": " + e.getMessage());

            return StanzaError.from(StanzaError.Condition.bad_request, e.getMessage()).build();
        }
        logger.info("Accepted initial sources from " + participantId + ": " + sourcesAccepted);

        // Update channel info - we may miss update during conference restart,
        // but the state will be synced up after channels are allocated for this
        // participant on the new bridge
        colibriSessionManager.updateParticipant(
                participant,
                getTransport(contents),
                sourcesAccepted,
                getRtpDescriptions(contents));

        // Propagate [participant]'s sources to the other participants.
        propagateNewSources(participant, sourcesAccepted);
        // Now that the Jingle session is ready, signal any sources from other participants to [participant].
        participant.sendQueuedRemoteSources();

        return null;
    }

    /**
     * Extract a map from content name to the first child of type {@link RtpDescriptionPacketExtension}.
     */
    private Map<String, RtpDescriptionPacketExtension> getRtpDescriptions(
            @NotNull List<ContentPacketExtension> contents)
    {
        Map<String, RtpDescriptionPacketExtension> rtpDescriptions = new HashMap<>();
        for (ContentPacketExtension content : contents)
        {
            RtpDescriptionPacketExtension rtpDescription
                    = content.getFirstChildOfType(RtpDescriptionPacketExtension.class);
            if (rtpDescription != null)
            {
                rtpDescriptions.put(content.getName(), rtpDescription);
            }
        }

        return rtpDescriptions;
    }

    /**
     * Find the first {@link IceUdpTransportPacketExtension} in a list of Jingle contents.
     */
    private IceUdpTransportPacketExtension getTransport(@NotNull List<ContentPacketExtension> contents)
    {
        IceUdpTransportPacketExtension transport = null;
        for (ContentPacketExtension content : contents)
        {
            transport = content.getFirstChildOfType(IceUdpTransportPacketExtension.class);
            if (transport != null)
            {
                break;
            }
        }

        if (transport == null)
        {
            logger.error("No valid transport supplied in transport-update from $participant");
            return null;
        }

        if (!transport.isRtcpMux())
        {
            transport.addChildExtension(new IceRtcpmuxPacketExtension());
        }

        return transport;
    }


    /**
     * Removes sources from the conference.
     *
     * @param participant the participant that owns the sources to be removed.
     * @param sourcesRequestedToBeRemoved the sources that an endpoint requested to be removed from the conference.
     * @param removeColibriSourcesFromLocalBridge whether to signal the source removal to the local bridge (we use
     * "false" to avoid sending an unnecessary "remove source" message just prior to the "expire" message).
     * @param sendSourceRemove Whether to send source-remove IQs to the remaining participants.
     */
    private StanzaError removeSources(
            @NotNull Participant participant,
            EndpointSourceSet sourcesRequestedToBeRemoved,
            boolean removeColibriSourcesFromLocalBridge,
            boolean sendSourceRemove)
    {
        Jid participantJid = participant.getMucJid();
        ConferenceSourceMap sourcesAcceptedToBeRemoved;
        try
        {
            sourcesAcceptedToBeRemoved = conferenceSources.tryToRemove(participantJid, sourcesRequestedToBeRemoved);
        }
        catch (ValidationFailedException e)
        {
            logger.error("Error removing SSRCs from: " + participantJid + ": " + e.getMessage());
            return StanzaError.from(StanzaError.Condition.bad_request, e.getMessage()).build();
        }

        String participantId = participant.getEndpointId();
        logger.debug(
                () -> "Received source removal request from " + participantId + ": " + sourcesRequestedToBeRemoved);
        logger.debug(() -> "Accepted sources to remove from " + participantId + ": " + sourcesAcceptedToBeRemoved);

        if (sourcesAcceptedToBeRemoved.isEmpty())
        {
            logger.warn(
                    "No sources or groups to be removed from " + participantId
                            + ". The requested sources to remove: " + sourcesRequestedToBeRemoved);
            return null;
        }

        colibriSessionManager.removeSources(
                participant,
                sourcesAcceptedToBeRemoved,
                removeColibriSourcesFromLocalBridge);

        if (sendSourceRemove)
        {
            sendSourceRemove(sourcesAcceptedToBeRemoved, participant);
        }

        return null;
    }

    /**
     * Send a source-remove message to all participant except for {@code except}.
     * @param sources the sources to be contained in the source-remove message.
     * @param except a participant to not send a source-remove to.
     */
    private void sendSourceRemove(ConferenceSourceMap sources, Participant except)
    {
        final ConferenceSourceMap finalSources = sources
                .copy()
                .strip(ConferenceConfig.config.stripSimulcast(), true)
                .unmodifiable();
        if (finalSources.isEmpty())
        {
            logger.debug("No sources to remove.");
            return;
        }

        participants.stream()
                .filter(participant -> participant != except)
                .forEach(participant -> participant.removeRemoteSources(finalSources));
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

    public ChatRoomMember findMember(Jid from)
    {
        return chatRoom == null ? null : chatRoom.findChatMember(from);
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
     * Returns <tt>OperationSetJingle</tt> for the XMPP connection used in this <tt>JitsiMeetConference</tt> instance.
     */
    public OperationSetJingle getJingle()
    {
        return jingle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public MuteResult handleMuteRequest(Jid muterJid, Jid toBeMutedJid, boolean doMute, MediaType mediaType)
    {
        if (muterJid != null)
        {
            Participant muter = findParticipantForRoomJid(muterJid);
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
        }

        Participant participant = findParticipantForRoomJid(toBeMutedJid);
        if (participant == null)
        {
            logger.warn("Participant to be muted not found, jid=" + toBeMutedJid);
            return MuteResult.ERROR;
        }

        // process unmuting
        if (!doMute)
        {
            // do not allow unmuting other participants even for the moderator
            if (muterJid == null || !muterJid.equals(toBeMutedJid))
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

        if (doMute
            && participant.getChatMember().isJigasi()
            && !participant.hasAudioMuteSupport())
        {
            logger.warn("Mute not allowed, toBeMuted is jigasi.");
            return MuteResult.NOT_ALLOWED;
        }


        if (doMute && participant.getChatMember().isJibri())
        {
            logger.warn("Mute not allowed, toBeMuted is jibri.");
            return MuteResult.NOT_ALLOWED;
        }

        logger.info("Will " + (doMute ? "mute" : "unmute") + " " + toBeMutedJid + " on behalf of " + muterJid
            + " for " + mediaType);

        return colibriSessionManager.mute(participant, doMute, mediaType) ? MuteResult.SUCCESS : MuteResult.ERROR;
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
        for (Participant participant : participants)
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
        o.put("colibri_session_manager", colibriSessionManager.getDebugState());
        OrderedJsonObject conferencePropertiesJson = new OrderedJsonObject();
        for (ConferenceProperties.ConferenceProperty conferenceProperty : conferenceProperties.getProperties())
        {
            conferencePropertiesJson.put(conferenceProperty.getKey(), conferenceProperty.getValue());
        }
        o.put("conference_properties", conferencePropertiesJson);
        o.put("include_in_statistics", includeInStatistics);
        o.put("conference_sources", conferenceSources.toJson());
        o.put("audio_limit_reached", audioLimitReached);
        o.put("video_limit_reached", videoLimitReached);
        o.put("gid", gid);


        return o;
    }

    /**
     * Mutes all participants (except jibri or jigasi without "audioMute" support). Will block for colibri responses.
     */
    public void muteAllParticipants(MediaType mediaType)
    {
        Iterable<Participant> participantsToMute;
        synchronized (participantLock)
        {
            participantsToMute = new ArrayList<>(participants);
        }

        for (Participant participant : participantsToMute)
        {
            muteParticipant(participant, mediaType);
        }
    }

    /**
     * Mutes a participant, blocking for a colibri response (no-op if the participant is already muted).
     * Will not mute jibri instances, and jigasi instances without "audioMute" support.
     * @param participant the participant to mute.
     * @param mediaType the media type for the operation.
     */
    public void muteParticipant(Participant participant, MediaType mediaType)
    {
        if (participant.getChatMember().isJigasi() && !participant.hasAudioMuteSupport())
        {
            logger.warn("Will not mute jigasi with not audioMute support: " + participant);
            return;
        }

        if (participant.getChatMember().isJibri())
        {
            logger.warn("Will not mute jibri: " + participant);
            return;
        }

        if (!colibriSessionManager.mute(participant, true, mediaType))
        {
            logger.warn("Failed to mute colibri channels for " + participant);
            return;
        }

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

    /**
     * Conference ID.
     */
    public long getId()
    {
        return gid;
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
        if (chatRoom != null && checkMinParticipants() && colibriSessionManager.getBridgeCount() == 0)
        {
            logger.info("New bridge available, will try to restart: " + bridgeJid);

            synchronized (participantLock)
            {
                reInviteParticipants(participants);
            }
        }
    }

    /**
     * Handles the case of some bridges in the conference becoming non-operational.
     * @param bridgeJids the JIDs of the bridges that are non-operational.
     */
    private void onMultipleBridgesDown(Set<Jid> bridgeJids)
    {
        List<Participant> participantsToReinvite = colibriSessionManager.bridgesDown(bridgeJids);

        if (!participantsToReinvite.isEmpty())
        {
            listener.participantsMoved(participantsToReinvite.size());
            reInviteParticipants(participantsToReinvite);
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
     * An adapter for {@link #reInviteParticipants(List)}.
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
    private void reInviteParticipants(List<Participant> participants)
    {
        synchronized (participantLock)
        {
            colibriSessionManager.removeParticipants(participants);
            for (Participant participant : participants)
            {
                inviteParticipant(participant, true, false);
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
        return colibriSessionManager.getBridgeRegions();
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

    private FocusManager getFocusManager()
    {
        return jicofoServices.getFocusManager();
    }

    public JibriRecorder getJibriRecorder()
    {
        return jibriRecorder;
    }

    public JibriSipGateway getJibriSipGateway()
    {
        return jibriSipGateway;
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

        /**
         * {@code count} participants were moved away from a failed bridge.
         *
         * @param count the number of participants that were moved.
         */
        void participantsMoved(int count);

        /**
         * A participant reported that its ICE connection to the bridge failed.
         */
        void participantIceFailed();

        /**
         * A participant requested to be re-invited via session-terminate.
         */
        void participantRequestedRestart();

        /**
         * A number of bridges were removed from the conference because they were non-operational.
         */
        void bridgeRemoved(int count);
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
                    Participant p = participants.get(0);

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
        public void bridgeRemoved(Bridge bridge)
        {
            onMultipleBridgesDown(Collections.singleton(bridge.getJid()));
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
        public void roomDestroyed(@NotNull String reason)
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
     * Listener for events from {@link ColibriV1SessionManager}.
     */
    private class ColibriSessionManagerListener implements ColibriV1SessionManager.Listener
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

        @Override
        public void failedBridgesRemoved(int count)
        {
            listener.bridgeRemoved(count);
        }
    }

    private class ColibriRequestCallbackImpl implements ColibriRequestCallback
    {
        @Override
        public void requestFailed(@NotNull Jid jvbJid)
        {
            onMultipleBridgesDown(Collections.singleton(jvbJid));
        }

        @Override
        public void requestSucceeded(@NotNull Jid jvbJid)
        {
            // Remove "bridge not available" from Jicofo's presence
            ChatRoom chatRoom = JitsiMeetConferenceImpl.this.chatRoom;
            if (chatRoom != null)
            {
                chatRoom.setPresenceExtension(new BridgeNotAvailablePacketExt(), true);
            }
        }
    }
}
