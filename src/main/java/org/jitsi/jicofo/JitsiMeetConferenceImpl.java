/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.eventadmin.*;

import org.jitsi.util.Logger;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Class represents the focus of Jitsi Meet conference. Responsibilities:
 * a) Invites peers to the conference once they join multi user chat room
 *    (establishes Jingle session with peer).
 * b) Manages colibri channels per peer.
 * c) Advertisement of changes in peer's sources. When new peer joins the
 * 'add-source' notification is being sent, on leave: 'remove-source'
 * and a combination of add/remove on stream switch(desktop sharing).
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class JitsiMeetConferenceImpl
    implements JitsiMeetConference,
               RegistrationStateChangeListener,
               JingleRequestHandler,
               EventHandler
{
    /**
     * The classLogger instance used by this class.
     */
    private final static Logger classLogger
        = Logger.getLogger(JitsiMeetConferenceImpl.class);

    /**
     * An identifier of this {@link JitsiMeetConferenceImpl}.
     */
    private final String id;

    /**
     * Name of MUC room that is hosting Jitsi Meet conference.
     */
    private final EntityBareJid roomName;

    /**
     * {@link ConferenceListener} that will be notified
     * about conference events.
     */
    private final JitsiMeetConferenceImpl.ConferenceListener listener;

    /**
     * The logger for this instance. Uses the logging level either the one of
     * {@link #classLogger} or the one passed to the constructor, whichever
     * is higher.
     */
    private final Logger logger = Logger.getLogger(classLogger, null);

    /**
     * The instance of conference configuration.
     */
    private final JitsiMeetConfig config;

    /**
     * The instance of global configuration.
     */
    private final JitsiMeetGlobalConfig globalConfig;

    /**
     * XMPP protocol provider handler used by the focus.
     */
    private final ProtocolProviderHandler protocolProviderHandler;

    /**
     * The name of XMPP user used by the focus to login.
     */
    private final Resourcepart focusUserName;

    /**
     * Chat room operation set used to handle MUC stuff.
     */
    private OperationSetMultiUserChat chatOpSet;

    /** Jibri operation set to (un)register recorders. */
    private OperationSetJibri jibriOpSet;

    /**
     * Conference room chat instance.
     */
    private volatile ChatRoom2 chatRoom;

    /**
     * Operation set used to handle Jingle sessions with conference peers.
     */
    private OperationSetJingle jingle;

    /**
     * Colibri operation set used to manage videobridge channels allocations.
     */
    private OperationSetColibriConference colibri;

    /**
     * Jitsi Meet tool used for specific operations like adding presence
     * extensions.
     */
    private OperationSetJitsiMeetTools meetTools;

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
     * Information about Jitsi Meet conference services like videobridge,
     * SIP gateway, Jirecon.
     */
    private JitsiMeetServices services;

    /**
     * Chat room roles and presence handler.
     */
    private ChatRoomRoleAndPresence rolesAndPresence;

    /**
     * Indicates if this instance has been started (initialized).
     */
    private volatile boolean started;

    /**
     * Idle timestamp for this focus, -1 means active, otherwise
     * System.currentTimeMillis() is set when focus becomes idle.
     * Used to detect idle session and expire it if idle time limit is exceeded.
     */
    private long idleTimestamp = -1;

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
     * Bridge <tt>EventHandler</tt> registration.
     */
    private ServiceRegistration<EventHandler> eventHandlerRegistration;

    /**
     * <tt>ScheduledExecutorService</tt> service used to schedule delayed tasks
     * by this <tt>JitsiMeetConference</tt> instance.
     */
    private ScheduledExecutorService executor;

    /**
     * The list of {@link BridgeSession} currently in use by this conference.
     */
    private final List<BridgeSession> bridges = new LinkedList<>();

    /**
     * Creates new instance of {@link JitsiMeetConferenceImpl}.
     *
     * @param roomName name of MUC room that is hosting the conference.
     * @param focusUserName focus user login.
     * @param listener the listener that will be notified about this instance
     *        events.
     * @param config the conference configuration instance.
     * @param globalConfig an instance of the global config service.
     * @param logLevel (optional) the logging level to be used by this instance.
     *        See {@link #logger} for more details.
     */
    public JitsiMeetConferenceImpl(EntityBareJid            roomName,
                                   Resourcepart             focusUserName,
                                   ProtocolProviderHandler  protocolProviderHandler,
                                   ConferenceListener       listener,
                                   JitsiMeetConfig          config,
                                   JitsiMeetGlobalConfig    globalConfig,
                                   Level                    logLevel,
                                   String                   id)
    {
        this.protocolProviderHandler
            = Objects.requireNonNull(
                    protocolProviderHandler, "protocolProviderHandler");
        this.config = Objects.requireNonNull(config, "config");

        this.id = id;
        this.roomName = roomName;
        this.focusUserName = focusUserName;
        this.listener = listener;
        this.globalConfig = globalConfig;
        this.etherpadName = createSharedDocumentName();

        if (logLevel != null)
        {
            logger.setLevel(logLevel);
        }
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
    public synchronized void start()
        throws Exception
    {
        if (started)
        {
            return;
        }

        started = true;

        try
        {
            colibri
                = protocolProviderHandler.getOperationSet(
                        OperationSetColibriConference.class);
            jingle
                = protocolProviderHandler.getOperationSet(
                        OperationSetJingle.class);

            // Wraps OperationSetJingle in order to introduce
            // our nasty "lip-sync" hack
            if (Boolean.TRUE.equals(getConfig().isLipSyncEnabled()))
            {
                logger.info("Lip-sync enabled in " + getRoomName());
                jingle = new LipSyncHack(this, jingle);
            }

            chatOpSet
                = protocolProviderHandler.getOperationSet(
                        OperationSetMultiUserChat.class);
            meetTools
                = protocolProviderHandler.getOperationSet(
                        OperationSetJitsiMeetTools.class);
            jibriOpSet
                = protocolProviderHandler.getOperationSet(
                        OperationSetJibri.class);

            BundleContext osgiCtx = FocusBundleActivator.bundleContext;

            executor
                = ServiceUtils.getService(
                        osgiCtx, ScheduledExecutorService.class);

            services
                = ServiceUtils.getService(osgiCtx, JitsiMeetServices.class);

            // Set pre-configured SIP gateway
            //if (config.getPreConfiguredSipGateway() != null)
            //{
            //    services.setSipGateway(config.getPreConfiguredSipGateway());
            //}

            if (protocolProviderHandler.isRegistered())
            {
                joinTheRoom();
            }

            protocolProviderHandler.addRegistrationListener(this);

            idleTimestamp = System.currentTimeMillis();

            // Register for bridge events
            eventHandlerRegistration
                = EventUtil.registerEventHandler(
                        osgiCtx,
                        new String[]
                        {
                            BridgeEvent.BRIDGE_UP,
                            BridgeEvent.BRIDGE_DOWN
                        },
                        this);

            JibriDetector jibriDetector = services.getJibriDetector();
            if (jibriDetector != null && jibriOpSet != null)
            {
                jibriRecorder
                    = new JibriRecorder(
                            this, getXmppConnection(), executor, globalConfig);

                jibriOpSet.addJibri(jibriRecorder);
                jibriRecorder.init();
            }

            JibriDetector sipJibriDetector = services.getSipJibriDetector();
            if (sipJibriDetector != null && jibriOpSet != null)
            {
                jibriSipGateway
                    = new JibriSipGateway(
                            this,
                            getXmppConnection(),
                            FocusBundleActivator.getSharedThreadPool(),
                            globalConfig);

                jibriOpSet.addJibri(jibriSipGateway);
                jibriSipGateway.init();
            }
        }
        catch (Exception e)
        {
            stop();

            throw e;
        }
    }

    /**
     * Stops the conference, disposes colibri channels and releases all
     * resources used by the focus.
     */
    synchronized void stop()
    {
        if (!started)
        {
            return;
        }

        started = false;

        if (jibriSipGateway != null)
        {
            jibriSipGateway.dispose();
            jibriOpSet.removeJibri(jibriSipGateway);
            jibriSipGateway = null;
        }

        if (jibriRecorder != null)
        {
            jibriRecorder.dispose();
            jibriOpSet.removeJibri(jibriRecorder);
            jibriRecorder = null;
        }

        if (eventHandlerRegistration != null)
        {
            eventHandlerRegistration.unregister();
            eventHandlerRegistration = null;
        }

        protocolProviderHandler.removeRegistrationListener(this);

        disposeConference();

        leaveTheRoom();

        if (jingle != null)
        {
            jingle.terminateHandlersSessions(this);
        }

        if (listener != null)
        {
            listener.conferenceEnded(this);
        }
    }

    /**
     * Returns <tt>true</tt> if focus has joined the conference room.
     */
    public boolean isInTheRoom()
    {
        return chatRoom != null && chatRoom.isJoined();
    }

    /**
     * Joins the conference room.
     *
     * @throws Exception if we have failed to join the room for any reason
     */
    private void joinTheRoom()
        throws Exception
    {
        logger.info("Joining the room: " + roomName);

        chatRoom = (ChatRoom2) chatOpSet.findRoom(roomName.toString());
        chatRoom.setConference(this);

        rolesAndPresence = new ChatRoomRoleAndPresence(this, chatRoom);
        rolesAndPresence.init();

        chatRoom.join();

        // Advertise shared Etherpad document
        meetTools.sendPresenceExtension(
            chatRoom, EtherpadPacketExt.forDocumentName(etherpadName));

        // Trigger focus joined room event
        EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                    EventFactory.focusJoinedRoom(
                            roomName,
                            getId()));
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

        if (rolesAndPresence != null)
        {
            rolesAndPresence.dispose();
            rolesAndPresence = null;
        }

        chatRoom.leave();

        chatRoom = null;
    }

    /**
     * Method called by {@link #rolesAndPresence} when new member joins
     * the conference room.
     *
     * @param chatRoomMember the new member that has just joined the room.
     */
    protected void onMemberJoined(final XmppChatMember chatRoomMember)
    {
        synchronized (participantLock)
        {
            logger.info(
                    "Member "
                        + chatRoomMember.getContactAddress() + " joined.");

            if (!isFocusMember(chatRoomMember))
            {
                idleTimestamp = -1;
            }

            // Are we ready to start ?
            if (!checkAtLeastTwoParticipants())
            {
                return;
            }

            // Cancel single participant timeout when someone joins ?
            cancelSinglePeerTimeout();

            // Invite all not invited yet
            if (participants.size() == 0)
            {
                for (final ChatRoomMember member : chatRoom.getMembers())
                {
                    inviteChatMember(
                            (XmppChatMember) member,
                            member == chatRoomMember);
                }
            }
            // Only the one who has just joined
            else
            {
                inviteChatMember((XmppChatMember) chatRoomMember, true);
            }
        }
    }

    /**
     * Creates a new {@link ColibriConference} instance for use by this
     * {@link JitsiMeetConferenceImpl}.
     */
    private ColibriConference createNewColibriConference(Jid bridgeJid)
            throws XmppStringprepException
    {
        ColibriConference colibriConference = colibri.createNewConference();
        colibriConference.setGID(id);

        colibriConference.setConfig(config);

        Localpart roomName = chatRoom.getNameAsJid().getLocalpart();
        colibriConference.setName(roomName);
        colibriConference.setJitsiVideobridge(bridgeJid);

        return colibriConference;
    }

    /**
     * Invites new member to the conference which means new Jingle session
     * established and videobridge channels being allocated.
     *
     * @param chatRoomMember the chat member to be invited into the conference.
     * @param justJoined whether the chat room member should be invited as a
     * result of just having joined (as opposed to e.g. another participant
     * joining triggering the invite).
     */
    private void inviteChatMember(XmppChatMember chatRoomMember,
                                  boolean justJoined)
    {
        synchronized (participantLock)
        {
            if (isFocusMember(chatRoomMember))
            {
                return;
            }

            // Peer already connected ?
            if (findParticipantForChatMember(chatRoomMember) != null)
            {
                return;
            }

            final Participant participant
                = new Participant(
                        this,
                        chatRoomMember,
                        globalConfig.getMaxSourcesPerUser());

            participants.add(participant);
            inviteParticipant(
                    participant,
                    false,
                    hasToStartMuted(participant, justJoined));
        }
    }

    private BridgeSession inviteParticipant(
            Participant participant,
            boolean reInvite,
            boolean[] startMuted)
    {
        BridgeSession bridgeSession;
        synchronized (bridges)
        {
            if (findBridgeSession(participant) != null)
            {
                // This should never happen.
                logger.error("The participant already has a bridge?");
                return null;
            }

            // Select a bridge (a BridgeState) for the new participant.
            BridgeState bridgeState = null;
            Jid enforcedVideoBridge = config.getEnforcedVideobridge();
            BridgeSelector bridgeSelector = getServices().getBridgeSelector();


            if (enforcedVideoBridge != null)
            {
                bridgeState = bridgeSelector.getBridgeState(enforcedVideoBridge);
                if (bridgeState == null)
                {
                    logger.warn("The enforced bridge is not registered with "
                                    + "BridgeSelector, will try to use a "
                                    + "different one.");
                }
            }

            if (bridgeState == null)
            {
                bridgeState
                    = bridgeSelector.selectVideobridge(this, participant);
            }

            if (bridgeState == null)
            {
                // Can not find a bridge to use.
                logger.error(
                        "Can not invite participant -- no bridge available.");

                if (chatRoom != null
                    && !chatRoom.containsPresenceExtension(
                            BridgeNotAvailablePacketExt.ELEMENT_NAME,
                            BridgeNotAvailablePacketExt.NAMESPACE))
                {
                    meetTools.sendPresenceExtension(
                            chatRoom, new BridgeNotAvailablePacketExt());
                }
                return null;

            }

            bridgeSession = findBridgeSession(bridgeState);
            if (bridgeSession == null)
            {
                // The selected bridge is not yet used for this conference,
                // so initialize a new BridgeSession
                try
                {
                    bridgeSession = new BridgeSession(bridgeState);
                }
                catch (XmppStringprepException e)
                {
                    logger.error("Invalid room name", e);
                    return null;
                }

                bridges.add(bridgeSession);
                // TODO: if the number of bridges changes 1->2 or 2->1, then
                // we need to enable/disable relaying.
            }

            bridgeSession.participants.add(participant);
            logger.info("Added participant jid= " + participant.getMucJid()
                            + ", bridge=" + bridgeSession.bridgeState.getJid());
            logRegions();

            // Colibri channel allocation and jingle invitation take time, so
            // schedule them on a separate thread.
            ChannelAllocator channelAllocator
                = new ChannelAllocator(
                        this,
                        bridgeSession,
                        participant,
                        startMuted,
                        reInvite);

            participant.setChannelAllocator(channelAllocator);
            FocusBundleActivator.getSharedThreadPool().submit(channelAllocator);
        }

        return bridgeSession;
    }

    /**
     * Logs the regions of all bridges and participants of the conference.
     */
    private void logRegions()
    {
        StringBuilder sb = new StringBuilder("Region info, conference=" + getId() + ": [");
        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                sb.append("[").append(bridgeSession.bridgeState.getRegion());
                for (Participant p : bridgeSession.participants)
                {
                    sb.append(", ").append(p.getChatMember().getRegion());
                }
                sb.append("]");
            }
        }

        sb.append("]");
        logger.info(sb.toString());
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
     * jitsi-videobridge instance represented by a {@link BridgeState}, or
     * {@code null} if the {@link BridgeState} is not currently used in this
     * conference.
     * @param state the {@link BridgeSession} which represents a particular
     * jitsi-videobridge instance for which to return the {@link BridgeSession}.
     */
    private BridgeSession findBridgeSession(BridgeState state)
    {
        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                if (bridgeSession.bridgeState.equals(state))
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
     * {@code null} if the {@link BridgeState} is not currently used in this
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
                if (bridgeSession.bridgeState.getJid().equals(jid))
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
    private boolean[] hasToStartMuted(
            Participant participant,
            boolean justJoined)
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
     * Returns {@code true} if there are at least two non-focus participants in
     * the room.
     *
     * @return <tt>true</tt> if we have at least two non-focus participants.
     */
    private boolean checkAtLeastTwoParticipants()
    {
        // 2 + 1 focus
        if (chatRoom.getMembersCount() >= (2 + 1))
        {
            return true;
        }

        int realCount = 0;
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (!isFocusMember((XmppChatMember)member))
            {
                realCount++;
            }
        }

        return realCount >= 2;
    }

    /**
     * Check if given chat member is a focus.
     *
     * @param member the member to check.
     *
     * @return <tt>true</tt> if given {@link ChatRoomMember} is a focus
     *         participant.
     */
    boolean isFocusMember(XmppChatMember member)
    {
        return member.getName().equals(focusUserName.toString());
    }

    /**
     * Checks if given MUC jid belongs to the focus user.
     *
     * @param mucJid the full MUC address to check.
     *
     * @return <tt>true</tt> if given <tt>mucJid</tt> belongs to the focus
     *         participant or <tt>false</tt> otherwise.
     */
    @Override
    public boolean isFocusMember(Jid mucJid)
    {
        ChatRoom2 chatRoom = this.chatRoom;
        return mucJid != null
                && chatRoom != null
                && mucJid.equals(chatRoom.getLocalMucJid());
    }

    /**
     * Check if given member represent SIP gateway participant.

     * @param member the chat member to be checked.
     *
     * @return <tt>true</tt> if given <tt>member</tt> represents the SIP gateway
     */
    // FIXME remove once Jigasi is a "robot"
    boolean isSipGateway(ChatRoomMember member)
    {
        Participant participant = findParticipantForChatMember(member);

        return participant != null && participant.isSipGateway();
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
            logger.error("Unable to destroy conference MUC room: " + roomName);
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
        cancelSinglePeerTimeout();

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
        }

        // TODO: what about removing the participants and ending their jingle
        // session?
    }

    /**
     * Method called by {@link #rolesAndPresence} when one of the members has
     * been kicked out of the conference room.
     *
     * @param chatRoomMember kicked chat room member.
     */
    protected void onMemberKicked(ChatRoomMember chatRoomMember)
    {
        synchronized (participantLock)
        {
            logger.info(
                "Member " + chatRoomMember.getContactAddress() + " kicked !!!");

            onMemberLeft(chatRoomMember);
        }
    }

    /**
     * Method called by {@link #rolesAndPresence} when someone leave conference
     * chat room.
     *
     * @param chatRoomMember the member that has left the room.
     */
    protected void onMemberLeft(ChatRoomMember chatRoomMember)
    {
        synchronized (participantLock)
        {
            String contactAddress = chatRoomMember.getContactAddress();

            logger.info("Member " + contactAddress + " is leaving");

            Participant leftPeer = findParticipantForChatMember(chatRoomMember);
            if (leftPeer != null)
            {
                terminateParticipant(leftPeer, Reason.GONE, null);
            }
            else
            {
                logger.warn(
                        "Participant not found for " + contactAddress
                            + " terminated already or never started ?");
            }

            if (participants.size() == 1)
            {
                rescheduleSinglePeerTimeout();
            }
            else if (participants.size() == 0)
            {
                stop();
            }
        }
    }

    private void terminateParticipant(Participant    participant,
                                      Reason         reason,
                                      String         message)
    {
        synchronized (participantLock)
        {
            Jid contactAddress = participant.getMucJid();
            JingleSession peerJingleSession = participant.getJingleSession();
            if (peerJingleSession != null)
            {
                logger.info("Terminating: " + contactAddress);

                jingle.terminateSession(peerJingleSession, reason, message);

                removeSources(
                    peerJingleSession,
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy(),
                    false /* no JVB update - will expire */);

            }

            // Cancel any threads currently trying to invite the participant.
            participant.setChannelAllocator(null);
            BridgeSession bridgeSession = findBridgeSession(participant);
            if (bridgeSession != null)
            {
                bridgeSession.terminate(participant);
            }

            boolean removed = participants.remove(participant);
            logger.info(
                "Removed participant: " + removed + ", " + contactAddress);
        }
    }

    @Override
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        logger.info("Reg state changed: " + evt);

        if (RegistrationState.REGISTERED.equals(evt.getNewState()))
        {

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
        else if (RegistrationState.UNREGISTERED.equals(evt.getNewState()))
        {
            stop();
        }
    }

    private Participant findParticipantForJingleSession(
            JingleSession jingleSession)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember().getContactAddressJid().equals(
                    jingleSession.getAddress()))
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
            if (participant.getChatMember().getContactAddressJid()
                    .equals(roomJid))
            {
                return participant;
            }
        }
        return null;
    }

    @Override
    public ChatRoomMemberRole getRoleForMucJid(Jid mucJid)
    {
        if (chatRoom == null)
        {
            return null;
        }

        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (member.getContactAddress().equals(mucJid.toString()))
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
    public XMPPError onSessionAccept(
            JingleSession peerJingleSession,
            List<ContentPacketExtension> answer)
    {
        Participant participant
            = findParticipantForJingleSession(peerJingleSession);
        Jid peerAddress = peerJingleSession.getAddress();

        if (participant == null)
        {
            String errorMsg
                = "No participant found for: " + peerAddress;
            logger.error(errorMsg);
            return XMPPError.from(XMPPError.Condition.item_not_found,
                    errorMsg).build();
        }

        if (participant.getJingleSession() != null)
        {
            //FIXME: we should reject it ?
            logger.error(
                    "Reassigning jingle session for participant: "
                        + peerAddress);
        }

        // XXX We will be acting on the received session-accept bellow.
        // Unfortunately, we may have not received an acknowledgment of our
        // session-initiate yet and whatever we do bellow will be torn down when
        // the acknowledgement timeout occurs later on. Since we will have
        // acted on the session-accept by the time the acknowledgement timeout
        // occurs, we may as well ignore the timeout.
        peerJingleSession.setAccepted(true);

        participant.setJingleSession(peerJingleSession);

        // Extract and store various session information in the Participant
        participant.setRTPDescription(answer);
        participant.addTransportFromJingle(answer);

        try
        {
            participant.addSourcesAndGroupsFromContent(answer);
        }
        catch (InvalidSSRCsException e)
        {
            logger.error(
                "Error processing session accept from: "
                    + peerAddress +": " + e.getMessage());

            return XMPPError.from(
                XMPPError.Condition.bad_request, e.getMessage()).build();
        }

        MediaSourceMap peerSources = participant.getSourcesCopy();
        MediaSourceGroupMap peerGroupsMap = participant.getSourceGroupsCopy();

        logger.info("Received sources from " + peerAddress + " " + peerSources);

        // Update channel info - we may miss update during conference restart,
        // but the state will be synced up after channels are allocated for this
        // peer on the new bridge
        BridgeSession bridgeSession = findBridgeSession(participant);
        if (bridgeSession != null)
        {
            bridgeSession.colibriConference.updateChannelsInfo(
                    participant.getColibriChannelsInfo(),
                    participant.getRtpDescriptionMap(),
                    peerSources,
                    peerGroupsMap,
                    participant.getBundleTransport(),
                    participant.getTransportMap(),
                    participant.getEndpointId());
        }
        else
        {
            logger.warn("No bridge found for a participant: "+participant);
            // TODO: how do we handle this? Re-invite?
        }

        // Loop over current participant and send 'source-add' notification
        propagateNewSources(participant, peerSources, peerGroupsMap);

        // Notify the peer itself since it is now stable
        if (participant.hasSourcesToAdd())
        {
            jingle.sendAddSourceIQ(
                    participant.getSourcesToAdd(),
                    participant.getSourceGroupsToAdd(),
                    peerJingleSession);

            participant.clearSourcesToAdd();
        }
        if (participant.hasSourcesToRemove())
        {
            jingle.sendRemoveSourceIQ(
                    participant.getSourcesToRemove(),
                    participant.getSourceGroupsToRemove(),
                    peerJingleSession);

            participant.clearSourcesToRemove();
        }

        return null;
    }

    /**
     * Advertises new sources across all conference participants by using
     * 'source-add' Jingle notification.
     *
     * @param sourceOwner the <tt>Participant</tt> who owns the sources.
     * @param sourcesToAdd the <tt>MediaSourceMap</tt> with the sources to advertise.
     * @param sourceGroupsToAdd the <tt>MediaSourceGroupMap</tt> with source groups
     *        to advertise.
     */
    private void propagateNewSources(Participant          sourceOwner,
                                     MediaSourceMap       sourcesToAdd,
                                     MediaSourceGroupMap  sourceGroupsToAdd)
    {
        for (Participant peerToNotify : participants)
        {
            // Skip origin
            if (sourceOwner == peerToNotify)
            {
                continue;
            }

            JingleSession jingleSessionToNotify
                = peerToNotify.getJingleSession();
            if (jingleSessionToNotify == null)
            {
                logger.warn(
                        "No jingle session yet for "
                            + peerToNotify.getChatMember().getContactAddress());

                peerToNotify.scheduleSourcesToAdd(sourcesToAdd);

                peerToNotify.scheduleSourceGroupsToAdd(sourceGroupsToAdd);

                continue;
            }

            jingle.sendAddSourceIQ(
                    sourcesToAdd, sourceGroupsToAdd, jingleSessionToNotify);
        }
    }


    /**
     * Callback called when we receive 'transport-info' from conference
     * participant. The info is forwarded to the videobridge at this point.
     *
     * {@inheritDoc}
     */
    @Override
    public void onTransportInfo(JingleSession session,
                                List<ContentPacketExtension> contentList)
    {
        Participant participant = findParticipantForJingleSession(session);
        if (participant == null)
        {
            logger.error("Failed to process transport-info," +
                             " no session for: " + session.getAddress());
            return;
        }

        // Participant will figure out bundle or non-bundle transport
        // based on its hasBundleSupport() value
        participant.addTransportFromJingle(contentList);

        BridgeSession bridgeSession = findBridgeSession(participant);
        // We can hit null here during conference restart, but the state will be
        // synced up later when the client sends 'transport-accept'
        if (bridgeSession == null)
        {
            logger.warn("Skipped transport-info processing - no conference");
            return;
        }

        if (participant.hasBundleSupport())
        {
            bridgeSession.colibriConference.updateBundleTransportInfo(
                    participant.getBundleTransport(),
                    participant.getEndpointId());
        }
        else
        {
            bridgeSession.colibriConference.updateTransportInfo(
                    participant.getTransportMap(),
                    participant.getColibriChannelsInfo());
        }
    }

    /**
     * 'transport-accept' message is received by the focus after it has sent
     * 'transport-replace' which is supposed to move the conference to another
     * bridge. It means that the client has accepted new transport.
     *
     * {@inheritDoc}
     */
    @Override
    public XMPPError onTransportAccept(JingleSession              jingleSession,
                                  List<ContentPacketExtension>    contents)
    {
        jingleSession.setAccepted(true);

        logger.info("Got transport-accept from: " + jingleSession.getAddress());

        // We basically do the same processing as with transport-info by just
        // forwarding transport/rtp information to the bridge
        onTransportInfo(jingleSession, contents);

        return null;
    }

    /**
     * Message sent by the client when for any reason it's unable to handle
     * 'transport-replace' message.
     *
     * {@inheritDoc}
     */
    @Override
    public void onTransportReject(JingleSession jingleSession, JingleIQ reply)
    {
        Participant p = findParticipantForJingleSession(jingleSession);
        if (p == null)
        {
            logger.error(
                    "No participant for " + Objects.toString(jingleSession));
            return;
        }

        // We could expire channels immediately here, but we're leaving them to
        // auto expire on the bridge or we're going to do that when user leaves
        // the MUC anyway
        logger.error(
                "Participant has rejected our transport offer: " + p.getMucJid()
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
    public XMPPError onAddSource(JingleSession jingleSession,
                                 List<ContentPacketExtension> contents)
    {
        Jid address = jingleSession.getAddress();
        Participant participant
            = findParticipantForJingleSession(jingleSession);
        if (participant == null)
        {
            String errorMsg = "Add-source: no peer state for " + address;
            logger.error(errorMsg);
            return XMPPError.from(
                    XMPPError.Condition.item_not_found, errorMsg).build();
        }

        Object[] added;
        try
        {
            added = participant.addSourcesAndGroupsFromContent(contents);
        }
        catch (InvalidSSRCsException e)
        {
            logger.error(
                "Error adding SSRCs from: " + address + ": " + e.getMessage());
            return XMPPError.from(
                XMPPError.Condition.bad_request, e.getMessage()).build();
        }

        MediaSourceMap sourcesToAdd = (MediaSourceMap) added[0];
        MediaSourceGroupMap sourceGroupsToAdd = (MediaSourceGroupMap) added[1];

        if (sourcesToAdd.isEmpty() && sourceGroupsToAdd.isEmpty())
        {
            logger.warn("Not sending source-add, notification would be empty");
            return null;
        }

        // Updates source groups on the bridge
        // We may miss the notification, but the state will be synced up
        // after conference has been relocated to the new bridge
        BridgeSession bridgeSession = findBridgeSession(participant);
        if (bridgeSession != null)
        {
            bridgeSession.colibriConference.updateSourcesInfo(
                    participant.getSourcesCopy(),
                    participant.getSourceGroupsCopy(),
                    participant.getColibriChannelsInfo());
        }
        else
        {
            logger.warn("No bridge for a participant.");
            // TODO: how do we handle this? Re-invite?
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
    public XMPPError onRemoveSource(JingleSession sourceJingleSession,
                               List<ContentPacketExtension> contents)
    {
        MediaSourceMap sourcesToRemove
            = MediaSourceMap.getSourcesFromContent(contents);

        MediaSourceGroupMap sourceGroupsToRemove
            = MediaSourceGroupMap.getSourceGroupsForContents(contents);

        removeSources(
                sourceJingleSession, sourcesToRemove, sourceGroupsToRemove, true);

        return null;
    }

    /**
     * Removes sources from the conference and notifies other participants.
     *
     * @param sourceJingleSession source Jingle session from which sources are
     *                            being removed.
     * @param sourcesToRemove the {@link MediaSourceMap} of sources to be removed from
     *                      the conference.
     * @param updateChannels tells whether or not sources update request should be
     *                       sent to the bridge.
     */
    private void removeSources(JingleSession        sourceJingleSession,
                               MediaSourceMap       sourcesToRemove,
                               MediaSourceGroupMap  sourceGroupsToRemove,
                               boolean              updateChannels)
    {
        Participant participant
            = findParticipantForJingleSession(sourceJingleSession);
        Jid participantJid = sourceJingleSession.getAddress();
        if (participant == null)
        {
            logger.error("Remove-source: no session for " + participantJid);
            return;
        }

        // Only sources owned by this peer end up in "removed" set
        MediaSourceMap removedSources = participant.removeSources(sourcesToRemove);

        MediaSourceGroupMap removedGroups
            = participant.removeSourceGroups(sourceGroupsToRemove);

        if (removedSources.isEmpty() && removedGroups.isEmpty())
        {
            logger.warn(
                    "No sources or groups to be removed from: "+ participantJid);
            return;
        }

        // This prevents from removing sources which do not belong to this peer
        sourcesToRemove = removedSources;
        sourceGroupsToRemove = removedGroups;

        // We remove all ssrc params from SourcePacketExtension as we want
        // the client to simply remove all lines corresponding to given SSRC and
        // not care about parameter's values we send.
        // Some params might get out of sync for various reasons like for
        // example Chrome coming up with 'default' value for missing 'mslabel'
        // or when we'll be doing lip-sync stream merge
        SSRCSignaling.deleteSSRCParams(sourcesToRemove);

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

        logger.info("Removing " + participantJid + " sources " + sourcesToRemove);

        for (Participant otherParticipant : participants)
        {
            if (otherParticipant == participant)
            {
                continue;
            }

            JingleSession jingleSessionToNotify
                = otherParticipant.getJingleSession();
            if (jingleSessionToNotify == null)
            {
                logger.warn(
                    "Remove source: no jingle session for " + participantJid);

                otherParticipant.scheduleSourcesToRemove(sourcesToRemove);

                otherParticipant.scheduleSourceGroupsToRemove(sourceGroupsToRemove);

                continue;
            }

            jingle.sendRemoveSourceIQ(
                    sourcesToRemove, sourceGroupsToRemove, jingleSessionToNotify);
        }
    }

    /**
     * Gathers the list of all sources that exist in the current conference state.
     *
     * @param except optional <tt>Participant</tt> instance whose sources will be
     *               excluded from the list
     *
     * @return <tt>MediaSourceMap</tt> of all sources of given media type that exist
     * in the current conference state.
     */
    MediaSourceMap getAllSources(Participant except)
    {
        MediaSourceMap mediaSources = new MediaSourceMap();

        for (Participant peer : participants)
        {
            // We want to exclude this one
            if (peer == except)
            {
                continue;
            }

            mediaSources.add(peer.getSourcesCopy());
        }

        return mediaSources;
    }

    /**
     * Gathers the list of all source groups that exist in the current conference
     * state.
     *
     * @param except optional <tt>Participant</tt> instance whose source groups
     *               will be excluded from the list
     *
     * @return the list of all source groups of given media type that exist in
     *         current conference state.
     */
    MediaSourceGroupMap getAllSourceGroups(Participant except)
    {
        MediaSourceGroupMap sourceGroups = new MediaSourceGroupMap();

        for (Participant peer : participants)
        {
            // Excluded this participant groups
            if (peer == except)
            {
                continue;
            }

            sourceGroups.add(peer.getSourceGroupsCopy());
        }

        return sourceGroups;
    }

    /**
     * Returns global config instance.
     *
     * @return instance of <tt>JitsiMeetGlobalConfig</tt> used by this
     * conference.
     */
    JitsiMeetGlobalConfig getGlobalConfig()
    {
        return globalConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EntityBareJid getRoomName()
    {
        return roomName;
    }

    /**
     * @return {@link XmppConnection} instance for this conference.
     */
    XmppConnection getXmppConnection()
    {
        return protocolProviderHandler.getXmppConnection();
    }

    /**
     * Returns XMPP protocol provider of the focus account.
     */
    public ProtocolProviderService getXmppProvider()
    {
        return protocolProviderHandler.getProtocolProvider();
    }

    public XmppChatMember findMember(Jid from)
    {
        return chatRoom == null ? null : chatRoom.findChatMember(from);
    }

    /**
     * Returns {@link System#currentTimeMillis()} timestamp indicating the time
     * when this conference has become idle(we can measure how long is it).
     * -1 is returned if this conference is considered active.
     *
     */
    public long getIdleTimestamp()
    {
        return idleTimestamp;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EntityFullJid getFocusJid()
    {
        return JidCreate.fullFrom(roomName, focusUserName);
    }

    /**
     * Returns <tt>OperationSetJingle</tt> for the XMPP connection used in this
     * <tt>JitsiMeetConference</tt> instance.
     */
    public OperationSetJingle getJingle()
    {
        return jingle;
    }

    /**
     * Returns {@link JitsiMeetServices} instance used in this conference.
     */
    public JitsiMeetServices getServices()
    {
        return services;
    }

    /**
     * Handles mute request sent from participants.
     * @param fromJid MUC jid of the participant that requested mute status
     *                change.
     * @param toBeMutedJid MUC jid of the participant whose mute status will be
     *                     changed(eventually).
     * @param doMute the new audio mute status to set.
     * @return <tt>true</tt> if status has been set successfully.
     */
    boolean handleMuteRequest(Jid fromJid,
                              Jid toBeMutedJid,
                              boolean doMute)
    {
        Participant principal = findParticipantForRoomJid(fromJid);
        if (principal == null)
        {
            logger.error(
                "Failed to perform mute operation - " + fromJid
                    +" not exists in the conference.");
            return false;
        }
        // Only moderators can mute others
        if (!fromJid.equals(toBeMutedJid)
            && ChatRoomMemberRole.MODERATOR.compareTo(
                principal.getChatMember().getRole()) < 0)
        {
            logger.error(
                "Permission denied for mute operation from " + fromJid);
            return false;
        }

        Participant participant = findParticipantForRoomJid(toBeMutedJid);
        if (participant == null)
        {
            logger.error("Participant for jid: " + toBeMutedJid + " not found");
            return false;
        }

        // do not allow unmuting other participants even for the moderator
        if (!doMute && !fromJid.equals(toBeMutedJid))
        {
            logger.warn("Blocking an unmute request (jid not the same).");
            return false;
        }

        logger.info(
            "Will " + (doMute ? "mute" : "unmute")
                + " " + toBeMutedJid + " on behalf of " + fromJid);

        BridgeSession bridgeSession = findBridgeSession(participant);
        boolean succeeded
            = bridgeSession != null
                    && bridgeSession.colibriConference.muteParticipant(
                            participant.getColibriChannelsInfo(), doMute);

        if (succeeded)
        {
            participant.setMuted(doMute);
        }

        return succeeded;
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
     * Focus instance ID
     */
    public String getId()
    {
        return id;
    }

    @Override
    public void handleEvent(Event event)
    {
        if (!(event instanceof BridgeEvent))
        {
            logger.error("Unexpected event type: " + event);
            return;
        }

        BridgeEvent bridgeEvent = (BridgeEvent) event;
        Jid bridgeJid = bridgeEvent.getBridgeJid();
        switch (event.getTopic())
        {
        case BridgeEvent.BRIDGE_DOWN:
            onBridgeDown(bridgeJid);
            break;
        case BridgeEvent.BRIDGE_UP:
            onBridgeUp(bridgeJid);
            break;
        }
    }

    private void onBridgeUp(Jid bridgeJid)
    {
        // Check if we're not shutting down
        if (!started)
        {
            return;
        }

        //TODO: if one of our bridges failed, we should have invited its
        // participants to another one. Here we should re-invite everyone if
        // the conference is not running (e.g. there was a single bridge and
        // it failed, then in was brought up).
        if (chatRoom != null && checkAtLeastTwoParticipants()
                && bridges.isEmpty())
        {
            logger.info("New bridge available: " + bridgeJid
                        + " will try to restart: " + getRoomName());

            // Trigger restart
            restartConference();
        }
    }

    /**
     * Handles on bridge down event by shutting down the conference if it's the
     * one we're using here.
     */
    void onBridgeDown(Jid bridgeJid)
    {
        synchronized (bridges)
        {
            BridgeSession bridgeSession = findBridgeSession(bridgeJid);
            if (bridgeSession != null)
            {
                logger.error("One of our bridges failed: " + bridgeJid);

                // Note: the Jingle sessions are still alive, we'll just
                // (try to) move to a new bridge and send transport-replace.
                List<Participant> participantsToReinvite
                    = bridgeSession.terminateAll();

                bridges.remove(bridgeSession);

                for (Participant participant : participantsToReinvite)
                {
                    // Cancel the thread early.
                    participant.setChannelAllocator(null);
                    inviteParticipant(
                            participant,
                            true,
                            hasToStartMuted(participant, false));
                }
            }
        }
    }

    private void restartConference()
    {
        logger.warn("Restarting the conference for room: " + getRoomName());

        disposeConference();

        synchronized (participantLock)
        {
            for (Participant participant : participants)
            {
                // Cancel all threads early.
                participant.setChannelAllocator(null);
            }
            // Invite all not invited yet
            for (Participant participant : participants)
            {
                inviteParticipant(
                        participant,
                        true,
                        hasToStartMuted(participant, false));
            }
        }
    }

    /**
     * Method called by {@link ChannelAllocator} when it fails to allocate
     * channels with {@link OperationFailedException}. We need to make some
     * decisions here.
     *
     * @param channelAllocator instance of <tt>ChannelAllocator</tt> which is
     * reporting the error.
     * @param exc <tt>OperationFailedException</tt> which provides details about
     * the reason for channel allocation failure.
     */
    void onChannelAllocationFailed(
            ChannelAllocator channelAllocator,
            OperationFailedException exc)
    {
        // We're gonna handle this, no more work for this ChannelAllocator.
        channelAllocator.cancel();

        BridgeSession bridgeSession = channelAllocator.getBridgeSession();
        Participant participant = channelAllocator.getParticipant();
        bridgeSession.terminate(participant);

        // Retry once.
        // TODO: be smarter about re-trying
        boolean retry = !channelAllocator.isReInvite();

        if (retry)
        {
            inviteParticipant(participant,
                              true,
                              channelAllocator.getStartMuted());
        }
        else
        {
            onBridgeDown(bridgeSession.bridgeState.getJid());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChatRoom2 getChatRoom()
    {
        return chatRoom;
    }

    /**
     * Returns config for this conference.
     * @return <tt>JitsiMeetConfig</tt> instance used in this conference.
     */
    public JitsiMeetConfig getConfig()
    {
        return config;
    }

    /**
     * Returns the <tt>Logger</tt> used by this instance.
     */
    public Logger getLogger()
    {
        return logger;
    }

    /**
     * Methods called by {@link ChannelAllocator} just after it has created new
     * Colibri conference on the JVB.
     *  @param colibriConference <tt>ColibriConference</tt> instance which just
     * has been allocated on the bridge
     * @param videobridgeJid the JID of the JVB where given
     */
    public void onColibriConferenceAllocated(
            ColibriConference    colibriConference,
            Jid videobridgeJid)
    {
        // TODO: do we need this event?
        EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                    EventFactory.conferenceRoom(
                            colibriConference.getConferenceId(),
                            roomName,
                            getId(),
                            videobridgeJid));
        }

        // Remove "bridge not available" from Jicofo's presence
        // There is no check if it was ever added, but should be harmless
        ChatRoom chatRoom = this.chatRoom;
        if (meetTools != null && chatRoom != null)
        {
            meetTools.removePresenceExtension(
                    chatRoom, new BridgeNotAvailablePacketExt());
        }
    }

    /**
     * Sets the value of the <tt>startMuted</tt> property of this instance.
     *
     * @param startMuted the new value to set on this instance. The specified
     * array is copied.
     */
    @Override
    public void setStartMuted(boolean[] startMuted)
    {
        this.startMuted = startMuted;
    }

    /**
     * Creates the shared document name by either using the conference room name
     * or a random string, depending on configuration.
     *
     * @return the shared document name.
     */
    private String createSharedDocumentName()
    {
        if (config.useRoomAsSharedDocName())
        {
            return roomName.getLocalpart().toString();
        }
        else
        {
            return UUID.randomUUID().toString().replaceAll("-", "");
        }
    }

    /**
     * (Re)schedules {@link SinglePersonTimeout}.
     */
    private void rescheduleSinglePeerTimeout()
    {
        if (executor != null)
        {
            cancelSinglePeerTimeout();

            long timeout = globalConfig.getSingleParticipantTimeout();

            singleParticipantTout
                = executor.schedule(
                        new SinglePersonTimeout(),
                        timeout, TimeUnit.MILLISECONDS);

            logger.debug(
                    "Scheduled single person timeout for " + getRoomName());
        }
    }

    /**
     * Cancels {@link SinglePersonTimeout}.
     */
    private void cancelSinglePeerTimeout()
    {
        if (executor != null && singleParticipantTout != null)
        {
            // This log is printed also when it's executed by the timeout thread
            // itself
            logger.debug(
                "Cancelling single person timeout in room: " + getRoomName());

            singleParticipantTout.cancel(false);
            singleParticipantTout = null;
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
                    logger.info(
                            "Timing out single participant: " + p.getMucJid());

                    terminateParticipant(
                            p, Reason.EXPIRED, "Idle session timeout");

                    disposeConference();
                }
                else
                {
                    logger.error(
                        "Should never execute if more than 1 participant? "
                            + getRoomName());
                }
                singleParticipantTout = null;
            }
        }
    }

    /**
     * Returns the COLIBRI conference ID of one of the bridges used by this
     * conference.
     * TODO: remove this (it is only used for testing)
     */
    public String getJvbConferenceId()
    {
        for (BridgeSession bridgeSession : bridges)
        {
            if (bridgeSession != null)
            {
                return bridgeSession.colibriConference.getConferenceId();
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BridgeState> getBridges()
    {
        List<BridgeState> bridgeStates = new LinkedList<>();
        synchronized (bridges)
        {
            for (BridgeSession bridgeSession : bridges)
            {
                // TODO: do we actually want the hasFailed check?
                if (!bridgeSession.hasFailed)
                {
                    bridgeStates.add(bridgeSession.bridgeState);
                }
            }
        }
        return  bridgeStates;
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
    }

    /**
     * Represents a {@link BridgeState} instance as used by this
     * {@link JitsiMeetConferenceImpl}.
     */
    class BridgeSession
    {
        /**
         * The {@link BridgeState}.
         */
        BridgeState bridgeState;

        /**
         * The list of participants in the conference which use this
         * {@link BridgeSession}.
         */
        List<Participant> participants = new LinkedList<>();

        /**
         * The {@link ColibriConference} instance used to communicate with
         * the jitsi-videobridge represented by this {@link BridgeSession}.
         */
        final ColibriConference colibriConference;

        /**
         * Indicates if the bridge used in this conference is faulty. We use
         * this flag to skip channel expiration step when the conference is being
         * disposed of.
         */
        public boolean hasFailed = false;

        /**
         * Initializes a new {@link BridgeSession} instance.
         * @param bridgeState the {@link BridgeState} which the new
         * {@link BridgeSession} instance is to represent.
         */
        BridgeSession(BridgeState bridgeState)
                throws XmppStringprepException
        {
            this.bridgeState = bridgeState;
            this.colibriConference
                = createNewColibriConference(bridgeState.getJid());
        }

        /**
         * Disposes of this {@link BridgeSession}, attempting to expire the
         * COLIBRI conference.
         */
        private void dispose()
        {
            // We will not expire channels if the bridge is faulty or
            // when our connection is down
            if (!hasFailed && protocolProviderHandler.isRegistered())
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
         * Expires the COLIBRI channels (via {@link #terminate(Participant)})
         * for all participants.
         * @return the list of participants which were removed from
         * {@link #participants} as a result of this call.
         */
        private List<Participant> terminateAll()
        {
            List<Participant> terminatedParticipants = new LinkedList<>();
            // sync on what?
            for (Participant participant : new LinkedList<>(participants))
            {
                if (terminate(participant))
                {
                    terminatedParticipants.add(participant);
                }
            }

            return terminatedParticipants;
        }

        /**
         * Expires the COLIBRI channels allocated for a specific
         * {@link Participant} and removes the participant from
         * {@link #participants}.
         * @param participant the {@link Participant} for which to expire the
         * COLIBRI channels.
         * @return {@code true} if the participant was a member of
         * {@link #participants} and was removed as a result of this call, and
         * {@code false} otherwise.
         */
        public boolean terminate(Participant participant)
        {
            //TODO synchronize?
            // TODO: make sure this does not block waiting for a response
            boolean removed = participants.remove(participant);
            if (removed)
            {
                logRegions();
            }

            ColibriConferenceIQ channelsInfo
                = participant.getColibriChannelsInfo();

            if (channelsInfo != null && !hasFailed)
            {
                logger.info("Expiring channels for: " + participant.getMucJid());
                colibriConference.expireChannels(channelsInfo);

                // TODO: what do we do when the last participant is removed?
            }

            return removed;
        }
    }
}
