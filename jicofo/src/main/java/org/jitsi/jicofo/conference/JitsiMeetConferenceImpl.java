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

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.MediaType;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.bridge.colibri.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.jicofo.version.*;
import org.jitsi.jicofo.visitors.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.UtilKt;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.jibri.*;

import org.jitsi.xmpp.extensions.visitors.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smackx.caps.*;
import org.jivesoftware.smackx.caps.packet.*;
import org.jxmpp.jid.*;

import javax.xml.namespace.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import java.util.stream.*;

import static org.jitsi.jicofo.conference.ConferenceUtilKt.getVisitorMucJid;
import static org.jitsi.jicofo.xmpp.IqProcessingResult.*;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.jitsi.jicofo.xmpp.MuteIqHandlerKt.createMuteIq;

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
    implements JitsiMeetConference, XmppProvider.Listener
{

    /**
     * Status used by participants when they are switching from a room to a breakout room.
     */
    private static final String BREAKOUT_SWITCHING_STATUS = "switch_room";

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
     * The JID of the main room if this is a breakout room, and {@code null} otherwise.
     */
    @Nullable
    private EntityBareJid mainRoomJid = null;

    /**
     * Maps a visitor node ID (one of the values from {@link XmppConfig#getVisitors()}'s keys) to the {@link ChatRoom}
     * on that node.
     */
    private final Map<String, ChatRoom> visitorChatRooms = new ConcurrentHashMap<>();

    /**
     * Map of occupant JID to Participant.
     */
    private final Map<Jid, Participant> participants = new ConcurrentHashMap<>();

    /**
     * This lock is used to synchronise write access to {@link #participants}.
     */
    private final Object participantLock = new Object();

    /**
     * A stat number of conference participants with a visitor muc role.
     */
    private final RateLimitedStat visitorCount = new RateLimitedStat(VisitorsConfig.config.getNotificationInterval(),
        (numVisitors) -> {
            setConferenceProperty(
                ConferenceProperties.KEY_VISITOR_COUNT,
                Integer.toString(numVisitors));
            return null;
        });

    /**
     * The aggregated count of visitors' supported codecs
     */
    private final PreferenceAggregator visitorCodecs;

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

    private ChatRoomRoleManager chatRoomRoleManager;

    /**
     * Indicates if this instance has been started (initialized).
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * A timeout task which will terminate media session of the user who is
     * sitting alone in the room for too long.
     */
    private Future<?> singleParticipantTout;

    /**
     * A task to stop the conference if no participants or breakout rooms are present after a timeout.
     * It's triggered when the conference is first created, or when the last participant leaves with an indication
     * that it will join a breakout room.
     */
    private Future<?> conferenceStartTimeout;

    private final Object conferenceStartTimeoutLock = new Object();

    /**
     * Reconnect timer. Used to stop the conference if XMPP connection is not restored in a given time.
     */
    private Future<?> reconnectTimeout;

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

    /** Whether to enable transcription via a colibri export. */
    private boolean enableTranscription = false;

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
     * Whether broadcasting to visitors is currently enabled.
     * TODO: support changing it.
     */
    private final boolean visitorsBroadcastEnabled = VisitorsConfig.config.getAutoEnableBroadcast();

    @NotNull private final Instant createdInstant = Instant.now();

    /**
     * The unique meeting ID for this conference. We expect this to be set by the XMPP server in the MUC config form.
     * If for some reason it's not present, we'll generate a local UUID. The field is nullable, but always non-null
     * after the MUC has been joined.
     */
    @Nullable
    private String meetingId;

    /** Presence extensions set from the outside which are to be added to the presence in each MUC. */
    private final Map<QName, ExtensionElement> presenceExtensions = new ConcurrentHashMap<>();

    /**
     * Creates new instance of {@link JitsiMeetConferenceImpl}.
     *
     * @param roomName name of MUC room that is hosting the conference.
     * @param listener the listener that will be notified about this instance events.
     * @param logLevel (optional) the logging level to be used by this instance. See {@link #logger} for more details.
     */
    public JitsiMeetConferenceImpl(
            @NotNull EntityBareJid roomName,
            ConferenceListener listener,
            @NotNull Map<String, String> properties,
            Level logLevel,
            String jvbVersion,
            boolean includeInStatistics,
            @NotNull JicofoServices jicofoServices)
    {
        logger = new LoggerImpl(JitsiMeetConferenceImpl.class.getName(), logLevel);
        logger.addContext("room", roomName.toString());

        this.config = new JitsiMeetConfig(properties);

        this.roomName = roomName;
        this.listener = listener;
        this.etherpadName = createSharedDocumentName();
        this.includeInStatistics = includeInStatistics;

        this.jicofoServices = jicofoServices;
        this.jvbVersion = jvbVersion;

        scheduleConferenceStartTimeout();

        visitorCodecs = new PreferenceAggregator(
            logger,
            (codecs) -> {
                setConferenceProperty(
                    ConferenceProperties.KEY_VISITOR_CODECS,
                    String.join(",", codecs)
                );
                return null;
            });

        logger.info("Created new conference.");
    }

    @Override
    @Nullable
    public String getMeetingId()
    {
        return meetingId;
    }

    @Nullable
    @Override
    public EntityBareJid getMainRoomJid()
    {
        return mainRoomJid;
    }

    @Override
    public List<EntityBareJid> getVisitorRoomsJids()
    {
        return this.visitorChatRooms.values().stream().map(ChatRoom::getRoomJid)
            .collect(Collectors.toList());
    }

    /**
     * @return the colibri session manager, late init.
     */
    private ColibriSessionManager getColibriSessionManager()
    {
        if (colibriSessionManager == null)
        {
            // We initialize colibriSessionManager only after having joined the room, so meetingId must be set.
            String meetingId = Objects.requireNonNull(this.meetingId);
            colibriSessionManager = new ColibriV2SessionManager(
                    jicofoServices.getXmppServices().getServiceConnection().getXmppConnection(),
                    jicofoServices.getBridgeSelector(),
                    getRoomName().toString(),
                    meetingId,
                    config.getRtcStatsEnabled(),
                    enableTranscription ? TranscriptionConfig.config.getUrl(meetingId) : null,
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

            if (clientXmppProvider.getRegistered())
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

        visitorCount.stop();

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
        if (includeInStatistics())
        {
            ConferenceMetrics.conferenceSeconds.addAndGet(
                    Duration.between(createdInstant, Instant.now()).toMillis() / 1000.0);
        }
        if (listener != null)
        {
            listener.conferenceEnded(this);
        }
    }

    /**
     * Returns <tt>true</tt> if the conference has been successfully started.
     */
    @Override
    public boolean isStarted()
    {
        return started.get();
    }

    /**
     * Initialize {@link #meetingId}, given an optional value coming from the chat room configuration. If no valid
     * meeting ID is provided, a random UUID will be generated.
     * @param chatRoomMeetingId the meeting ID that was set in the chat room configuration.
     * @throws RuntimeException if the meeting ID is already in use by another conference.
     */
    private void setMeetingId(String chatRoomMeetingId)
    {
        if (meetingId != null)
        {
            logger.error("Meeting ID is already set: " + meetingId + ", will not replace.");
            return;
        }

        String meetingId;
        if (isBlank(chatRoomMeetingId))
        {
            meetingId = UUID.randomUUID().toString();
            if (includeInStatistics)
            {
                logger.warn("No meetingId set for the MUC. Generating one locally.");
            }
        }
        else
        {
            meetingId = chatRoomMeetingId;
        }

        if (!listener.meetingIdSet(this, meetingId))
        {
            logger.error("Failed to set a unique meeting ID.");
            throw new RuntimeException("Failed to set a unique meeting ID.");
        }

        this.meetingId = meetingId;
        logger.addContext("meeting_id", meetingId);
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

        ChatRoom chatRoom = getClientXmppProvider().findOrCreateRoom(roomName, logger.getLevel());
        this.chatRoom = chatRoom;
        chatRoom.addListener(chatRoomListener);

        ChatRoomInfo chatRoomInfo = chatRoom.join();
        setMeetingId(chatRoomInfo.getMeetingId());

        mainRoomJid = chatRoomInfo.getMainRoomJid();

        AuthenticationAuthority authenticationAuthority = jicofoServices.getAuthenticationAuthority();
        if (authenticationAuthority != null)
        {
            chatRoomRoleManager = new AuthenticationRoleManager(chatRoom, authenticationAuthority);
            chatRoom.addListener(chatRoomRoleManager);
            chatRoomRoleManager.grantOwnership();
        }
        // We do not use auto-owner in breakout rooms.
        else if (ConferenceConfig.config.enableAutoOwner() && mainRoomJid == null)
        {
            chatRoomRoleManager = new AutoOwnerRoleManager(chatRoom);
            chatRoom.addListener(chatRoomRoleManager);
            chatRoomRoleManager.grantOwnership();
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

        if (VisitorsConfig.config.getEnabled())
        {
            setConferenceProperty(
                ConferenceProperties.KEY_VISITORS_ENABLED,
                Boolean.TRUE.toString(),
                false
            );
        }

        presenceExtensions.add(createConferenceProperties());
        this.presenceExtensions.forEach((qName, extension) -> presenceExtensions.add(extension));

        // updates presence with presenceExtensions and sends it
        chatRoom.addPresenceExtensions(presenceExtensions);
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
        ChatRoom chatRoom = this.chatRoom;
        if (updatePresence && chatRoom != null && !value.equals(oldValue))
        {
            ConferenceProperties newProps = createConferenceProperties();
            chatRoom.setPresenceExtension(newProps);

            for (final ChatRoom visitorChatRoom: visitorChatRooms.values())
            {
                visitorChatRoom.setPresenceExtension(newProps);
            }
        }
    }

    @Override
    public void setPresenceExtension(@NotNull ExtensionElement extension)
    {
        presenceExtensions.put(extension.getQName(), extension);

        ChatRoom chatRoom = this.chatRoom;
        if (chatRoom != null)
        {
            chatRoom.setPresenceExtension(extension);
        }
        for (final ChatRoom visitorChatRoom: visitorChatRooms.values())
        {
            visitorChatRoom.setPresenceExtension(extension);
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

        // first disconnect vnodes before leaving
        final List<ExtensionElement> disconnectVnodeExtensions;
        final List<ChatRoom> visitorChatRoomsToLeave;
        synchronized (visitorChatRooms)
        {
            disconnectVnodeExtensions = visitorChatRooms.keySet().stream()
                    .map(DisconnectVnodePacketExtension::new).collect(Collectors.toList());
            visitorChatRoomsToLeave = new ArrayList<>(visitorChatRooms.values());
            visitorChatRooms.clear();
        }

        final ChatRoom chatRoomToLeave = chatRoom;
        chatRoom.removeListener(chatRoomListener);
        chatRoom = null;

        TaskPools.getIoPool().submit(() ->
        {
            if (!disconnectVnodeExtensions.isEmpty())
            {
                jicofoServices.getXmppServices().getVisitorsManager()
                        .sendIqToComponentAndGetResponse(roomName, disconnectVnodeExtensions);
            }

            visitorChatRoomsToLeave.forEach(visitorChatRoom ->
            {
                try
                {
                    visitorChatRoom.removeAllListeners();
                    visitorChatRoom.leave();
                }
                catch (Exception e)
                {
                    logger.error("Failed to leave visitor room", e);
                }
            });

            chatRoomToLeave.leave();
        });
    }

    /**
     * Handles a new {@link ChatRoomMember} joining the {@link ChatRoom}: invites it as a {@link Participant} to the
     * conference if there are enough members.
     */
    private void onMemberJoined(@NotNull ChatRoomMember chatRoomMember)
    {
        // Detect a race condition in which this thread runs before EntityCapsManager's async StanzaListener that
        // populates the JID to NodeVerHash cache. If that's the case calling getFeatures() would result in an
        // unnecessary disco#info request being sent. That's not an unrecoverable problem, but just yielding should
        // avoid sending disco#info in most cases.
        Presence presence = chatRoomMember.getPresence();
        CapsExtension caps = presence == null ? null : presence.getExtension(CapsExtension.class);
        if ((caps != null) && caps.getHash() != null
                && EntityCapsManager.getNodeVerHashByJid(chatRoomMember.getOccupantJid()) == null)
        {
            logger.info("Caps extension present, but JID does not exist in EntityCapsManager.");
            Thread.yield();
        }

        // Trigger feature discovery before we acquire the lock. The features will be saved in the ChatRoomMember
        // instance, and the call might block for a disco#info request.
        chatRoomMember.getFeatures();

        synchronized (participantLock)
        {
            cancelConferenceStartTimeout();
            // Make sure it's still a member of the room.
            if (chatRoomMember.getChatRoom().getChatMember(chatRoomMember.getOccupantJid()) != chatRoomMember)
            {
                logger.warn("ChatRoomMember is no longer a member of its room. Will not invite.");
                return;
            }

            if (chatRoomMember.getRole() == MemberRole.VISITOR && !VisitorsConfig.config.getEnabled())
            {
                logger.warn("Ignoring a visitor because visitors are not configured:" + chatRoomMember.getName());
                return;
            }

            String room = ", room=";
            if (chatRoomMember.getChatRoom() == chatRoom)
            {
                room += "main";
            }
            else
            {
                room += chatRoomMember.getChatRoom().getRoomJid();
            }
            logger.info(
                    "Member joined:" + chatRoomMember.getName()
                            + " stats-id=" + chatRoomMember.getStatsId()
                            + " region=" + chatRoomMember.getRegion()
                            + " audioMuted=" + chatRoomMember.isAudioMuted()
                            + " videoMuted=" + chatRoomMember.isVideoMuted()
                            + " role=" + chatRoomMember.getRole()
                            + " isJibri=" + chatRoomMember.isJibri()
                            + " isJigasi=" + chatRoomMember.isJigasi()
                            + " isTranscriber=" + chatRoomMember.isTranscriber()
                            + room);

            // Are we ready to start ?
            if (!checkMinParticipants())
            {
                return;
            }

            // Cancel single participant timeout when someone joins ?
            cancelSingleParticipantTimeout();

            // Invite all not invited yet
            if (participants.isEmpty())
            {
                for (final ChatRoomMember member : chatRoom.getMembers())
                {
                    inviteChatMember(member);
                }
                for (final ChatRoom visitorChatRoom: visitorChatRooms.values())
                {
                    for (final ChatRoomMember member : visitorChatRoom.getMembers())
                    {
                        if (member.getRole() == MemberRole.VISITOR)
                        {
                            inviteChatMember(member);
                        }
                    }
                }
            }
            // Only the one who has just joined
            else
            {
                inviteChatMember(chatRoomMember);
            }
        }
    }

    /**
     * Adds a {@link ChatRoomMember} to the conference. Creates the
     * {@link Participant} instance corresponding to the {@link ChatRoomMember}.
     * established and videobridge channels being allocated.
     *
     * @param chatRoomMember the chat member to be invited into the conference.
     */
    private void inviteChatMember(ChatRoomMember chatRoomMember)
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
            Set<Features> features = chatRoomMember.getFeatures();
            logger.info("Creating participant " + chatRoomMember.getName() + " with features=" + features);
            final Participant participant = new Participant(
                    chatRoomMember,
                    this,
                    jicofoServices.getXmppServices().getJingleHandler(),
                    logger,
                    features);

            ConferenceMetrics.participants.inc();
            if (!features.contains(Features.START_MUTED_RMD))
            {
                ConferenceMetrics.participantsNoStartMutedRmd.inc();
            }

            boolean added = (participants.put(chatRoomMember.getOccupantJid(), participant) == null);
            if (added)
            {
                if (participant.isUserParticipant())
                {
                    userParticipantAdded();
                }
                else if (participant.getChatMember().getRole() == MemberRole.VISITOR)
                {
                    visitorAdded(participant.getChatMember().getVideoCodecs());
                }
            }

            inviteParticipant(participant, false, true);
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
        ChatRoom chatRoom = getChatRoom();
        if (chatRoom == null)
        {
            return false;
        }
        int minParticipants = ConferenceConfig.config.getMinParticipants();
        int memberCount = chatRoom.getMemberCount()
                + visitorChatRooms.values().stream().mapToInt(ChatRoom::getMemberCount).sum();
        return memberCount >= minParticipants;
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
                        /* no need to send source-remove */ false,
                        /* not reinviting */ false);
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
            else if (participants.isEmpty())
            {
                expireBridgeSessions();
            }
        }

        maybeStop(chatRoomMember);
    }

    /**
     * Stop the conference if there are no members and there are no associated breakout room.
     * @param chatRoomMember The participant leaving if any.
     */
    private void maybeStop(ChatRoomMember chatRoomMember)
    {
        ChatRoom chatRoom = this.chatRoom;
        if (chatRoom == null || chatRoom.getMemberCount() == 0)
        {
            if (jicofoServices.getFocusManager().hasBreakoutRooms(roomName))
            {
                logger.info("Breakout rooms still present, will not stop.");
            }
            else if (chatRoomMember != null
                    && chatRoomMember.getPresence() != null
                    && BREAKOUT_SWITCHING_STATUS.equals(chatRoomMember.getPresence().getStatus()))
            {
                logger.info("Member moving to breakout room, will not stop.");
                scheduleConferenceStartTimeout();
            }
            else
            {
                logger.info("Last member left, stopping.");
                stop();
            }
        }
    }

    /**
     * Signal to this conference that one of its associated breakout conferences ended.
     */
    public void breakoutConferenceEnded()
    {
        maybeStop(null);
    }

    private void terminateParticipant(
            Participant participant,
            @NotNull Reason reason,
            String message,
            boolean sendSessionTerminate,
            boolean sendSourceRemove,
            boolean willReinvite)
    {
        logger.info(String.format(
                "Terminating %s, reason: %s, send session-terminate: %s",
                participant.getChatMember().getName(),
                reason,
                sendSessionTerminate));

        synchronized (participantLock)
        {
            participant.terminateJingleSession(reason, message, sendSessionTerminate);

            // We can use updateParticipant=false here, because we'll call removeParticipant below.
            removeParticipantSources(participant, sendSourceRemove, false);

            Participant removed = participants.remove(participant.getChatMember().getOccupantJid());
            logger.info(
                    "Removed participant " + participant.getChatMember().getName() + " removed=" + (removed != null));
            if (!willReinvite && removed != null)
            {
                if (includeInStatistics())
                {
                    ConferenceMetrics.endpointSeconds.addAndGet(participant.durationSeconds());
                }
                if (removed.isUserParticipant())
                {
                    userParticipantRemoved();
                }
                else if (removed.getChatMember().getRole() == MemberRole.VISITOR)
                {
                    visitorRemoved(removed.getChatMember().getVideoCodecs());
                }
            }
        }

        getColibriSessionManager().removeParticipant(participant.getEndpointId());
    }

    @Override
    public void componentsChanged(@NotNull Set<XmppProvider.Component> components)
    {
    }

    @Override
    public void registrationChanged(boolean registered)
    {
        if (registered)
        {
            logger.info("XMPP reconnected");

            if (this.reconnectTimeout != null)
            {
                this.reconnectTimeout.cancel(true);
                this.reconnectTimeout = null;
            }
            else
            {
                logger.error("Reconnected but not supposed to be here:" + roomName);
            }

            XMPPConnection connection = chatRoom.getXmppProvider().getXmppConnection();
            if (connection instanceof XMPPTCPConnection && !((XMPPTCPConnection) connection).streamWasResumed())
            {
                logger.error("Reconnected without resuming, give up and stop.");

                // This is a connect without resumption, so make sure we fix the state, by stopping
                // all clients will reload the state will be fine when they invite us again.
                stop();
            }
        }
        else
        {
            logger.info("XMPP disconnected.");
            XMPPConnection connection = chatRoom.getXmppProvider().getXmppConnection();

            if (connection instanceof XMPPTCPConnection
                && ((XMPPTCPConnection) connection).isSmEnabled())
            {
                logger.info("XMPP will wait for a reconnect.");
                reconnectTimeout = TaskPools.getScheduledPool().schedule(
                        this::stop,
                        XmppConfig.client.getReplyTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            else
            {
                stop();
            }
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
        Pair<Bridge, String> existingBridgeSession
                = getColibriSessionManager().getBridgeSessionId(participant.getEndpointId());
        if (Objects.equals(bridgeSessionId, existingBridgeSession.getSecond()))
        {
            logger.info(String.format(
                    "Received ICE failed notification from %s, bridge-session ID: %s",
                    participant.getEndpointId(),
                    bridgeSessionId));
            if (existingBridgeSession.getFirst() != null)
            {
                existingBridgeSession.getFirst().endpointRequestedRestart();
            }
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
        Pair<Bridge, String> existingBridgeSession
                = getColibriSessionManager().getBridgeSessionId(participant.getEndpointId());
        if (!Objects.equals(bridgeSessionId, existingBridgeSession.getSecond()))
        {
            throw new InvalidBridgeSessionIdException(bridgeSessionId + " is not a currently active session");
        }

        if (reinvite && existingBridgeSession.getFirst() != null)
        {
            existingBridgeSession.getFirst().endpointRequestedRestart();
        }

        synchronized (participantLock)
        {
            terminateParticipant(
                    participant,
                    Reason.SUCCESS,
                    (reinvite) ? "reinvite requested" : null,
                    /* do not send session-terminate */ false,
                    /* do send source-remove */ true,
                    reinvite);

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
                null /* no change in sources, just transport */,
                null,
                false);
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
        getColibriSessionManager().updateParticipant(
                participant.getEndpointId(),
                null,
                participant.getSources(),
                null,
                false);
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
                null,
                false);

        sendSourceRemove(new ConferenceSourceMap(participantId, sourcesAcceptedToBeRemoved), participant);
    }

    /**
     * Handles a "session-accept" or "transport-accept" request from a participant.
     */
    void acceptSession(
            @NotNull Participant participant,
            @NotNull EndpointSourceSet sourcesAdvertised,
            IceUdpTransportPacketExtension transport,
            @Nullable InitialLastN initialLastN)
    throws ValidationFailedException
    {
        String participantId = participant.getEndpointId();

        EndpointSourceSet sourcesAccepted = EndpointSourceSet.EMPTY;
        if (!sourcesAdvertised.isEmpty())
        {
            sourcesAccepted = conferenceSources.tryToAdd(participantId, sourcesAdvertised);
        }

        getColibriSessionManager().updateParticipant(
                participantId,
                transport,
                getSourcesForParticipant(participant),
                initialLastN,
                false);

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
    private void removeParticipantSources(
            @NotNull Participant participant,
            boolean sendSourceRemove,
            boolean updateParticipant)
    {
        String participantId = participant.getEndpointId();
        EndpointSourceSet sourcesRemoved = conferenceSources.remove(participantId);

        if (sourcesRemoved != null && !sourcesRemoved.isEmpty())
        {
            if (updateParticipant)
            {
                getColibriSessionManager().updateParticipant(
                        participant.getEndpointId(),
                        null,
                        participant.getSources(),
                        null,
                        true);
            }

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
        return hasMember(jid, chatRoom) || visitorChatRooms.values().stream().anyMatch(c -> hasMember(jid, c));
    }

    private boolean hasMember(Jid jid, ChatRoom chatRoom)
    {
        return chatRoom != null
                && (jid instanceof EntityFullJid)
                && chatRoom.getChatMember((EntityFullJid) jid) != null;
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

        boolean colibriMuteState;
        MediaType colibriMediaType;
        if (mediaType == MediaType.AUDIO)
        {
            colibriMuteState = !chatRoom.isMemberAllowedToUnmute(toBeMutedJid, MediaType.AUDIO);
            colibriMediaType = MediaType.AUDIO;
        }
        else
        {
            colibriMuteState = !chatRoom.isMemberAllowedToUnmute(toBeMutedJid, MediaType.VIDEO) &&
                !chatRoom.isMemberAllowedToUnmute(toBeMutedJid, MediaType.DESKTOP);
            colibriMediaType = MediaType.VIDEO;
        }
        getColibriSessionManager().mute(participant.getEndpointId(), colibriMuteState, colibriMediaType);

        return MuteResult.SUCCESS;
    }

    @Override
    @NotNull
    public OrderedJsonObject getRtcstatsState()
    {
        return getDebugState(false);
    }

    @Override
    @NotNull
    public OrderedJsonObject getDebugState()
    {
        return getDebugState(true);
    }

    /**
     * @param full when false some high volume fields that aren't needed for rtcstats are suppressed.
     */
    private OrderedJsonObject getDebugState(boolean full)
    {
        OrderedJsonObject o = new OrderedJsonObject();
        o.put("name", roomName.toString());
        String meetingId = this.meetingId;
        if (meetingId != null)
        {
            o.put("meeting_id", meetingId);
        }
        o.put("config", config.getDebugState());
        ChatRoom chatRoom = this.chatRoom;
        o.put("chat_room", chatRoom == null ? "null" : chatRoom.getDebugState());
        OrderedJsonObject participantsJson = new OrderedJsonObject();
        for (Participant participant : participants.values())
        {
            participantsJson.put(participant.getEndpointId(), participant.getDebugState(full));
        }
        o.put("participants", participantsJson);
        //o.put("jibri_recorder", jibriRecorder.getDebugState());
        //o.put("jibri_sip_gateway", jibriSipGateway.getDebugState());
        ChatRoomRoleManager chatRoomRoleManager = this.chatRoomRoleManager;
        o.put("chat_room_role_manager", chatRoomRoleManager == null ? "null" : chatRoomRoleManager.getDebugState());
        o.put("started", started.get());
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

        int visitorCount = 0;
        int participantCount = 0;
        int jibriCount = 0;
        int jigasiCount = 0;
        int transcriberCount = 0;
        synchronized (participantLock)
        {
            for (Participant p : participants.values())
            {
                participantCount++;
                ChatRoomMember member = p.getChatMember();
                if (member.getRole() == MemberRole.VISITOR)
                {
                    visitorCount++;
                }
                if (member.isJibri())
                {
                    jibriCount++;
                }
                if (member.isTranscriber())
                {
                    transcriberCount++;
                }
                // Only count non-transcribing jigasis
                else if (member.isJigasi())
                {
                    jigasiCount++;
                }
            }
        }
        o.put("visitor_count", visitorCount);
        o.put("visitor_codecs", visitorCodecs.debugState());
        o.put("participant_count", participantCount);
        o.put("jibri_count", jibriCount);
        o.put("jigasi_count", jigasiCount);
        o.put("transcriber_count", transcriberCount);

        return o;
    }

    /**
     * Mutes all participants (except jibri or jigasi without "audioMute" support). Will block for colibri responses.
     */
    @Override
    public void muteAllParticipants(@NotNull MediaType mediaType, EntityFullJid actor)
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

                // we skip the participant that enabled the av moderation
                if (participant.getMucJid().equals(actor))
                {
                    continue;
                }

                participantsToMute.add(participant);
            }
        }

        // Sync the colibri force mute state with the AV moderation state.
        // We assume this is successful. If for some reason it wasn't the colibri layer should handle it (e.g. remove a
        // broken bridge).
        MediaType colibriMediaType = mediaType == MediaType.AUDIO ? MediaType.AUDIO : MediaType.VIDEO;
        Set<MediaType> mediaTypes = mediaType == MediaType.AUDIO
            ? Collections.singleton(MediaType.AUDIO)
            : EnumSet.of(MediaType.VIDEO, MediaType.DESKTOP);
        Set<String> participantIdsToMute = new HashSet<>();
        Set<String> participantIdsToUnmute = new HashSet<>();
        for (Participant p : participantsToMute)
        {
            if (ChatRoomKt.isMemberAllowedToUnmute(chatRoom, p.getMucJid(), mediaTypes))
            {
                participantIdsToUnmute.add(p.getEndpointId());
            }
            else
            {
                participantIdsToMute.add(p.getEndpointId());
            }
        }

        if (!participantIdsToMute.isEmpty())
        {
            getColibriSessionManager().mute(
                    participantIdsToMute,
                    true,
                    colibriMediaType);
        }
        if (!participantIdsToUnmute.isEmpty())
        {
            getColibriSessionManager().mute(
                    participantIdsToUnmute,
                    false,
                    colibriMediaType);
        }

        // Signal to the participants that they are being muted.
        for (Participant participant : participantsToMute)
        {
            AbstractMuteIq muteIq = createMuteIq(mediaType);
            muteIq.setType(IQ.Type.set);
            muteIq.setTo(participant.getMucJid());
            muteIq.setMute(true);
            UtilKt.tryToSendStanza(getClientXmppProvider().getXmppConnection(), muteIq);
        }
    }

    /**
     * Returns current participants count. A participant is chat member who has
     * some videobridge and media state assigned(not just raw chat room member).
     * For example chat member which belongs to the focus never becomes
     * a participant.
     */
    @Override
    public int getParticipantCount()
    {
        return participants.size();
    }

    @Override
    public long getVisitorCount()
    {
        synchronized (participantLock)
        {
            return participants.values().stream()
                    .filter(p -> p.getChatMember().getRole() == MemberRole.VISITOR)
                    .count();
        }
    }

    public Map<Bridge, ConferenceBridgeProperties> getBridges()
    {
        ColibriSessionManager colibriSessionManager = this.colibriSessionManager;
        if (colibriSessionManager == null)
        {
            return Collections.emptyMap();
        }
        return colibriSessionManager.getBridges();
    }

    @Override
    public boolean moveEndpoint(@NotNull String endpointId, Bridge bridge)
    {
        if (bridge != null)
        {
            List<String> bridgeParticipants = colibriSessionManager.getParticipants(bridge);
            if (!bridgeParticipants.contains(endpointId))
            {
                logger.warn("Endpoint " + endpointId + " is not connected to bridge " + bridge.getJid());
                return false;
            }
        }
        ColibriSessionManager colibriSessionManager = this.colibriSessionManager;
        if (colibriSessionManager == null)
        {
            return false;
        }

        colibriSessionManager.removeParticipant(endpointId);
        return reInviteParticipantsById(Collections.singletonList(endpointId)) == 1;
    }

    @Override
    public int moveEndpoints(@NotNull Bridge bridge, int numEps)
    {
        logger.info("Moving " + numEps + " endpoints from " + bridge.getJid());
        ColibriSessionManager colibriSessionManager = this.colibriSessionManager;
        if (colibriSessionManager == null)
        {
            return 0;
        }
        List<String> participantIds
                = colibriSessionManager.getParticipants(bridge).stream().limit(numEps).collect(Collectors.toList());
        for (String participantId : participantIds)
        {
            colibriSessionManager.removeParticipant(participantId);
        }
        return reInviteParticipantsById(participantIds);
    }

    /**
     * Checks whether a request for a new endpoint to join this conference should be redirected to a visitor node.
     * @return the name of the visitor node if it should be redirected, and null otherwise.
     */
    @Override
    @Nullable
    public String redirectVisitor(boolean visitorRequested, @Nullable String userId, @Nullable String groupId)
        throws Exception
    {
        logger.debug("redirectVisitor visitorRequested=" + visitorRequested + ", userId=" + userId
            + ", groupId=" + groupId);
        if (!VisitorsConfig.config.getEnabled())
        {
            return null;
        }

        // We don't support both visitors and a lobby. Once a lobby is enabled we don't use visitors anymore.
        ChatRoom chatRoom = this.chatRoom;
        if (chatRoom != null)
        {
            if (chatRoom.getLobbyEnabled())
            {
                logger.debug("Lobby enabled, not redirecting.");
                return null;
            }
            if (Boolean.FALSE.equals(chatRoom.getVisitorsEnabled()))
            {
                logger.warn("Visitors are disabled, not redirecting.");
                return null;
            }
        }
        if (VisitorsConfig.config.getRequireMucConfigFlag())
        {
            if (chatRoom == null || !Boolean.TRUE.equals(chatRoom.getVisitorsEnabled()))
            {
                logger.debug("RequireMucConfigFlag is set, and the room does not have the flag, not redirecting.");
                return null;
            }
        }
        // We don't support visitors in breakout rooms.
        if (mainRoomJid != null)
        {
            logger.debug("This is a breakout room, not redirecting.");
            return null;
        }

        long participantCount = getUserParticipantCount();
        boolean visitorsAlreadyUsed;
        synchronized (visitorChatRooms)
        {
            visitorsAlreadyUsed = !visitorChatRooms.isEmpty();
        }

        int participantsSoftLimit = VisitorsConfig.config.getMaxParticipants();
        if (chatRoom != null && chatRoom.getParticipantsSoftLimit() != null && chatRoom.getParticipantsSoftLimit() > 0)
        {
            participantsSoftLimit = chatRoom.getParticipantsSoftLimit();
        }

        logger.debug("redirectVisitor: participantsSoftLimit=" + participantsSoftLimit
            + ", visitorsAlreadyUsed=" + visitorsAlreadyUsed
            + ", visitorRequested=" + visitorRequested
            + ", participantCount=" + participantCount
            + ", participantsSoftLimit=" + participantsSoftLimit);
        if (visitorsAlreadyUsed || visitorRequested || participantCount >= participantsSoftLimit)
        {
            return selectVisitorNode();
        }

        return null;
    }

    private long userParticipantCount = 0;

    private void userParticipantAdded()
    {
        synchronized (participantLock)
        {
            userParticipantCount++;
        }
    }

    private void userParticipantRemoved()
    {
        synchronized(participantLock)
        {
            if (userParticipantCount <= 0)
            {
                logger.error("userParticipantCount out of sync - trying to reduce when value is " +
                    userParticipantCount);
            }
            else
            {
                userParticipantCount--;
            }
        }
    }

    /**
     * Get the number of participants for the purpose of visitor node selection. Exclude participants for jibri and
     * transcribers, because they shouldn't count towards the max participant limit.
     */
    private long getUserParticipantCount()
    {
        synchronized (participantLock)
        {
            return userParticipantCount;
        }
    }

    /**
     * Selects a visitor node for a new participant, and joins the associated chat room if not already joined
     * @return the ID of the selected node, or null if the endpoint is to be sent to the main room.
     * @throws Exception if joining the chat room failed.
     */
    @Nullable
    private String selectVisitorNode()
            throws Exception
    {
        ChatRoom chatRoomToJoin;
        String node;

        synchronized (visitorChatRooms)
        {
            node = ConferenceUtilKt.selectVisitorNode(
                    visitorChatRooms,
                    jicofoServices.getXmppServices().getVisitorConnections());
            if (node == null)
            {
                logger.warn("Visitor node required, but none available.");
                return null;
            }
            if (visitorChatRooms.containsKey(node))
            {
                visitorChatRooms.get(node).visitorInvited();
                // Already joined.
                return node;
            }

            // Join a new visitor chat room on the selected [node].
            XmppProvider xmppProvider = jicofoServices.getXmppServices().getXmppVisitorConnectionByName(node);
            if (xmppProvider == null)
            {
                logger.error("No XMPP provider for node " + node);
                return null;
            }

            XmppVisitorConnectionConfig config = XmppConfig.getVisitors().get(node);
            if (config == null)
            {
                logger.error("No XMPP config for node " + node);
                return null;
            }

            EntityBareJid visitorMucJid = getVisitorMucJid(
                    roomName,
                    jicofoServices.getXmppServices().getClientConnection(),
                    xmppProvider);

            // Will call join after releasing the lock
            chatRoomToJoin = xmppProvider.findOrCreateRoom(visitorMucJid, logger.getLevel());

            chatRoomToJoin.addListener(new VisitorChatRoomListenerImpl(chatRoomToJoin));

            visitorChatRooms.put(node, chatRoomToJoin);
            chatRoomToJoin.visitorInvited();
        }

        chatRoomToJoin.join();
        Collection<ExtensionElement> presenceExtensions = new ArrayList<>();

        ComponentVersionsExtension versionsExtension = new ComponentVersionsExtension();
        versionsExtension.addComponentVersion(
                ComponentVersionsExtension.COMPONENT_FOCUS,
                CurrentVersionImpl.VERSION.toString());
        presenceExtensions.add(versionsExtension);

        // TODO: what do we want to include in presence in visitor MUCs? Do we need to keep their conference
        // properties up to date?
        presenceExtensions.add(createConferenceProperties());

        this.presenceExtensions.forEach((qName, extension) -> presenceExtensions.add(extension));
        // updates presence with presenceExtensions and sends it
        chatRoomToJoin.addPresenceExtensions(presenceExtensions);

        if (this.visitorsBroadcastEnabled)
        {
            VisitorsManager visitorsManager = jicofoServices.getXmppServices().getVisitorsManager();
            visitorsManager.sendIqToComponent(
                    roomName,
                    Collections.singletonList(new ConnectVnodePacketExtension(node)));
        }
        else
        {
            logger.info("Redirected visitor, broadcast not enabled yet.");
        }
        return node;
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

    private int reInviteParticipantsById(@NotNull List<String> participantIdsToReinvite)
    {
        return reInviteParticipantsById(participantIdsToReinvite, true);
    }

    private int reInviteParticipantsById(@NotNull List<String> participantIdsToReinvite, boolean updateParticipant)
    {
        int n = participantIdsToReinvite.size();
        if (n == 0)
        {
            return 0;
        }

        List<Participant> participantsToReinvite = new ArrayList<>();
        synchronized (participantLock)
        {
            for (Participant participant : participants.values())
            {
                if (participantsToReinvite.size() == n)
                {
                    break;
                }
                if (participantIdsToReinvite.contains(participant.getEndpointId()))
                {
                    participantsToReinvite.add(participant);
                }
            }
            if (participantsToReinvite.size() != participantIdsToReinvite.size())
            {
                logger.error("Can not re-invite all participants, no Participant object for some of them.");
            }
            reInviteParticipants(participantsToReinvite, updateParticipant);
        }
        ConferenceMetrics.participantsMoved.addAndGet(participantsToReinvite.size());
        return participantsToReinvite.size();
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
                /* send source-remove */ true,
                /* not reinviting */ false);
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
        reInviteParticipants(participants, true);
    }

    /**
     * Re-invites {@link Participant}s into the conference.
     *
     * @param participants the list of {@link Participant}s to be re-invited.
     * @param updateParticipant flag to check if update participant call should be called.
     */
    private void reInviteParticipants(Collection<Participant> participants, boolean updateParticipant)
    {
        synchronized (participantLock)
        {
            for (Participant participant : participants)
            {
                participant.setInviteRunnable(null);
                boolean restartJingle = ConferenceConfig.config.getReinviteMethod() == ReinviteMethod.RestartJingle;

                if (restartJingle)
                {
                    removeParticipantSources(participant, true, updateParticipant);
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

    private void cancelConferenceStartTimeout()
    {
        synchronized (conferenceStartTimeoutLock)
        {
            if (conferenceStartTimeout != null)
            {
                conferenceStartTimeout.cancel(true);
                conferenceStartTimeout = null;
            }
        }
    }

    /**
     * If there is a scheduled "conference start" timeout, cancel it and re-schedule with the configured delay. If
     * there isn't one scheduled, doesn't do anything.
     */
    public void rescheduleConferenceStartTimeout()
    {
        synchronized (conferenceStartTimeoutLock)
        {
            if (conferenceStartTimeout != null)
            {
                cancelConferenceStartTimeout();
                scheduleConferenceStartTimeout();
            }
        }
    }

    /**
     * Schedules conference start timeout.
     */
    private void scheduleConferenceStartTimeout()
    {
        synchronized (conferenceStartTimeoutLock)
        {
            cancelConferenceStartTimeout();
            conferenceStartTimeout = TaskPools.getScheduledPool().schedule(
                    () ->
                    {
                        if (includeInStatistics)
                        {
                            logger.info("Expiring due to initial timeout.");
                        }

                        // in case of last participant leaving to join a breakout room, we want to skip destroy
                        if (jicofoServices.getFocusManager().hasBreakoutRooms(roomName))
                        {
                            logger.info("Breakout rooms present, will not stop.");
                            return;
                        }

                        stop();
                    },
                    ConferenceConfig.config.getConferenceStartTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Called when a new visitor has been added to the conference. */
    private void visitorAdded(List<String> codecs)
    {
        visitorCount.adjustValue(+1);
        if (codecs != null)
        {
            visitorCodecs.addPreference(codecs);
        }
    }

    /** Called when a new visitor has been added to the conference. */
    private void visitorRemoved(List<String> codecs)
    {
        visitorCount.adjustValue(-1);
        if (codecs != null)
        {
            visitorCodecs.removePreference(codecs);
        }
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
        if (ConferenceConfig.config.getEnableModeratorChecks())
        {
            return MemberRoleKt.hasModeratorRights(getRoleForMucJid(from));
        }

        return true;
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
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return String.format("JitsiMeetConferenceImpl[name=%s]", getRoomName());
    }

    @Override
    public boolean isRtcStatsEnabled()
    {
        return config.getRtcStatsEnabled();
    }

    private void setEnableTranscribing(boolean enable)
    {
        if (enableTranscription == enable)
        {
            return;
        }

        logger.info("Setting enableTranscribing=" + enable);
        enableTranscription = enable;
        setConferenceProperty(ConferenceProperties.KEY_AUDIO_RECORDING_ENABLED, enable ? "true" : "false");

        String meetingId = JitsiMeetConferenceImpl.this.meetingId;
        ColibriSessionManager colibriSessionManager = JitsiMeetConferenceImpl.this.colibriSessionManager;

        if (meetingId == null || colibriSessionManager == null)
        {
            // The new value will take effect when colibriSessionManager is initialized (after the room is joined and
            // meetingId is set).
            return;
        }

        TemplatedUrl uri = enable ? TranscriptionConfig.config.getUrl(meetingId) : null;
        if (enable && uri == null)
        {
            logger.info("Transcription enabled, but no URL is configured.");
            return;
        }

        colibriSessionManager.setTranscriberUrl(uri);
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
        void conferenceEnded(@NotNull JitsiMeetConferenceImpl conference);

        /**
         * Fire an event attempting to set the meeting ID for the conference. The implementation should return `false`
         * in case another meeting with the same ID already exists, which will result in an exception.
         * @param conference the conference.
         * @param meetingId the meetingId to attempt.
         * @return true if the given meetingId was free and was associated with the conference, false otherwise.
         */
        boolean meetingIdSet(@NotNull JitsiMeetConferenceImpl conference, @NotNull String meetingId);
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
                            /* send source-remove */ false,
                            /* not reinviting */ false);

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
        public void bridgeFailedHealthCheck(@NotNull Bridge bridge)
        {
            removeBridge(bridge, "failed health check");
        }

        @Override
        public void bridgeRemoved(@NotNull Bridge bridge)
        {
            removeBridge(bridge, "was removed");
        }

        @Override
        public void bridgeAdded(Bridge bridge)
        {
            onBridgeUp(bridge.getJid());
        }

        private void removeBridge(@NotNull Bridge bridge, @NotNull String reason)
        {
            List<String> participantIdsToReinvite
                    = colibriSessionManager != null
                    ? colibriSessionManager.removeBridge(bridge) : Collections.emptyList();
            if (!participantIdsToReinvite.isEmpty())
            {
                logger.info("Re-inviting " + participantIdsToReinvite + " because " + bridge.getJid() + " " + reason);
                reInviteParticipantsById(participantIdsToReinvite);
            }
        }

    }

    /**
     * Handle events from members in one of the visitor MUCs.
     */
    private class VisitorChatRoomListenerImpl extends DefaultChatRoomListener
    {
        private final Logger logger = JitsiMeetConferenceImpl.this.logger.createChildLogger(
                VisitorChatRoomListenerImpl.class.getSimpleName());
        private final ChatRoom chatRoom;

        private VisitorChatRoomListenerImpl(ChatRoom chatRoom)
        {
            this.chatRoom = chatRoom;
            logger.addContext("visitor_muc", chatRoom.getRoomJid().toString());
        }

        @Override
        public void roomDestroyed(String reason)
        {
            logger.info("Visitor room destroyed with reason=" + reason);
            ChatRoom chatRoomToLeave = null;
            String vnode = null;
            synchronized (visitorChatRooms)
            {
                Map.Entry<String, ChatRoom> entry
                        = visitorChatRooms.entrySet().stream()
                            .filter(e -> e.getValue() == chatRoom).findFirst().orElse(null);
                if (entry != null)
                {
                    chatRoomToLeave = entry.getValue();
                    vnode = entry.getKey();
                    visitorChatRooms.remove(vnode);
                }
            }

            if (chatRoomToLeave != null)
            {
                ChatRoom finalChatRoom = chatRoomToLeave;
                TaskPools.getIoPool().submit(() ->
                {
                    try
                    {
                        logger.info("Removing visitor chat room");
                        finalChatRoom.leave();
                    }
                    catch (Exception e)
                    {
                        logger.warn("Error while leaving chat room.", e);
                    }
                });

                if (vnode != null)
                {
                    jicofoServices.getXmppServices().getVisitorsManager().sendIqToComponent(
                            roomName, Collections.singletonList(new DisconnectVnodePacketExtension(vnode)));
                }
            }
        }

        @Override
        public void memberJoined(@NotNull ChatRoomMember member)
        {
            if (member.getRole() != MemberRole.VISITOR)
            {
                logger.debug("Ignoring non-visitor member of visitor room: " + member);
                return;
            }
            // Run in the IO pool because feature discovery may send disco#info and block for a response, and shouldn't
            // run in Smack's thread.
            TaskPools.getIoPool().submit(() -> onMemberJoined(member));
        }

        @Override
        public void memberKicked(@NotNull ChatRoomMember member)
        {
            if (member.getRole() != MemberRole.VISITOR)
            {
                logger.debug("Member kicked for non-visitor member of visitor room: " + member);
                return;
            }
            onMemberKicked(member);
        }

        @Override
        public void memberLeft(@NotNull ChatRoomMember member)
        {
            if (member.getRole() != MemberRole.VISITOR)
            {
                logger.debug("Member left for non-visitor member of visitor room: " + member);
                return;
            }
            onMemberLeft(member);
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
            // Run in the IO pool because feature discovery may send disco#info and block for a response, and shouldn't
            // run in Smack's thread.
            TaskPools.getIoPool().submit(() -> onMemberJoined(member));
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
        public void localRoleChanged(@NotNull MemberRole newRole)
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

        @Override
        public void transcribingEnabledChanged(boolean enabled)
        {
            setEnableTranscribing(enabled);
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
            if (chatRoom != null)
            {
                chatRoom.addPresenceExtensionIfMissing(new BridgeNotAvailablePacketExt());
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
                // Remove any "bridge not available" extensions.
                chatRoom.removePresenceExtensions(e -> e instanceof BridgeNotAvailablePacketExt);
            }
        }

        @Override
        public void bridgeRemoved(@NotNull Bridge bridge, @NotNull List<String> participantIds)
        {
            ConferenceMetrics.bridgesRemoved.inc();
            logger.info("Bridge " + bridge + " was removed from the conference. Re-inviting its participants: "
                    + participantIds);
            reInviteParticipantsById(participantIds);
        }

        @Override
        public void endpointRemoved(@NotNull String endpointId)
        {
            logger.info("Endpoint " + endpointId + " was removed from the conference. Re-inviting participant.");
            reInviteParticipantsById(Collections.singletonList(endpointId), false);
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
