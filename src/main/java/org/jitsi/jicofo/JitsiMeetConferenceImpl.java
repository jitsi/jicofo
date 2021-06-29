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
package org.jitsi.jicofo;

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jetbrains.annotations.Nullable;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.jicofo.version.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.impl.protocol.xmpp.colibri.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import java.util.stream.*;

import static org.jitsi.jicofo.JitsiMeetConferenceImpl.MuteResult.*;
import static org.jitsi.jicofo.xmpp.IqProcessingResult.*;

/**
 * Represents a Jitsi Meet conference. Manages the Jingle sessions with the
 * participants, as well as the COLIBRI session with the jitsi-videobridge
 * instances used for the conference.
 *
 * A note on synchronization: this class uses a lot of 'synchronized' blocks,
 * on 4 different objects ({@link #bridges}, {@link #participantLock},
 * {@code this} and {@link BridgeSession#octoParticipant}). At the time of this
 * writing it seems that multiple locks are acquired only in the following
 * order: * {@code participantsLock} -> {@code bridges}.
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
     * A "global" identifier of this {@link JitsiMeetConferenceImpl} (i.e.
     * a unique ID across a set of independent jicofo instances).
     */
    private final long gid;

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

    /**
     * An executor to be used for tasks related to this conference, which need to execute in order and which may block.
     */
    private final QueueExecutor queueExecutor;

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
     * Operation set used to handle Jingle sessions with conference
     * participants.
     */
    private OperationSetJingle jingle;

    /**
     * The list of all conference participants.
     */
    private final List<Participant> participants = new CopyOnWriteArrayList<>();

    /**
     * This lock is used to synchronise write access to {@link #participants}.
     *
     * WARNING: To avoid deadlocks we must make sure that any code paths that
     * lock both {@link #bridges} and {@link #participantLock} does so in the
     * correct order. The lock on {@link #participantLock} must be acquired
     * first.
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
     * Contains the flags which indicate whether participants being invited
     * to the conference as a result of joining (as opposed to having already
     * joined) should be invited with the "start muted" option. The element at
     * offset 0 is for audio, at offset 1 for video.
     */
    private boolean[] startMuted = { false, false };

    /**
     * The name of shared Etherpad document. Is advertised through MUC Presence
     * by Jicofo user.
     */
    private final String etherpadName;

    /**
     * The list of {@link BridgeSession} currently in use by this conference.
     *
     * WARNING: To avoid deadlocks we must make sure that any code paths that
     * lock both {@link #bridges} and {@link #participantLock} does so in the
     * correct order. The lock on {@link #participantLock} must be acquired
     * first.
     *
     */
    private final List<BridgeSession> bridges = new LinkedList<>();

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

        this.gid = gid;
        this.roomName = roomName;
        this.listener = listener;
        this.etherpadName = createSharedDocumentName();
        this.includeInStatistics = includeInStatistics;

        this.jicofoServices = Objects.requireNonNull(JicofoServices.jicofoServicesSingleton);
        queueExecutor = new QueueExecutor(TaskPools.getIoPool(), "JitsiMeetConference-queueExecutor", logger);

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
    void stop()
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

        try
        {
            disposeConference();
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

        chatRoom = getClientXmppProvider().findOrCreateRoom(roomName);
        chatRoom.addListener(chatRoomListener);
        chatRoom.setEventExecutor(queueExecutor);

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
     * Creates a new {@link ColibriConference} instance for use by this
     * {@link JitsiMeetConferenceImpl}.
     */
    // False positive
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private ColibriConference createNewColibriConference(Jid bridgeJid)
    {
        ColibriConferenceImpl colibriConference
                = new ColibriConferenceImpl(
                        jicofoServices.getXmppServices().getServiceConnection().getXmppConnection());
        // JVB expects the hex string
        colibriConference.setGID(Long.toHexString(gid));

        colibriConference.setName(chatRoom.getRoomJid());
        String meetingId = chatRoom.getMeetingId();
        if (meetingId != null)
        {
            colibriConference.setMeetingId(meetingId);
        }
        colibriConference.setJitsiVideobridge(bridgeJid);

        return colibriConference;
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

            final Participant participant = new Participant(chatRoomMember, logger);

            participants.add(participant);
            inviteParticipant(participant, false, hasToStartMuted(participant, justJoined));
        }
    }

    /**
     * Selects a {@link Bridge} to use for a specific {@link Participant}.
     *
     * @param participant the participant for which to select a
     * {@link Bridge}.
     * @return the {@link Bridge}, or {@code null} if one could not be
     * found or the participant already has an associated {@link Bridge}.
     */
    private Bridge selectBridge(Participant participant)
    {
        if (findBridgeSession(participant) != null)
        {
            // This should never happen.
            logger.error("The participant already has a bridge:" + participant.getChatMember().getName());
            return null;
        }

        // Select a Bridge for the new participant.
        BridgeSelector bridgeSelector = jicofoServices.getBridgeSelector();
        Bridge bridge = bridgeSelector.selectBridge(this, participant.getChatMember().getRegion());

        if (bridge == null)
        {
            // Can not find a bridge to use.
            logger.error("Can not invite participant, no bridge available: " + participant.getChatMember().getName());

            if (chatRoom != null
                && !chatRoom.containsPresenceExtension(
                BridgeNotAvailablePacketExt.ELEMENT_NAME,
                BridgeNotAvailablePacketExt.NAMESPACE))
            {
                chatRoom.setPresenceExtension(new BridgeNotAvailablePacketExt(), false);
            }
            return null;

        }

        return bridge;
    }

    /**
     * Get a stream of those bridges which are operational.
     * Caller should be synchronized on bridges.
     */
    private Stream<BridgeSession> operationalBridges()
    {
        return bridges.stream().filter(session -> !session.hasFailed && session.bridge.isOperational());
    }

    /**
     * Removes all non-operational bridges from the conference and re-invites their participants.
     */
    private void removeNonOperationalBridges()
    {
        Set<Jid> nonOperationalBridges = bridges.stream()
                .filter(session -> session.hasFailed || !session.bridge.isOperational())
                .map(session -> session.bridge.getJid())
                .collect(Collectors.toSet());
        if (!nonOperationalBridges.isEmpty())
        {
            onMultipleBridgesDown(nonOperationalBridges);
        }
    }

    /**
     * Invites a {@link Participant} to the conference. Selects the
     * {@link BridgeSession} to use and starts a new {@link
     * ParticipantChannelAllocator} to allocate COLIBRI channels and initiate
     * a Jingle session with the {@link Participant}.
     * @param participant the participant to invite.
     * @param reInvite whether the participant is to be re-invited or invited
     * for the first time.
     * @param startMuted an array of size 2, which will determine whether the
     * offer sent to the participant should indicate that the participant
     * should start audio muted (depending on the value of the element at
     * index 0) and video muted (depending on the value of the element at
     * index 1).
     */
    private void inviteParticipant(
            Participant participant,
            boolean reInvite,
            boolean[] startMuted)
    {
        BridgeSession bridgeSession;

        // Some of the bridges in the conference may have become non-operational. Inviting a new participant to the
        // conference requires communication with its bridges, so we remove them from the conference first.
        removeNonOperationalBridges();

        synchronized (bridges)
        {
            Bridge bridge = selectBridge(participant);
            if (bridge == null)
            {
                logger.error("Failed to select a bridge for " + participant);
                return;
            }

            if (!bridge.isOperational())
            {
                logger.error("The selected bridge is non-operational: " + bridge);
            }

            bridgeSession = findBridgeSession(bridge);
            if (bridgeSession == null)
            {
                // The selected bridge is not yet used for this conference,
                // so initialize a new BridgeSession
                bridgeSession = new BridgeSession(bridge);

                bridges.add(bridgeSession);
                setConferenceProperty(
                    ConferenceProperties.KEY_BRIDGE_COUNT,
                    Integer.toString(bridges.size()));

                if (operationalBridges().count() >= 2)
                {
                    if (!getFocusManager().isJicofoIdConfigured())
                    {
                        logger.warn(
                            "Enabling Octo while the jicofo ID is not set. Configure a valid value [1-65535] by "
                            + "setting org.jitsi.jicofo.SHORT_ID in sip-communicator.properties or jicofo.octo.id in "
                            + "jicofo.conf. Future versions will require this for Octo.");
                    }
                    // Octo needs to be enabled (by inviting an Octo
                    // participant for each bridge), or if it is already enabled
                    // the list of relays for each bridge may need to be
                    // updated.
                    updateOctoRelays();
                }
            }

            bridgeSession.addParticipant(participant);
            participant.setBridgeSession(bridgeSession);
            logger.info("Added participant id= " + participant.getChatMember().getName()
                            + ", bridge=" + bridgeSession.bridge.getJid());

            // Colibri channel allocation and jingle invitation take time, so
            // schedule them on a separate thread.
            ParticipantChannelAllocator channelAllocator
                = new ParticipantChannelAllocator(
                        this,
                        bridgeSession,
                        participant,
                        startMuted,
                        reInvite,
                        logger);

            participant.setChannelAllocator(channelAllocator);
            TaskPools.getIoPool().submit(channelAllocator);

            if (reInvite)
            {
                propagateNewSourcesToOcto(
                    participant.getBridgeSession(), participant.getSourcesCopy(), participant.getSourceGroupsCopy());
            }
        }
    }

    /**
     * Re-calculates the Octo relays for bridges in the conference, and updates
     * each bridge session.
     */
    private void updateOctoRelays()
    {
        if (!OctoConfig.config.getEnabled())
        {
            return;
        }

        synchronized (bridges)
        {
            List<String> allRelays = getAllRelays(null);

            logger.info("Updating Octo relays: " + allRelays);
            operationalBridges().forEach(bridge -> bridge.setRelays(allRelays));
        }
    }

    /**
     * @param exclude a relay id to exclude from the result.
     * @return the set of all Octo relays of bridges in the conference, except
     * for {@code exclude}.
     */
    private List<String> getAllRelays(String exclude)
    {
        synchronized (bridges)
        {
            return
                operationalBridges()
                    .map(bridge -> bridge.bridge.getRelayId())
                    .filter(Objects::nonNull)
                    .filter(bridge -> !bridge.equals(exclude))
                    .collect(Collectors.toList());
        }
    }

    /**
     * @return the {@link BridgeSession} instance which is used for a specific
     * {@link Participant}, or {@code null} if there is no bridge for the
     * participant.
     * @param participant the {@link Participant} for which to find the bridge.
     */
    private BridgeSession findBridgeSession(Participant participant)
    {
        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                if (bridgeSession.participants.contains(participant))
                {
                    return bridgeSession;
                }
            }
        }
        return null;
    }

    /**
     * @return the {@link BridgeSession} instance used by this
     * {@link JitsiMeetConferenceImpl} which corresponds to a particular
     * jitsi-videobridge instance represented by a {@link Bridge}, or
     * {@code null} if the {@link Bridge} is not currently used in this
     * conference.
     * @param state the {@link BridgeSession} which represents a particular
     * jitsi-videobridge instance for which to return the {@link BridgeSession}.
     */
    private BridgeSession findBridgeSession(Bridge state)
    {
        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                if (bridgeSession.bridge.equals(state))
                {
                    return bridgeSession;
                }
            }
        }

        return null;
    }

    /**
     * @return the {@link BridgeSession} instance used by this
     * {@link JitsiMeetConferenceImpl} which corresponds to a particular
     * jitsi-videobridge instance represented by its JID, or
     * {@code null} if the {@link Bridge} is not currently used in this
     * conference.
     * @param jid the XMPP JID which represents a particular
     * jitsi-videobridge instance for which to return the {@link BridgeSession}.
     */
    private BridgeSession findBridgeSession(Jid jid)
    {
        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                if (bridgeSession.bridge.getJid().equals(jid))
                {
                    return bridgeSession;
                }
            }
        }

        return null;
    }

    /**
     * Returns array of boolean values that indicates whether the last
     * participant have to start video or audio muted.
     * @param participant the participant
     * @param justJoined indicates whether the participant joined the room now
     * or he was in the room before.
     * @return array of boolean values that indicates whether the last
     * participant have to start video or audio muted. The first element
     * should be associated with the audio and the second with video.
     */
    private boolean[] hasToStartMuted(Participant participant, boolean justJoined)
    {
        final boolean[] startMuted = new boolean[] {false, false};
        if (this.startMuted != null && this.startMuted[0] && justJoined)
        {
            startMuted[0] = true;
        }

        if (this.startMuted != null && this.startMuted[1] && justJoined)
        {
            startMuted[1] = true;
        }

        if (startMuted[0] && startMuted[1])
        {
            return startMuted;
        }

        int participantNumber
            = participant != null
                    ? participant.getChatMember().getJoinOrderNumber()
                    : participants.size();

        if (!startMuted[0])
        {
            Integer startAudioMuted = config.getStartAudioMuted();
            if (startAudioMuted != null)
            {
                startMuted[0] = (participantNumber > startAudioMuted);
            }
        }

        if (!startMuted[1])
        {
            Integer startVideoMuted = config.getStartVideoMuted();
            if(startVideoMuted != null)
            {
                startMuted[1] = (participantNumber > startVideoMuted);
            }
        }

        return startMuted;
    }

    /**
     * Returns {@code true} if there are enough participants in the room to start a conference.
     */
    private boolean checkMinParticipants()
    {
        int minParticipants = ConferenceConfig.config.getMinParticipants();
        return chatRoom.getMembersCount() >= minParticipants;
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
     * Disposes of this conference. Expires all allocated COLIBRI conferences.
     *
     * Does not terminate jingle sessions with its participants (why???).
     *
     */
    private void disposeConference()
    {
        // If the conference is being disposed the timeout is not needed
        // anymore
        cancelSingleParticipantTimeout();

        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                // No need to expire channels, just expire the whole colibri
                // conference.
                // bridgeSession.terminateAll();
                bridgeSession.dispose();
            }
            bridges.clear();
            setConferenceProperty(
                ConferenceProperties.KEY_BRIDGE_COUNT,
                "0");
        }

        // TODO: what about removing the participants and ending their jingle
        // session?
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
                stop();
            }
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
            if (participant.isSessionEstablished())
            {
                JingleSession jingleSession = participant.getJingleSession();

                jingle.terminateSession(jingleSession, reason, message, sendSessionTerminate);

                removeSources(
                    jingleSession,
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy(),
                    false /* no JVB update - will expire */,
                    sendSourceRemove);

                participant.setJingleSession(null);
            }

            boolean removed = participants.remove(participant);
            logger.info("Removed participant " + participant.getChatMember().getName() + " removed=" + removed);
        }

        BridgeSession bridgeSession = terminateParticipantBridgeSession(participant);
        if (bridgeSession != null)
        {
            maybeExpireBridgeSession(bridgeSession);
        }
    }

    /**
     * Expires the session with a particular bridge if it has no real (non-octo)
     * participants left.
     * @param bridgeSession the bridge session to expire.
     */
    private void maybeExpireBridgeSession(BridgeSession bridgeSession)
    {
        synchronized (bridges)
        {
            if (bridgeSession.participants.isEmpty())
            {
                bridgeSession.terminateAll();
                bridges.remove(bridgeSession);
                setConferenceProperty(
                    ConferenceProperties.KEY_BRIDGE_COUNT,
                    Integer.toString(bridges.size()));

                updateOctoRelays();
            }
        }
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
    public XMPPError onSessionAccept(@NotNull JingleSession jingleSession, List<ContentPacketExtension> answer)
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
    public XMPPError onSessionInfo(@NotNull JingleSession session, JingleIQ iq)
    {
        Jid address = session.getAddress();
        Participant participant = findParticipantForJingleSession(session);

        // FIXME: (duplicate) there's very similar logic in onSessionAccept
        if (participant == null)
        {
            String errorMsg = "No session for " + address;
            logger.warn(errorMsg);
            return XMPPError.from(XMPPError.Condition.item_not_found, errorMsg).build();
        }

        IceStatePacketExtension iceStatePE
                = iq.getExtension(
                        IceStatePacketExtension.ELEMENT_NAME,
                        IceStatePacketExtension.NAMESPACE);
        String iceState = iceStatePE != null ? iceStatePE.getText() : null;

        if (!"failed".equalsIgnoreCase(iceState))
        {
            logger.info(String.format("Ignored ice-state %s from %s", iceState, address));

            return null;
        }

        BridgeSessionPacketExtension bsPE = getBridgeSessionPacketExtension(iq);
        String bridgeSessionId = bsPE != null ? bsPE.getId() : null;
        BridgeSession bridgeSession = findBridgeSession(participant);

        if (bridgeSession != null && bridgeSession.id.equals(bridgeSessionId))
        {
            logger.info(String.format(
                    "Received ICE failed notification from %s, session: %s",
                    address,
                    bridgeSession));
            reInviteParticipant(participant);
        }
        else
        {
            logger.info(String.format(
                    "Ignored ICE failed notification for invalid session,"
                        + " participant: %s, bridge session ID: %s",
                    address,
                    bridgeSessionId));
        }
        listener.participantIceFailed();

        return null;
    }

    private BridgeSessionPacketExtension getBridgeSessionPacketExtension(@NotNull IQ iq)
    {
        return iq.getExtension(BridgeSessionPacketExtension.ELEMENT_NAME, BridgeSessionPacketExtension.NAMESPACE);
    }

    /**
     * Handles 'session-terminate' received from the client.
     *
     * {@inheritDoc}
     */
    @Override
    public XMPPError onSessionTerminate(JingleSession session, JingleIQ iq)
    {
        Participant participant = findParticipantForJingleSession(session);

        // FIXME: (duplicate) there's very similar logic in onSessionAccept/onSessionInfo
        if (participant == null)
        {
            String errorMsg = "No participant for " + session.getAddress();
            logger.warn(errorMsg);
            return XMPPError.from(XMPPError.Condition.item_not_found, errorMsg).build();
        }

        BridgeSessionPacketExtension bsPE = getBridgeSessionPacketExtension(iq);
        String bridgeSessionId = bsPE != null ? bsPE.getId() : null;
        BridgeSession bridgeSession = findBridgeSession(participant);
        boolean restartRequested = bsPE != null && bsPE.isRestart();

        if (restartRequested)
        {
            listener.participantRequestedRestart();
        }

        if (bridgeSession == null || !bridgeSession.id.equals(bridgeSessionId))
        {
            logger.info(String.format(
                    "Ignored session-terminate for invalid session: %s, bridge session ID: %s restart: %s",
                    participant,
                    bridgeSessionId,
                    restartRequested));

            return XMPPError.from(XMPPError.Condition.item_not_found, "invalid bridge session ID").build();
        }

        logger.info(String.format(
                "Received session-terminate from %s, bridge session: %s, restart: %s",
                participant,
                bridgeSession,
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
                    inviteParticipant(participant, false, hasToStartMuted(participant, false));
                }
                else
                {
                    logger.warn(String.format("Rate limiting %s for restart requests", participant));

                    return XMPPError.from(XMPPError.Condition.resource_constraint, "rate-limited").build();
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
     * @param sources the <tt>MediaSourceMap</tt> with the sources to advertise.
     * @param sourceGroups the <tt>MediaSourceGroupMap</tt> with source groups
     *        to advertise.
     */
    private void propagateNewSources(
        Participant sourceOwner,
        MediaSourceMap sources,
        MediaSourceGroupMap sourceGroups)
    {
        participants.stream()
            .filter(otherParticipant -> otherParticipant != sourceOwner)
            .forEach(
                participant ->
                {
                    if (participant.isSessionEstablished())
                    {
                        jingle.sendAddSourceIQ(sources, sourceGroups, participant.getJingleSession());
                    }
                    else
                    {
                        logger.warn("No jingle session yet for " + participant.getChatMember().getName());

                        participant.scheduleSourcesToAdd(sources);
                        participant.scheduleSourceGroupsToAdd(sourceGroups);
                    }
                });
    }

    /**
     * Adds the specified sources and source groups to the Octo participants
     * of all bridges except for {@code exclude}.
     * @param exclude the bridge to which sources will not be added (i.e. the
     * bridge to which the participant whose sources we are adding is
     * connected).
     * @param sources the sources to add.
     * @param sourceGroups the source groups to add.
     */
    private void propagateNewSourcesToOcto(
        BridgeSession exclude,
        MediaSourceMap sources,
        MediaSourceGroupMap sourceGroups)
    {
        synchronized (bridges)
        {
            operationalBridges()
                .filter(bridge -> !bridge.equals(exclude))
                .forEach(bridge -> bridge.addSourcesToOcto(sources, sourceGroups));
        }
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

        // Participant will figure out bundle or non-bundle transport based on its hasBundleSupport() value
        participant.addTransportFromJingle(contentList);

        BridgeSession bridgeSession = findBridgeSession(participant);
        // We can hit null here during conference restart, but the state will be synced up later when the client
        // sends 'transport-accept'
        // XXX FIXME: we half-handled this above!
        if (bridgeSession == null)
        {
            logger.warn("Skipped transport-info processing - no bridge session for " + session.getAddress());
            return;
        }

        bridgeSession.colibriConference.updateBundleTransportInfo(
                participant.getBundleTransport(),
                participant.getEndpointId());
    }

    /**
     * 'transport-accept' message is received by the focus after it has sent
     * 'transport-replace' which is supposed to move the conference to another
     * bridge. It means that the client has accepted new transport.
     *
     * {@inheritDoc}
     */
    @Override
    public XMPPError onTransportAccept(@NotNull JingleSession jingleSession, List<ContentPacketExtension> contents)
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
    public XMPPError onAddSource(@NotNull JingleSession jingleSession, List<ContentPacketExtension> contents)
    {
        Jid address = jingleSession.getAddress();
        Participant participant = findParticipantForJingleSession(jingleSession);
        if (participant == null)
        {
            String errorMsg = "no session for " + address;
            logger.warn(errorMsg);
            return XMPPError.from(XMPPError.Condition.item_not_found, errorMsg).build();
        }

        Object[] added;
        try
        {
            added = tryAddSourcesToParticipant(participant, contents);
        }
        catch (InvalidSSRCsException e)
        {
            logger.error("Error adding SSRCs from: " + address + ": " + e.getMessage());
            return XMPPError.from(XMPPError.Condition.bad_request, e.getMessage()).build();
        }

        MediaSourceMap sourcesToAdd = (MediaSourceMap) added[0];
        MediaSourceGroupMap sourceGroupsToAdd = (MediaSourceGroupMap) added[1];

        if (sourcesToAdd.isEmpty() && sourceGroupsToAdd.isEmpty())
        {
            logger.warn("Stop processing source-add, no new sources added: " + participant.getChatMember().getName());
            return null;
        }

        // Updates source groups on the bridge
        // We may miss the notification, but the state will be synced up
        // after conference has been relocated to the new bridge
        synchronized (bridges)
        {
            BridgeSession bridgeSession = findBridgeSession(participant);
            if (bridgeSession != null)
            {
                bridgeSession.colibriConference.updateSourcesInfo(
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy(),
                    participant.getColibriChannelsInfo());

                propagateNewSourcesToOcto(bridgeSession, sourcesToAdd, sourceGroupsToAdd);
            }
            else
            {
                logger.warn("No bridge for a participant: " + participant.getChatMember().getName());
                // TODO: how do we handle this? Re-invite?
            }
        }

        propagateNewSources(participant, sourcesToAdd, sourceGroupsToAdd);

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
    public XMPPError onRemoveSource(@NotNull JingleSession sourceJingleSession, List<ContentPacketExtension> contents)
    {
        MediaSourceMap sourcesToRemove = MediaSourceMap.getSourcesFromContent(contents);
        MediaSourceGroupMap sourceGroupsToRemove = MediaSourceGroupMap.getSourceGroupsForContents(contents);

        return removeSources(sourceJingleSession, sourcesToRemove, sourceGroupsToRemove, true, true);
    }

    /**
     * Updates the RTP description, transport and propagates sources and source
     * groups of a participant that sends the session-accept or transport-accept
     * Jingle IQs.
     */
    private XMPPError onSessionAcceptInternal(
            @NotNull JingleSession jingleSession, List<ContentPacketExtension> contents)
    {
        Participant participant = findParticipantForJingleSession(jingleSession);
        Jid participantJid = jingleSession.getAddress();

        if (participant == null)
        {
            String errorMsg = "No participant found for: " + participantJid;
            logger.warn(errorMsg);
            return XMPPError.from(XMPPError.Condition.item_not_found, errorMsg).build();
        }

        if (participant.getJingleSession() != null && participant.getJingleSession() != jingleSession)
        {
            //FIXME: we should reject it ?
            logger.error("Reassigning jingle session for participant: " + participantJid);
        }

        participant.setJingleSession(jingleSession);

        // Extract and store various session information in the Participant
        participant.setRTPDescription(contents);
        participant.addTransportFromJingle(contents);

        MediaSourceMap sourcesAdvertised = MediaSourceMap.getSourcesFromContent(contents);
        MediaSourceGroupMap sourceGroupsAdvertised = MediaSourceGroupMap.getSourceGroupsForContents(contents);
        if (sourcesAdvertised.isEmpty() && ConferenceConfig.config.injectSsrcForRecvOnlyEndpoints())
        {
            // We inject an SSRC in order to ensure that the participant has
            // at least one SSRC advertised. Otherwise, non-local bridges in the
            // conference will not be aware of the participant.
            SourcePacketExtension sourcePacketExtension = new SourcePacketExtension();
            long ssrc = RANDOM.nextInt() & 0xffff_ffffL;
            logger.info(participant + " did not advertise any SSRCs. Injecting " + ssrc);
            sourcePacketExtension.setSSRC(ssrc);
            sourcePacketExtension.setInjected(true);
            sourcesAdvertised.addSource(MediaType.AUDIO.toString(), sourcePacketExtension);
        }
        MediaSourceMap sourcesAdded;
        MediaSourceGroupMap sourceGroupsAdded;
        try
        {
            Object[] sourcesAndGroupsAdded
                = tryAddSourcesToParticipant(participant, sourcesAdvertised, sourceGroupsAdvertised);
            sourcesAdded = (MediaSourceMap) sourcesAndGroupsAdded[0];
            sourceGroupsAdded = (MediaSourceGroupMap) sourcesAndGroupsAdded[1];
        }
        catch (InvalidSSRCsException e)
        {
            logger.error("Error processing session accept from: " + participantJid +": " + e.getMessage());

            return XMPPError.from(XMPPError.Condition.bad_request, e.getMessage()).build();
        }

        logger.info(
                "Received session-accept from " + participant.getChatMember().getName()
                        + " with accepted sources:" + sourcesAdded);

        // Update channel info - we may miss update during conference restart,
        // but the state will be synced up after channels are allocated for this
        // participant on the new bridge
        synchronized (bridges)
        {
            BridgeSession participantBridge = findBridgeSession(participant);
            if (participantBridge != null)
            {
                participantBridge.updateColibriChannels(participant);
            }
            else
            {
                logger.warn("No bridge found for a participant: " + participant.getChatMember().getName());
                // TODO: how do we handle this? Re-invite?
            }

            // If we accepted any new sources from the participant, update
            // the state of all remote bridges.
            if ((!sourcesAdded.isEmpty() || !sourceGroupsAdded.isEmpty()) && participantBridge != null)
            {
                propagateNewSourcesToOcto(participantBridge, sourcesAdded, sourceGroupsAdded);
            }
        }

        // Loop over current participant and send 'source-add' notification
        propagateNewSources(participant, sourcesAdded.copyDeep(), sourceGroupsAdded.copy());

        // Notify the participant itself since it is now stable
        if (participant.hasSourcesToAdd())
        {
            jingle.sendAddSourceIQ(
                participant.getSourcesToAdd(),
                participant.getSourceGroupsToAdd(),
                jingleSession);

            participant.clearSourcesToAdd();
        }
        if (participant.hasSourcesToRemove())
        {
            jingle.sendRemoveSourceIQ(
                participant.getSourcesToRemove(),
                participant.getSourceGroupsToRemove(),
                jingleSession);

            participant.clearSourcesToRemove();
        }

        return null;
    }


    /**
     * Removes sources from the conference.
     *
     * @param sourceJingleSession source Jingle session from which sources are being removed.
     * @param sourcesToRemove the {@link MediaSourceMap} of sources to be removed from the conference.
     * @param updateChannels tells whether or not sources update request should be sent to the bridge.
     * @param sendSourceRemove Whether to send source-remove IQs to the remaining participants.
     */
    private XMPPError removeSources(
            JingleSession sourceJingleSession,
            MediaSourceMap sourcesToRemove,
            MediaSourceGroupMap sourceGroupsToRemove,
            boolean updateChannels,
            boolean sendSourceRemove)
    {
        Participant participant = findParticipantForJingleSession(sourceJingleSession);
        Jid participantJid = sourceJingleSession.getAddress();
        if (participant == null)
        {
            logger.warn("Remove-source: no session for " + participantJid);
            return null;
        }

        final MediaSourceMap conferenceSources = getAllSources();
        final MediaSourceGroupMap conferenceSourceGroups = getAllSourceGroups();

        SSRCValidator validator
                = new SSRCValidator(
                        participant.getEndpointId(),
                        conferenceSources,
                        conferenceSourceGroups,
                        ConferenceConfig.config.getMaxSsrcsPerUser(),
                        this.logger);

        Object[] removed;

        try
        {
            removed = validator.tryRemoveSourcesAndGroups(sourcesToRemove, sourceGroupsToRemove);
        }
        catch (InvalidSSRCsException e)
        {
            logger.error("Error removing SSRCs from: " + participantJid + ": " + e.getMessage());
            return XMPPError.from(XMPPError.Condition.bad_request, e.getMessage()).build();
        }

        // Only sources owned by this participant end up in "removed" set
        final MediaSourceMap removedSources = (MediaSourceMap) removed[0];
        final MediaSourceGroupMap removedGroups = (MediaSourceGroupMap) removed[1];

        if (removedSources.isEmpty() && removedGroups.isEmpty())
        {
            logger.warn("No sources or groups to be removed from: " + participantJid);
            return null;
        }

        participant.removeSources(removedSources);
        participant.removeSourceGroups(removedGroups);

        // We remove all ssrc params from SourcePacketExtension as we want
        // the client to simply remove all lines corresponding to given SSRC and
        // not care about parameter's values we send.
        // Some params might get out of sync for various reasons like for
        // example Chrome coming up with 'default' value for missing 'mslabel'
        // or when we'll be doing lip-sync stream merge
        SSRCSignaling.deleteSSRCParams(removedSources);

        // Updates source Groups on the bridge
        BridgeSession bridgeSession = findBridgeSession(participant);
        // We may hit null here during conference restart, but that's not
        // important since the bridge for this instance will not be used
        // anymore and state is synced up soon after channels are allocated
        if (updateChannels && bridgeSession != null)
        {
            bridgeSession.colibriConference.updateSourcesInfo(
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy(),
                    participant.getColibriChannelsInfo());
        }

        synchronized (bridges)
        {
            operationalBridges()
                .filter(bridge -> !bridge.equals(bridgeSession))
                .forEach(bridge -> bridge.removeSourcesFromOcto(removedSources, removedGroups));
        }

        logger.info("Removing sources from " + participantJid + ": " + removedSources);

        if (sendSourceRemove)
        {
            participants.stream()
                    .filter(otherParticipant -> otherParticipant != participant)
                    .forEach(
                            otherParticipant ->
                            {
                                if (otherParticipant.isSessionEstablished())
                                {
                                    jingle.sendRemoveSourceIQ(
                                            removedSources,
                                            removedGroups,
                                            otherParticipant.getJingleSession());
                                }
                                else
                                {
                                    logger.warn("Remove source: no jingle session for " + participantJid);
                                    otherParticipant.scheduleSourcesToRemove(removedSources);
                                    otherParticipant.scheduleSourceGroupsToRemove(removedGroups);
                                }
                            });
        }

        return null;
    }

    /**
     * Adds the sources and groups described by the given list of Jingle
     * {@link ContentPacketExtension} to the given participant.
     *
     * @param participant - The {@link Participant} instance to which sources and groups will be added.
     * @param contents - The list of Jingle 'content' packet extensions which describe media sources and groups.
     *
     * @return See returns description of
     *         {@link SSRCValidator#tryAddSourcesAndGroups(MediaSourceMap, MediaSourceGroupMap)}.
     * @throws InvalidSSRCsException See throws description of
     *         {@link SSRCValidator#tryAddSourcesAndGroups(MediaSourceMap, MediaSourceGroupMap)}.
     */
    private Object[] tryAddSourcesToParticipant(Participant participant, List<ContentPacketExtension> contents)
        throws InvalidSSRCsException
    {
        return tryAddSourcesToParticipant(
                participant,
                MediaSourceMap.getSourcesFromContent(contents),
                MediaSourceGroupMap.getSourceGroupsForContents(contents));
    }

    /**
     * Adds the given sources and groups to the given participant.
     *
     * @param participant - The {@link Participant} instance to which sources and groups will be added.
     * @param newSources - The new media sources to add.
     * @param newGroups - The new media group sources to add.
     *
     * @return See returns description of
     *         {@link SSRCValidator#tryAddSourcesAndGroups(MediaSourceMap, MediaSourceGroupMap)}.
     * @throws InvalidSSRCsException See throws description of
     *         {@link SSRCValidator#tryAddSourcesAndGroups(MediaSourceMap, MediaSourceGroupMap)}.
     */
    private Object[] tryAddSourcesToParticipant(
            Participant participant,
            MediaSourceMap newSources,
            MediaSourceGroupMap newGroups)
        throws InvalidSSRCsException
    {
        MediaSourceMap conferenceSources = getAllSources();
        MediaSourceGroupMap conferenceSourceGroups = getAllSourceGroups();

        SSRCValidator validator
            = new SSRCValidator(
                    participant.getEndpointId(),
                    conferenceSources,
                    conferenceSourceGroups,
                    ConferenceConfig.config.getMaxSsrcsPerUser(),
                    this.logger);

        // Claim the new sources by injecting owner tag into packet extensions,
        // so that the validator will be able to tell who owns which sources.
        participant.claimSources(newSources);

        Object[] added = validator.tryAddSourcesAndGroups(newSources, newGroups);

        participant.addSourcesAndGroups((MediaSourceMap) added[0], (MediaSourceGroupMap) added[1]);

        return added;
    }

    /**
     * Gathers the list of all sources that exist in the current conference state.
     *
     * @return <tt>MediaSourceMap</tt> of all sources of given media type that exist in the current conference state.
     */
    private MediaSourceMap getAllSources()
    {
        return getAllSources(Collections.emptyList(), false);
    }

    /**
     * Gathers the list of all sources that exist in the current conference state.
     *
     * @param except optional <tt>Participant</tt> instance whose sources will be excluded from the list.
     *
     * @return <tt>MediaSourceMap</tt> of all sources of given media type that exist
     * in the current conference state.
     */
    MediaSourceMap getAllSources(Participant except)
    {
        return getAllSources(Collections.singletonList(except), false);
    }

    /**
     * Gathers the list of all sources that exist in the current conference state.
     *
     * @param except optional <tt>Participant</tt> instance whose sources will be excluded from the list
     * @param skipParticipantsWithoutBridgeSession skip sources from participants without a  bridge session.
     *
     * @return <tt>MediaSourceMap</tt> of all sources of given media type that exist
     * in the current conference state.
     */
    private MediaSourceMap getAllSources(List<Participant> except, boolean skipParticipantsWithoutBridgeSession)
    {
        MediaSourceMap mediaSources = new MediaSourceMap();

        for (Participant participant : participants)
        {
            // We want to exclude this one
            if (except.contains(participant))
            {
                continue;
            }

            // If the return value is used to create a new octo participant then
            // we skip participants without a bridge session (which happens when
            // a bridge fails & participant are re-invited). The reason why we
            // do this is to avoid adding sources to the (newly created) octo
            // participant from soon to be re-invited (and hence soon to be local)
            // participants, causing a weird transition from octo participant to
            // local participant in the new bridge.
            if (skipParticipantsWithoutBridgeSession && participant.getBridgeSession() == null)
            {
                continue;
            }

            mediaSources.add(participant.getSourcesCopy());
        }

        return mediaSources;
    }

    /**
     * Gathers the list of all source groups that exist in the current conference state.
     *
     * @return the list of all source groups of given media type that exist in current conference state.
     */
    private MediaSourceGroupMap getAllSourceGroups()
    {
        return getAllSourceGroups(Collections.emptyList(), false);
    }

    /**
     * Gathers the list of all source groups that exist in the current conference
     * state.
     *
     * @param except optional <tt>Participant</tt> instance whose source groups will be excluded from the list.
     * @return the list of all source groups of given media type that exist in current conference state.
     */
    MediaSourceGroupMap getAllSourceGroups(Participant except)
    {
        return getAllSourceGroups(Collections.singletonList(except), false);
    }

    /**
     * Gathers the list of all source groups that exist in the current conference state.
     *
     * @param except a list of participants whose sources will not be included in the result.
     * @param skipParticipantsWithoutBridgeSession skip source groups from participants without a bridge session.
     * @return the list of all source groups of given media type that exist in current conference state.
     */
    private MediaSourceGroupMap getAllSourceGroups(
        List<Participant> except, boolean skipParticipantsWithoutBridgeSession)
    {
        MediaSourceGroupMap sourceGroups = new MediaSourceGroupMap();

        for (Participant participant : participants)
        {
            // Excluded this participant groups
            if (except.contains(participant))
            {
                continue;
            }

            // If the return value is used to create a new octo participant then
            // we skip participants without a bridge session (which happens when
            // a bridge fails & participant are re-invited). The reason why we
            // do this is to avoid adding sources to the (newly created) octo
            // participant from soon to be re-invited (and hence soon to be local)
            // participants, causing a weird transition from octo participant to
            // local participant in the new bridge.
            if (skipParticipantsWithoutBridgeSession && participant.getBridgeSession() == null)
            {
                continue;
            }

            sourceGroups.add(participant.getSourceGroupsCopy());
        }

        return sourceGroups;
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
            else if (!this.chatRoom.isMemberAllowedToUnmute(toBeMutedJid, mediaType))
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

        BridgeSession bridgeSession = findBridgeSession(participant);
        ColibriConferenceIQ participantChannels = participant.getColibriChannelsInfo();
        boolean succeeded
            = bridgeSession != null
                    && participantChannels != null
                    && bridgeSession.colibriConference.muteParticipant(participantChannels, doMute, mediaType);

        if (!succeeded)
        {
            logger.warn("Failed to mute, bridgeSession=" + bridgeSession + ", pc=" + participantChannels);
        }
        else
        {
            participant.setMuted(mediaType, doMute);
        }

        return succeeded ? MuteResult.SUCCESS : MuteResult.ERROR;
    }

    /**
     * {@inheritDoc}
     */
    public void muteAllNonModeratorParticipants(MediaType mediaType)
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
     * Mutes a participant.
     * @param participant the participant to mute. We mute only participants which are not moderators
     *                    and hasn't been already muted.
     * @param mediaType the media type for the operation.
     */
    public void muteParticipant(Participant participant, MediaType mediaType)
    {
        boolean isParticipantModerator = MemberRoleKt.hasModeratorRights(participant.getChatMember().getRole());
        if (isParticipantModerator || participant.isMuted(mediaType))
        {
            return;
        }

        MuteResult result = handleMuteRequest(null, participant.getMucJid(), true, mediaType);
        if (result == SUCCESS)
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
        if (chatRoom != null && checkMinParticipants() && bridges.isEmpty())
        {
            logger.info("New bridge available, will try to restart: " + bridgeJid);

            synchronized (participantLock)
            {
                reInviteParticipants(participants);
            }
        }
    }

    /**
     * Notifies this conference that one of its channel allocators failed to
     * allocate channels, and that the participants on a specific bridge need
     * to be re-invited.
     * @param bridgeJid the JID of the bridge on which participants need to be
     * re-invited.
     */
    void channelAllocationFailed(Jid bridgeJid)
    {
        onBridgeDown(bridgeJid);
    }

    /**
     * Notifies this conference that the bridge with a specific JID has failed.
     * @param bridgeJid the JID of the bridge which failed.
     */
    void onBridgeDown(Jid bridgeJid)
    {
        onMultipleBridgesDown(Collections.singleton(bridgeJid));
    }

    /**
     * Handles the case of some of the bridges in the conference becoming non-operational.
     * @param bridgeJids the JIDs of the bridges that are non-operational.
     */
    private void onMultipleBridgesDown(Set<Jid> bridgeJids)
    {
        List<Participant> participantsToReinvite = new ArrayList<>();

        synchronized (bridges)
        {
            bridgeJids.forEach(bridgeJid ->
            {
                BridgeSession bridgeSession = findBridgeSession(bridgeJid);
                if (bridgeSession != null)
                {
                    logger.error("One of our bridges failed: " + bridgeJid);

                    // Note: the Jingle sessions are still alive, we'll just
                    // (try to) move to a new bridge and send transport-replace.
                    participantsToReinvite.addAll(bridgeSession.terminateAll());

                    bridges.remove(bridgeSession);
                    listener.bridgeRemoved();
                }
            });

            setConferenceProperty(
                    ConferenceProperties.KEY_BRIDGE_COUNT,
                    Integer.toString(bridges.size()));

            updateOctoRelays();
        }

        if (!participantsToReinvite.isEmpty())
        {
            listener.participantsMoved(participantsToReinvite.size());
            reInviteParticipants(participantsToReinvite);
        }
    }

    /**
     * A callback called by {@link ParticipantChannelAllocator} when
     * establishing the Jingle session with its participant fails.
     * @param channelAllocator the channel allocator which failed.
     */
    void onInviteFailed(ParticipantChannelAllocator channelAllocator)
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
     * A method to be called by {@link AbstractChannelAllocator} just after it
     * has created a new Colibri conference on the JVB.
     */
    void onColibriConferenceAllocated()
    {
        // Remove "bridge not available" from Jicofo's presence
        // There is no check if it was ever added, but should be harmless
        ChatRoom chatRoom = this.chatRoom;
        if (chatRoom != null)
        {
            chatRoom.setPresenceExtension(new BridgeNotAvailablePacketExt(), true);
        }
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
            for (Participant participant : participants)
            {
                terminateParticipantBridgeSession(participant);
            }
            for (Participant participant : participants)
            {
                inviteParticipant(
                        participant,
                        true,
                        hasToStartMuted(participant, false));
            }
        }
    }

    private BridgeSession terminateParticipantBridgeSession(@NotNull Participant participant)
    {
        BridgeSession session = participant.getBridgeSession();
        participant.terminateBridgeSession();

        // Expire the OctoEndpoints for this participant on other
        // bridges.
        if (session != null)
        {
            MediaSourceMap removedSources = participant.getSourcesCopy();
            MediaSourceGroupMap removedGroups = participant.getSourceGroupsCopy();

            // Locking participantLock and the bridges is okay (or at
            // least used elsewhere).
            synchronized (bridges)
            {
                operationalBridges()
                    .filter(bridge -> !bridge.equals(session))
                    .forEach(
                        bridge -> bridge.removeSourcesFromOcto(
                            removedSources, removedGroups));
            }
        }

        return session;
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
    public Map<Bridge, Integer> getBridges()
    {
        Map<Bridge, Integer> bridges = new HashMap<>();
        synchronized (this.bridges)
        {
            for (BridgeSession bridgeSession : this.bridges)
            {
                // TODO: do we actually want the hasFailed check?
                if (!bridgeSession.hasFailed)
                {
                    bridges.put(bridgeSession.bridge, bridgeSession.participants.size());
                }
            }
        }
        return bridges;
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
            if (!(result instanceof NotProcessed) && jibriSipGateway != null)
            {
                result = jibriRecorder.handleJibriRequest(request);
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
        return String.format("JitsiMeetConferenceImpl[gid=%d, name=%s]", gid, getRoomName());
    }

    /**
     * The interface used to listen for conference events.
     */
    interface ConferenceListener
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
         * A bridge was removed from the conference because it was non-operational.
         */
        void bridgeRemoved();
    }

    /**
     * Represents a {@link Bridge} instance as used by this
     * {@link JitsiMeetConferenceImpl}.
     */
    class BridgeSession
    {
        /**
         * The {@link Bridge}.
         */
        final Bridge bridge;

        /**
         * The bridge session's id.
         *
         * At the time of this writing it's used to distinguish between current
         * and outdated ICE failed notifications coming from the client.
         *
         * It can often happen that during a bridge failure multiple clients
         * will send ICE failed messages because all of them will have
         * connectivity broken. Jicofo will mark the bridge as unhealthy when
         * processing the first notification and any following ones should be
         * discarded.
         */
        final String id = JitsiMeetConferenceImpl.this.gid + "_" +Integer.toHexString(RANDOM.nextInt(0x1_000000));

        /**
         * The list of participants in the conference which use this
         * {@link BridgeSession}.
         */
        private final List<Participant> participants = new LinkedList<>();

        /**
         * The {@link ColibriConference} instance used to communicate with
         * the jitsi-videobridge represented by this {@link BridgeSession}.
         */
        final ColibriConference colibriConference;

        /**
         * The single {@link OctoParticipant} for this bridge session, if any.
         */
        private OctoParticipant octoParticipant;

        /**
         * Indicates if the bridge used in this conference is faulty. We use
         * this flag to skip channel expiration step when the conference is being
         * disposed of.
         */
        public boolean hasFailed = false;

        /**
         * Initializes a new {@link BridgeSession} instance.
         * @param bridge the {@link Bridge} which the new
         * {@link BridgeSession} instance is to represent.
         */
        BridgeSession(Bridge bridge)
        {
            this.bridge = Objects.requireNonNull(bridge, "bridge");
            this.colibriConference = createNewColibriConference(bridge.getJid());
        }

        private void addParticipant(Participant participant)
        {
            participants.add(participant);
            bridge.endpointAdded();
        }

        /**
         * Disposes of this {@link BridgeSession}, attempting to expire the
         * COLIBRI conference.
         */
        private void dispose()
        {
            // We will not expire channels if the bridge is faulty or when our connection is down.
            if (!hasFailed && getClientXmppProvider().isRegistered())
            {
                colibriConference.expireConference();
            }
            else
            {
                // TODO: make sure this doesn't block waiting for a response
                colibriConference.dispose();
            }

            // TODO: should we terminate (or clear) #participants?
        }

        /**
         * Expires the COLIBRI channels (via
         * {@link Participant#terminateBridgeSession()}) for all
         * participants.
         * @return the list of participants which were removed from
         * {@link #participants} as a result of this call (does not include
         * the Octo participant).
         */
        private List<Participant> terminateAll()
        {
            List<Participant> terminatedParticipants = new LinkedList<>();
            // sync on what?
            for (Participant participant : new LinkedList<>(participants))
            {
                if (participant.terminateBridgeSession() != null)
                {
                    terminatedParticipants.add(participant);
                }
            }

            if (octoParticipant != null)
            {
                terminate(octoParticipant);
            }

            return terminatedParticipants;
        }

        /**
         * Expires the COLIBRI channels allocated for a specific {@link Participant} and removes the participant from
         * {@link #participants}.
         * @param participant the {@link Participant} for which to expire the COLIBRI channels.
         * @return {@code true} if the participant was a member of {@link #participants} and was removed as a result of
         * this call, and {@code false} otherwise.
         */
        public boolean terminate(AbstractParticipant participant)
        {
            boolean octo = participant == this.octoParticipant;
            boolean removed = octo || participants.remove(participant);

            ColibriConferenceIQ channelsInfo
                = participant != null
                    ? participant.getColibriChannelsInfo() : null;

            if (channelsInfo != null && !hasFailed)
            {
                String id
                    = (participant instanceof Participant)
                        ? ((Participant) participant).getMucJid().toString()
                        : "octo";
                logger.info("Expiring channels for: " + id + " on: " + bridge);
                colibriConference.expireChannels(channelsInfo);
            }

            if (octo)
            {
                if (participant != null)
                {
                    participant.setChannelAllocator(null);
                }
                this.octoParticipant = null;
            }

            return removed;
        }

        /**
         * Sends a COLIBRI message which updates the channels for a particular
         * {@link Participant} in this {@link BridgeSession}, setting the
         * participant's RTP description, sources, transport information, etc.
         */
        private void updateColibriChannels(Participant participant)
        {
            colibriConference.updateChannelsInfo(
                participant.getColibriChannelsInfo(),
                participant.getRtpDescriptionMap(),
                participant.getSourcesCopy(),
                participant.getSourceGroupsCopy(),
                participant.getBundleTransport(),
                participant.getEndpointId(),
                null);
        }

        /**
         * Sends a COLIBRI message which updates the channels for the Octo
         * participant in this {@link BridgeSession}.
         */
        private void updateColibriOctoChannels(OctoParticipant octoParticipant)
        {
            if (octoParticipant != null)
            {
                colibriConference.updateChannelsInfo(
                    octoParticipant.getColibriChannelsInfo(),
                    octoParticipant.getRtpDescriptionMap(),
                    octoParticipant.getSourcesCopy(),
                    octoParticipant.getSourceGroupsCopy(),
                    null,
                    null,
                    octoParticipant.getRelays());
            }
        }

        /**
         * Returns the Octo participant for this {@link BridgeSession}. If
         * a participant doesn't exist yet, it is created.
         * @return the {@link OctoParticipant} for this {@link BridgeSession}.
         */
        private OctoParticipant getOrCreateOctoParticipant()
        {
            if (octoParticipant != null)
            {
                return octoParticipant;
            }

            List<String> remoteRelays = getAllRelays(bridge.getRelayId());
            return getOrCreateOctoParticipant(new LinkedList<>(remoteRelays));
        }

        /**
         * Returns the Octo participant for this {@link BridgeSession}. If
         * a participant doesn't exist yet, it is created and initialized
         * with {@code relays} as the list of remote Octo relays.
         * @return the {@link OctoParticipant} for this {@link BridgeSession}.
         */
        private OctoParticipant getOrCreateOctoParticipant(List<String> relays)
        {
            if (octoParticipant == null)
            {
                octoParticipant = createOctoParticipant(relays);
            }
            return octoParticipant;
        }

        /**
         * Adds sources and source groups to this {@link BridgeSession}'s Octo
         * participant. If the Octo participant's session is already
         * established, then the sources are added and a colibri message is
         * sent to the bridge. Otherwise, they are scheduled to be added once
         * the session is established.
         * @param sources the sources to add.
         * @param sourceGroups the source groups to add.
         */
        private void addSourcesToOcto(MediaSourceMap sources, MediaSourceGroupMap sourceGroups)
        {
            if (!OctoConfig.config.getEnabled())
            {
                return;
            }

           OctoParticipant octoParticipant = getOrCreateOctoParticipant();

           synchronized (octoParticipant)
           {
               if (octoParticipant.isSessionEstablished())
               {
                   octoParticipant.addSourcesAndGroups(sources, sourceGroups);
                   updateColibriOctoChannels(octoParticipant);
               }
               else
               {
                   // The allocator will take care of updating these when the
                   // session is established.
                   octoParticipant.scheduleSourcesToAdd(sources);
                   octoParticipant.scheduleSourceGroupsToAdd(sourceGroups);
               }
           }
        }

        /**
         * Removes sources and source groups
         */
        private void removeSourcesFromOcto(MediaSourceMap sources, MediaSourceGroupMap sourceGroups)
        {
            OctoParticipant octoParticipant = this.octoParticipant;
            if (octoParticipant != null)
            {
                synchronized (octoParticipant)
                {
                    if (octoParticipant.isSessionEstablished())
                    {
                        octoParticipant.removeSources(sources);
                        octoParticipant.removeSourceGroups(sourceGroups);

                        updateColibriOctoChannels(octoParticipant);
                    }
                    else
                    {
                        octoParticipant.scheduleSourcesToRemove(sources);
                        octoParticipant.scheduleSourceGroupsToRemove(sourceGroups);
                    }
                }
            }
        }

        /**
         * Sets the list of Octo relays for this {@link BridgeSession}.
         * @param allRelays all relays in the conference (including the relay
         * of the bridge of this {@link BridgeSession}).
         */
        private void setRelays(List<String> allRelays)
        {
            List<String> remoteRelays = new LinkedList<>(allRelays);
            remoteRelays.remove(bridge.getRelayId());

            logger.info("Updating Octo relays for " + bridge);

            OctoParticipant octoParticipant = getOrCreateOctoParticipant(remoteRelays);
            octoParticipant.setRelays(remoteRelays);
            if (octoParticipant.isSessionEstablished())
            {
                updateColibriOctoChannels(octoParticipant);
            }
        }

        /**
         * Creates an {@link OctoParticipant} for this {@link BridgeSession}
         * and starts an {@link OctoChannelAllocator} to allocate channels for
         * it.
         * @param relays the list of Octo relay ids to set to the newly
         * allocated channels.
         * @return the instance which was created.
         */
        private OctoParticipant createOctoParticipant(List<String> relays)
        {
            logger.info("Creating an Octo participant for " + bridge);

            OctoParticipant octoParticipant = new OctoParticipant(JitsiMeetConferenceImpl.this, relays, logger);

            MediaSourceMap remoteSources = getAllSources(participants, true);
            MediaSourceGroupMap remoteGroups = getAllSourceGroups(participants, true);

            octoParticipant.addSourcesAndGroups(remoteSources, remoteGroups);

            OctoChannelAllocator channelAllocator
                = new OctoChannelAllocator(JitsiMeetConferenceImpl.this, this, octoParticipant, logger);
            octoParticipant.setChannelAllocator(channelAllocator);

            TaskPools.getIoPool().submit(channelAllocator);

            return octoParticipant;
        }

        @Override
        public String toString()
        {
            return String.format(
                    "BridgeSession[id=%s, bridge=%s]@%d",
                    id,
                    bridge,
                    hashCode());
        }
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

                    disposeConference();
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
            onBridgeDown(bridge.getJid());
        }

        @Override
        public void bridgeAdded(Bridge bridge)
        {
            onBridgeUp(bridge.getJid());
        }
    }

    public enum MuteResult
    {
        SUCCESS,
        NOT_ALLOWED,
        ERROR
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
            startMuted = new boolean[] { startAudioMuted, startVideoMuted };
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
    }
}
