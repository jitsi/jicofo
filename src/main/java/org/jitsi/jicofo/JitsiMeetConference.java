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
import net.java.sip.communicator.util.Logger;

import org.jitsi.assertions.*;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.log.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
import org.jitsi.eventadmin.*;

import org.osgi.framework.*;

import java.text.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Class represents the focus of Jitsi Meet conference. Responsibilities:
 * a) Invites peers to the conference once they join multi user chat room
 *    (establishes Jingle session with peer).
 * b) Manages colibri channels per peer.
 * c) Advertisement of changes in peer's SSRCs. When new peer joins the
 * 'add-source' notification is being sent, on leave: 'remove-source'
 * and a combination of add/remove on stream switch(desktop sharing).
 *
 * @author Pawel Domas
 */
public class JitsiMeetConference
    implements RegistrationStateChangeListener,
               JingleRequestHandler,
               EventHandler
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(JitsiMeetConference.class);

    /**
     * Format used to print the date into the focus identifier string.
     * Data contained in the id should never be used for business logic.
     */
    private final static SimpleDateFormat ID_DATE_FORMAT
        = new SimpleDateFormat("yyy-MM-dd_HH:mm:ss");

    /**
     * Conference focus instance identifier. For now consists of current date
     * and the {@link #hashCode()}. Included date should not be used for any
     * calculations/app logic - it's just to have it more meaningful than random
     * numbers.
     *
     * FIXME: It would make sense to retrieve it from {@link ReservationSystem}
     *        if available.
     */
    private final String id;

    /**
     * Name of MUC room that is hosting Jitsi Meet conference.
     */
    private final String roomName;

    /**
     * {@link ConferenceListener} that will be notified about conference events.
     */
    private final ConferenceListener listener;

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
     * Indicates if the bridge used in this conference is faulty. We use this
     * flag to skip channel expiration step when conference is being disposed.
     */
    private boolean bridgeHasFailed;

    /**
     * The name of XMPP user used by the focus to login.
     */
    private final String focusUserName;

    /**
     * Chat room operation set used to handle MUC stuff.
     */
    private OperationSetMultiUserChat chatOpSet;

    /**
     * Conference room chat instance.
     */
    private ChatRoom2 chatRoom;

    /**
     * Operation set used to handle Jingle sessions with conference peers.
     */
    private OperationSetJingle jingle;

    /**
     * Colibri operation set used to manage videobridge channels allocations.
     */
    private OperationSetColibriConference colibri;

    /**
     * Instance of Colibri conference used in this conference. It will have
     * <tt>null</tt> value only when the conference has been disposed. To avoid
     * hitting <tt>null</tt> during conference restart all access must be
     * synchronized on {@link #colibriConfSyncRoot}.
     */
    private volatile ColibriConference colibriConference;

    /**
     * Write to {@link #colibriConference} is synchronized on this
     * <tt>Object</tt>.
     */
    private final Object colibriConfSyncRoot = new Object();

    /**
     * Jitsi Meet tool used for specific operations like adding presence
     * extensions.
     */
    private OperationSetJitsiMeetTools meetTools;

    /**
     * The list of active conference participants.
     */
    private final List<Participant> participants = new CopyOnWriteArrayList<>();

    /**
     * Takes care of conference recording.
     */
    private JitsiMeetRecording recording;

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
     * Indicates if this instance has been started(initialized).
     */
    private volatile boolean started;

    /**
     * Idle timestamp for this focus, -1 means active, otherwise
     * System.currentTimeMillis() is set when focus becomes idle.
     * Used to detect idle session and expire it if idle time limit is exceeded.
     */
    private long idleTimestamp = -1;

    /**
     * If the first element is <tt>true</tt> the participant
     * will start audio muted. if the second element is <tt>true</tt> the
     * participant will start video muted.
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
     * Creates new instance of {@link JitsiMeetConference}.
     *
     * @param roomName name of MUC room that is hosting the conference.
     * @param focusUserName focus user login.
     * @param listener the listener that will be notified about this instance
     *        events.
     * @param config the conference configuration instance.
     * @param globalConfig an instance of the global config service.
     */
    public JitsiMeetConference(String                   roomName,
                               String                   focusUserName,
                               ProtocolProviderHandler  protocolProviderHandler,
                               ConferenceListener       listener,
                               JitsiMeetConfig          config,
                               JitsiMeetGlobalConfig    globalConfig)
    {
        Assert.notNull(protocolProviderHandler, "protocolProviderHandler");
        Assert.notNull(config, "config");

        this.id = ID_DATE_FORMAT.format(new Date()) + "_" + hashCode();
        this.roomName = roomName;
        this.focusUserName = focusUserName;
        this.etherpadName = UUID.randomUUID().toString().replaceAll("-", "");
        this.protocolProviderHandler = protocolProviderHandler;
        this.listener = listener;
        this.config = config;
        this.globalConfig = globalConfig;
    }

    /**
     * Starts conference focus processing, bind listeners and so on...
     *
     * @throws Exception if error occurs during initialization. Instance is
     *         considered broken in that case. It's stop method will be called
     *         before throwing the exception to perform deinitialization where
     *         possible. {@link ConferenceListener}s will be notified that this
     *         conference has ended.
     */
    public synchronized void start()
        throws Exception
    {
        if (started)
            return;

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

            BundleContext osgiCtx = FocusBundleActivator.bundleContext;

            services
                = ServiceUtils.getService(osgiCtx, JitsiMeetServices.class);

            recording = new JitsiMeetRecording(this, services);

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
        }
        catch(Exception e)
        {
            this.stop();

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
            return;

        started = false;

        if (eventHandlerRegistration != null)
        {
            eventHandlerRegistration.unregister();
            eventHandlerRegistration = null;
        }

        protocolProviderHandler.removeRegistrationListener(this);

        synchronized (colibriConfSyncRoot)
        {
            disposeConference();
        }

        leaveTheRoom();

        if (jingle != null)
            jingle.terminateHandlersSessions(this);

        if (listener != null)
            listener.conferenceEnded(this);
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

        chatRoom = (ChatRoom2) chatOpSet.findRoom(roomName);

        rolesAndPresence = new ChatRoomRoleAndPresence(this, chatRoom);
        rolesAndPresence.init();

        chatRoom.join();

        // Advertise shared Etherpad document
        meetTools.sendPresenceExtension(
            chatRoom, EtherpadPacketExt.forDocumentName(etherpadName));

        // init recorder
        if (recording != null)
            recording.init();

        // Trigger focus joined room event
        EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(
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
    protected void onMemberJoined(final ChatRoomMember chatRoomMember)
    {
        logger.info(
            "Member " + chatRoomMember.getContactAddress() + " joined.");

        if (!isFocusMember(chatRoomMember))
        {
            idleTimestamp = -1;
        }

        // Are we ready to start ?
        if (!checkAtLeastTwoParticipants())
        {
            return;
        }

        synchronized (colibriConfSyncRoot)
        {
            if (colibriConference == null)
            {
                initNewColibriConference();
            }
        }

        // Invite all not invited yet
        if (participants.size() == 0)
        {
            for (final ChatRoomMember member : chatRoom.getMembers())
            {
                final boolean[] startMuted
                    = hasToStartMuted(
                            member, member == chatRoomMember /* justJoined */);

                inviteChatMember(member, startMuted, colibriConference);
            }
        }
        // Only the one who has just joined
        else
        {
            final boolean[] startMuted = hasToStartMuted(chatRoomMember, true);
            inviteChatMember(chatRoomMember, startMuted, colibriConference);
        }
    }

    /**
     * Initialized new instance of {@link #colibriConference}. Call to this
     * method must be synchronized on {@link #colibriConfSyncRoot}.
     */
    private void initNewColibriConference()
    {
        colibriConference = colibri.createNewConference();

        colibriConference.setConfig(config);

        String roomName = MucUtil.extractName(chatRoom.getName());
        colibriConference.setName(roomName);

        bridgeHasFailed = false;
    }

    /**
     * Invites new member to the conference which means new Jingle session
     * established and videobridge channels being allocated.
     *
     * @param chatRoomMember the chat member to be invited into the conference.
     * @param startMuted array with values for audio and video that indicates
     * whether the participant should start muted.
     */
    private void inviteChatMember(final ChatRoomMember       chatRoomMember,
                                  final boolean[]            startMuted,
                                  final ColibriConference    colibriConference)
    {
        if (isFocusMember(chatRoomMember))
            return;

        final String address = chatRoomMember.getContactAddress();

        final Participant newParticipant;

        // Peer already connected ?
        if (findParticipantForChatMember(chatRoomMember) != null)
            return;

        newParticipant
            = new Participant(
                    (XmppChatMember) chatRoomMember,
                    globalConfig.getMaxSSRCsPerUser());

        participants.add(newParticipant);

        logger.info("Added participant for: " + address);

        // Invite peer takes time because of channel allocation, so schedule
        // this on separate thread.
        FocusBundleActivator.getSharedThreadPool().submit(
                new ChannelAllocator(
                        this, colibriConference, newParticipant,
                        startMuted, false /* re-invite */));
    }

    /**
     * Returns array of boolean values that indicates whether the last
     * participant have to start video or audio muted.
     * @param member the participant
     * @param justJoined indicates whether the participant joined the room now
     * or he was in the room before.
     * @return array of boolean values that indicates whether the last
     * participant have to start video or audio muted. The first element
     * should be associated with the audio and the second with video.
     */
    private final boolean[] hasToStartMuted(ChatRoomMember    member,
                                            boolean           justJoined)
    {
        final boolean[] startMuted = new boolean[] {false, false};
        if(this.startMuted != null && this.startMuted[0] && justJoined)
            startMuted[0] = true;

        if(this.startMuted != null && this.startMuted[1] && justJoined)
            startMuted[1] = true;

        if(startMuted[0] && startMuted[1])
        {
            return startMuted;
        }

        int participantNumber = 0;
        if(member != null && member instanceof XmppChatMember)
        {
            participantNumber = ((XmppChatMember)member).getJoinOrderNumber();
        }
        else
        {
            participantNumber = participants.size();
        }

        if(!startMuted[0])
        {
            Integer startAudioMuted = this.config.getAudioMuted();
            if(startAudioMuted != null)
            {
                startMuted[0] = (participantNumber > startAudioMuted);
            }
        }

        if(!startMuted[1])
        {
            Integer startVideoMuted = this.config.getVideoMuted();
            if(startVideoMuted != null)
            {
                startMuted[1] = (participantNumber > startVideoMuted);
            }
        }

        return startMuted;
    }

    /**
     * Counts the number of non-focus chat room members and returns
     * <tt>true</tt> if there are at least two of them.
     *
     * @return <tt>true</tt> if we have at least two non-focus participants.
     */
    private boolean checkAtLeastTwoParticipants()
    {
        // 2 + 1 focus
        if (chatRoom.getMembersCount() >= (2 + 1))
            return true;

        int realCount = 0;
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (!isFocusMember(member))
                realCount++;
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
    boolean isFocusMember(ChatRoomMember member)
    {
        return member.getName().equals(focusUserName);
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
     * Expires the conference on the bridge and other stuff related to it.
     * Call must be synchronized on {@link #colibriConfSyncRoot}.
     */
    private void disposeConference()
    {
        // We dispose the recorder here as the recording session is usually
        // bound to Colibri conference instance which will be invalid once we
        // dispose/expire the conference on the bridge
        // FIXME re-init recorder after ICE restart
        if (recording != null)
        {
            recording.dispose();
        }

        if (colibriConference != null)
        {
            // We will not expire channels if the bridge is faulty or
            // when our connection is down
            if (!bridgeHasFailed && protocolProviderHandler.isRegistered())
            {
                colibriConference.expireConference();
            }
            else
            {
                colibriConference.dispose();
            }
            colibriConference = null;
        }
    }

    /**
     * Method called by {@link #rolesAndPresence} when one of the members has
     * been kicked out of the conference room.
     *
     * @param chatRoomMember kicked chat room member.
     */
    protected void onMemberKicked(ChatRoomMember chatRoomMember)
    {
        logger.info(
            "Member " + chatRoomMember.getContactAddress() + " kicked !!!");
        /*
        FIXME: terminate will have no effect, as peer's MUC address
         will be no longer active.
        Participant session = findParticipantForChatMember(chatRoomMember);
        if (session != null)
        {
            jingle.terminateSession(
                session.getJingleSession(), Reason.EXPIRED);
        }
        else
        {
            logger.warn("No active session with "
                            + chatRoomMember.getContactAddress());
        }*/

        onMemberLeft(chatRoomMember);
    }

    /**
     * Method called by {@link #rolesAndPresence} when someone leave conference
     * chat room.
     *
     * @param chatRoomMember the member that has left the room.
     */
    protected void onMemberLeft(ChatRoomMember chatRoomMember)
    {
        String contactAddress = chatRoomMember.getContactAddress();

        logger.info("Member " + contactAddress + " is leaving");

        Participant leftPeer = findParticipantForChatMember(chatRoomMember);
        if (leftPeer != null)
        {
            JingleSession peerJingleSession = leftPeer.getJingleSession();
            if (peerJingleSession != null)
            {
                logger.info("Hanging up member " + contactAddress);

                jingle.terminateSession(peerJingleSession, Reason.GONE);

                removeSSRCs(
                        peerJingleSession,
                        leftPeer.getSSRCsCopy(),
                        leftPeer.getSSRCGroupsCopy(),
                        false /* no JVB update - will expire */);

                expireParticipantChannels(colibriConference, leftPeer);
            }

            boolean removed = participants.remove(leftPeer);
            logger.info(
                    "Removed participant: " + removed + ", " + contactAddress);
        }
        else
        {
            logger.error("Member not found for " + contactAddress);
        }

        if (participants.size() == 0)
        {
            stop();
        }
    }

    /**
     * Expires channels for given {@link Participant} unless there are some
     * circumstances that prevents us from doing it.
     *
     * @param colibriConference <tt>ColibriConference</tt> instance that owns
     *        the channels to be expired.
     * @param participant the <tt>Participant</tt> whose Colibri channels are to
     *        be expired.
     */
    void expireParticipantChannels(ColibriConference colibriConference,
                                   Participant       participant)
    {
        ColibriConferenceIQ channelsInfo
            = participant.getColibriChannelsInfo();

        if (channelsInfo != null && colibriConference != null
                && !bridgeHasFailed)
        {
            logger.info("Expiring channels for: " + participant.getMucJid());
            colibriConference.expireChannels(channelsInfo);
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
            if (participant.getChatMember().getContactAddress().equals(
                    jingleSession.getAddress()))
                return participant;
        }
        return null;
    }

    private Participant findParticipantForChatMember(ChatRoomMember chatMember)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember().equals(chatMember))
                return participant;
        }
        return null;
    }

    Participant findParticipantForRoomJid(String roomJid)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember().getContactAddress().equals(roomJid))
            {
                return participant;
            }
        }
        return null;
    }

    public ChatRoomMemberRole getRoleForMucJid(String mucJid)
    {
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (member.getContactAddress().equals(mucJid))
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
    public void onSessionAccept( JingleSession peerJingleSession,
                                 List<ContentPacketExtension> answer)
    {
        Participant participant
            = findParticipantForJingleSession(peerJingleSession);
        String peerJingleSessionAddress = peerJingleSession.getAddress();

        if (participant == null)
        {
            logger.error(
                    "No participant found for: " + peerJingleSessionAddress);
            return;
        }

        if (participant.getJingleSession() != null)
        {
            //FIXME: we should reject it ?
            logger.error(
                    "Reassigning jingle session for participant: "
                        + peerJingleSessionAddress);
        }

        // XXX We will be acting on the received session-accept bellow.
        // Unfortunately, we may have not received an acknowledgment of our
        // session-initiate yet and whatever we do bellow will be torn down when
        // the acknowledgement timeout occurrs later on. Since we will have
        // acted on the session-accept by the time the acknowledgement timeout
        // occurs, we may as well ignore the timeout.
        peerJingleSession.setAccepted(true);

        participant.setJingleSession(peerJingleSession);

        // Extract and store various session information in the Participant
        participant.setRTPDescription(answer);
        participant.addTransportFromJingle(answer);
        participant.addSSRCsFromContent(answer);
        participant.addSSRCGroupsFromContent(answer);

        MediaSSRCMap peerSSRCs = participant.getSSRCsCopy();
        MediaSSRCGroupMap peerGroupsMap = participant.getSSRCGroupsCopy();

        logger.info(
                "Received SSRCs from " + peerJingleSessionAddress + " "
                    + peerSSRCs);

        // Update channel info - we may miss update during conference restart,
        // but the state will be synced up after channels are allocated for this
        // peer on the new bridge
        ColibriConference colibriConference = this.colibriConference;
        if (colibriConference != null)
        {
            colibriConference.updateChannelsInfo(
                    participant.getColibriChannelsInfo(),
                    participant.getRtpDescriptionMap(),
                    peerSSRCs,
                    peerGroupsMap,
                    participant.getBundleTransport(),
                    participant.getTransportMap());
        }

        // Loop over current participant and send 'source-add' notification
        propagateNewSSRCs(participant, peerSSRCs, peerGroupsMap);

        // Notify the peer itself since it is now stable
        if (participant.hasSsrcsToAdd())
        {
            jingle.sendAddSourceIQ(
                    participant.getSsrcsToAdd(),
                    participant.getSSRCGroupsToAdd(),
                    peerJingleSession);

            participant.clearSsrcsToAdd();
        }
        if (participant.hasSsrcsToRemove())
        {
            jingle.sendRemoveSourceIQ(
                    participant.getSsrcsToRemove(),
                    participant.getSsrcGroupsToRemove(),
                    peerJingleSession);

            participant.clearSsrcsToRemove();
        }
    }

    /**
     * Advertises new SSRCs across all conference participants by using
     * 'source-add' Jingle notification.
     *
     * @param ssrcOwner the <tt>Participant</tt> who owns the SSRCs.
     * @param ssrcsToAdd the <tt>MediaSSRCMap</tt> with the SSRCs to advertise.
     * @param ssrcGroupsToAdd the <tt>MediaSSRCGroupMap</tt> with SSRC groups
     *        to advertise.
     */
    private void propagateNewSSRCs(Participant          ssrcOwner,
                                   MediaSSRCMap         ssrcsToAdd,
                                   MediaSSRCGroupMap    ssrcGroupsToAdd)
    {
        for (Participant peerToNotify : participants)
        {
            // Skip origin
            if (ssrcOwner == peerToNotify)
                continue;

            JingleSession jingleSessionToNotify
                = peerToNotify.getJingleSession();
            if (jingleSessionToNotify == null)
            {
                logger.warn(
                        "No jingle session yet for "
                            + peerToNotify.getChatMember().getContactAddress());

                peerToNotify.scheduleSSRCsToAdd(ssrcsToAdd);

                peerToNotify.scheduleSSRCGroupsToAdd(ssrcGroupsToAdd);

                continue;
            }

            jingle.sendAddSourceIQ(
                    ssrcsToAdd, ssrcGroupsToAdd, jingleSessionToNotify);
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
        // based on it's hasBundleSupport() value
        participant.addTransportFromJingle(contentList);

        ColibriConference colibriConference = this.colibriConference;
        // We can hit null here during conference restart, but the state will be
        // synced up later when the client sends 'transport-accept'
        if (colibriConference == null)
        {
            logger.warn("Skipped transport-info processing - no conference");
            return;
        }

        if (participant.hasBundleSupport())
        {
            colibriConference.updateBundleTransportInfo(
                    participant.getBundleTransport(),
                    participant.getColibriChannelsInfo());
        }
        else
        {
            colibriConference.updateTransportInfo(
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
    public void onTransportAccept(JingleSession                   jingleSession,
                                  List<ContentPacketExtension>    contents)
    {
        jingleSession.setAccepted(true);

        logger.info("Got transport-accept from: " + jingleSession.getAddress());

        // We basically do the same processing as with transport-info by just
        // forwarding transport/rtp information to the bridge
        onTransportInfo(jingleSession, contents);
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
     * participant. New SSRCs received are advertised to active participants.
     * If some participant does not have Jingle session established yet then
     * those SSRCs are scheduled for future update.
     *
     * {@inheritDoc}
     */
    @Override
    public void onAddSource(JingleSession jingleSession,
                            List<ContentPacketExtension> contents)
    {
        Participant participant = findParticipantForJingleSession(jingleSession);
        if (participant == null)
        {
            logger.error("Add-source: no peer state for "
                             + jingleSession.getAddress());
            return;
        }

        MediaSSRCMap ssrcsToAdd
            = participant.addSSRCsFromContent(contents);

        MediaSSRCGroupMap ssrcGroupsToAdd
            = participant.addSSRCGroupsFromContent(contents);

        if (ssrcsToAdd.isEmpty() && ssrcGroupsToAdd.isEmpty())
        {
            logger.warn("Not sending source-add, notification would be empty");
            return;
        }

        // Updates SSRC Groups on the bridge
        // We may miss the notification, but the state will be synced up
        // after conference has been relocated to the new bridge
        ColibriConference colibriConference = this.colibriConference;
        if (colibriConference != null)
        {
            colibriConference.updateSourcesInfo(
                    participant.getSSRCsCopy(),
                    participant.getSSRCGroupsCopy(),
                    participant.getColibriChannelsInfo());
        }

        propagateNewSSRCs(participant, ssrcsToAdd, ssrcGroupsToAdd);
    }

    /**
     * Callback called when we receive 'source-remove' notification from
     * conference participant. New SSRCs received are advertised to active
     * participants. If some participant does not have Jingle session
     * established yet then those SSRCs are scheduled for future update.
     *
     * {@inheritDoc}
     */
    @Override
    public void onRemoveSource(JingleSession sourceJingleSession,
                               List<ContentPacketExtension> contents)
    {
        MediaSSRCMap ssrcsToRemove
            = MediaSSRCMap.getSSRCsFromContent(contents);

        MediaSSRCGroupMap ssrcGroupsToRemove
            = MediaSSRCGroupMap.getSSRCGroupsForContents(contents);

        removeSSRCs(
                sourceJingleSession, ssrcsToRemove, ssrcGroupsToRemove, true);
    }

    /**
     * Removes SSRCs from the conference and notifies other participants.
     *
     * @param sourceJingleSession source Jingle session from which SSRCs are
     *                            being removed.
     * @param ssrcsToRemove the {@link MediaSSRCMap} of SSRCs to be removed from
     *                      the conference.
     * @param updateChannels tells whether or not SSRC update request should be
     *                       sent to the bridge.
     */
    private void removeSSRCs(JingleSession        sourceJingleSession,
                             MediaSSRCMap         ssrcsToRemove,
                             MediaSSRCGroupMap    ssrcGroupsToRemove,
                             boolean              updateChannels)
    {
        Participant sourcePeer
            = findParticipantForJingleSession(sourceJingleSession);
        String peerAddress = sourceJingleSession.getAddress();
        if (sourcePeer == null)
        {
            logger.error("Remove-source: no session for " + peerAddress);
            return;
        }

        // Only SSRCs owned by this peer end up in "removed" set
        MediaSSRCMap removedSSRCs = sourcePeer.removeSSRCs(ssrcsToRemove);

        MediaSSRCGroupMap removedGroups
            = sourcePeer.removeSSRCGroups(ssrcGroupsToRemove);

        if (removedSSRCs.isEmpty() && removedGroups.isEmpty())
        {
            logger.warn(
                    "No ssrcs or groups to be removed from: "+ peerAddress);
            return;
        }

        // This prevents from removing SSRCs which do not belong to this peer
        ssrcsToRemove = removedSSRCs;
        ssrcGroupsToRemove = removedGroups;

        // We remove all ssrc params from SourcePacketExtension as we want
        // the client to simply remove all lines corresponding to given SSRC and
        // not care about parameter's values we send.
        // Some params might get out of sync for various reasons like for
        // example Chrome coming up with 'default' value for missing 'mslabel'
        // or when we'll be doing lip-sync stream merge
        SSRCSignaling.deleteSSRCParams(ssrcsToRemove);

        // Updates SSRC Groups on the bridge
        ColibriConference colibriConference = this.colibriConference;
        // We may hit null here during conference restart, but that's not
        // important since the bridge for this instance will not be used
        // anymore and state is synced up soon after channels are allocated
        if (updateChannels && colibriConference != null)
        {
            colibriConference.updateSourcesInfo(
                    sourcePeer.getSSRCsCopy(),
                    sourcePeer.getSSRCGroupsCopy(),
                    sourcePeer.getColibriChannelsInfo());
        }

        logger.info("Removing " + peerAddress + " SSRCs " + ssrcsToRemove);

        for (Participant peer : participants)
        {
            if (peer == sourcePeer)
                continue;

            JingleSession jingleSessionToNotify = peer.getJingleSession();
            if (jingleSessionToNotify == null)
            {
                logger.warn(
                        "Remove source: no jingle session for " + peerAddress);

                peer.scheduleSSRCsToRemove(ssrcsToRemove);

                peer.scheduleSSRCGroupsToRemove(ssrcGroupsToRemove);

                continue;
            }

            jingle.sendRemoveSourceIQ(
                    ssrcsToRemove, ssrcGroupsToRemove, jingleSessionToNotify);
        }
    }

    /**
     * Gathers the list of all SSRCs that exist in the current conference state.
     *
     * @param except optional <tt>Participant</tt> instance whose SSRCs will be
     *               excluded from the list
     *
     * @return <tt>MediaSSRCMap</tt> of all SSRCs of given media type that exist
     * in the current conference state.
     */
    MediaSSRCMap getAllSSRCs(Participant except)
    {
        MediaSSRCMap mediaSSRCs = new MediaSSRCMap();

        for (Participant peer : participants)
        {
            // We want to exclude this one
            if (peer == except)
                continue;

            mediaSSRCs.add(peer.getSSRCsCopy());
        }

        return mediaSSRCs;
    }

    /**
     * Gathers the list of all SSRC groups that exist in the current conference
     * state.
     *
     * @param except optional <tt>Participant</tt> instance whose SSRC groups
     *               will be excluded from the list
     *
     * @return the list of all SSRC groups of given media type that exist in
     *         current conference state.
     */
    MediaSSRCGroupMap getAllSSRCGroups(Participant except)
    {
        MediaSSRCGroupMap ssrcGroups = new MediaSSRCGroupMap();

        for (Participant peer : participants)
        {
            // Excluded this participant groups
            if (peer == except)
                continue;

            ssrcGroups.add(peer.getSSRCGroupsCopy());
        }

        return ssrcGroups;
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
     * Returns the name of conference multi-user chat room.
     */
    public String getRoomName()
    {
        return roomName;
    }

    /**
     * Returns XMPP protocol provider of the focus account.
     */
    public ProtocolProviderService getXmppProvider()
    {
        return protocolProviderHandler.getProtocolProvider();
    }

    ChatRoomMember findMember(String from)
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
     * Returns focus MUC JID if it is in the room or <tt>null</tt> otherwise.
     * JID example: room_name@muc.server.com/focus_nickname.
     */
    public String getFocusJid()
    {

        return roomName + "/" + focusUserName;
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
    boolean handleMuteRequest(String fromJid,
                              String toBeMutedJid,
                              boolean doMute)
    {
        ColibriConference colibriConference = this.colibriConference;
        if (colibriConference == null)
        {
            logger.error("Conference disposed - mute request not handled");
            return false;
        }

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
            logger.warn("Do not allow unmuting other participants!");
            return false;
        }

        logger.info(
            "Will " + (doMute ? "mute" : "unmute")
                + " " + toBeMutedJid + " on behalf of " + fromJid);

        boolean succeeded
            = colibriConference.muteParticipant(
                    participant.getColibriChannelsInfo(), doMute);

        if (succeeded)
        {
            participant.setMuted(doMute);
        }

        return succeeded;
    }

    /**
     * Returns the instance of {@link ColibriConference} used in this jitsi
     * Meet session.
     */
    public ColibriConference getColibriConference()
    {
        return colibriConference;
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
        String bridgeJid = bridgeEvent.getBridgeJid();
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

    private void onBridgeUp(String bridgeJid)
    {
        // Check if we're not shutting down
        if (!started)
            return;

        // Check if our Colibri conference has been disposed
        synchronized (colibriConfSyncRoot)
        {
            if (colibriConference == null)
            {
                logger.info(
                        "New bridge available: " + bridgeJid
                            + " will try to restart: " + getRoomName());

                // Trigger restart
                restartConference();
            }
        }
    }

    /**
     * Handles on bridge down event by shutting down the conference if it's the
     * one we're using here.
     */
    void onBridgeDown(String bridgeJid)
    {
        synchronized (colibriConfSyncRoot)
        {
            if (colibriConference != null
                    && bridgeJid.equals(
                            colibriConference.getJitsiVideobridge()))
            {
                // We will not send expire channels requests
                // when the bridge has failed
                bridgeHasFailed = true;

                restartConference();
            }
        }
    }

    private void restartConference()
    {
        logger.warn("Restarting the conference for room: " + getRoomName());

        disposeConference();

        initNewColibriConference();

        // Invite all not invited yet
        for (final Participant p : participants)
        {
            // Invite peer takes time because of channel allocation, so schedule
            // this on separate thread.
            FocusBundleActivator.getSharedThreadPool().submit(
                    new ChannelAllocator(
                            this, colibriConference,
                            p, startMuted, true /* re-invite */));
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
            ChannelAllocator         channelAllocator,
            OperationFailedException exc)
    {
        if (ChannelAllocator.BRIDGE_FAILURE_ERR_CODE == exc.getErrorCode())
        {
            // Notify users about bridge is down event
            if (meetTools != null && chatRoom != null)
            {
                meetTools.sendPresenceExtension(
                        chatRoom, new BridgeIsDownPacketExt());
            }
            // Dispose the conference. This way we'll know there is no
            // conference active and we can restart on new bridge
            synchronized (colibriConfSyncRoot)
            {
                disposeConference();
            }
        }
    }

    /**
     * Returns <tt>ChatRoom2</tt> instance for the MUC this instance is
     * currently in or <tt>null</tt> if it isn't in any.
     */
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
     * Returns <tt>JitsiMeetRecording</tt> for this conference.
     * @return <tt>JitsiMeetRecording</tt> instance for this conference or
     *         <tt>null</tt> if it's not available yet(should be after we join
     *         the MUC).
     */
    public JitsiMeetRecording getRecording()
    {
        return recording;
    }

    /**
     * Methods called by {@link ChannelAllocator} just after it has created new
     * Colibri conference on the JVB.
     *
     * @param colibriConference <tt>ColibriConference</tt> instance which just
     * has been allocated on the bridge
     * @param videobridgeJid the JID of the JVB where given
     * <tt>ColibriConference</tt> has been allocated
     */
    public void onColibriConferenceAllocated(
            ColibriConference    colibriConference,
            String               videobridgeJid)
    {
        EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(
                    EventFactory.conferenceRoom(
                            colibriConference.getConferenceId(),
                            roomName,
                            getId(),
                            videobridgeJid));
        }

        if (recording != null)
            recording.onConferenceAllocated();
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
        void conferenceEnded(JitsiMeetConference conference);
    }

    /**
     * Sets the value of the <tt>startMuted</tt> property of this instance.
     *
     * @param startMuted the new value to set on this instance. The specified
     * array is copied.
     */
    public void setStartMuted(boolean[] startMuted)
    {
        this.startMuted = startMuted;
    }
}
