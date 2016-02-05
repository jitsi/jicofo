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
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ.Recording.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.log.*;
import org.jitsi.jicofo.recording.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.eventadmin.*;
import org.jivesoftware.smack.packet.*;

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
               BridgeListener

{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(JitsiMeetConference.class);

    /**
     * Error code used in {@link OperationFailedException} when there are no
     * working videobridge bridges.
     * FIXME: consider moving to OperationFailedException ?
     */
    private final static int BRIDGE_FAILURE_ERR_CODE = 20;

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
     * Synchronization root for currently selected bridge for the
     * {@link #colibriConference}.
     */
    private final Object bridgeSelectSync = new Object();

    /**
     * The instance of <tt>BridgeSelector</tt> we use to select JVB for this
     * conference.
     */
    private BridgeSelector bridgeSelector;

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
     * instance of Colibri conference used in this conference.
     */
    private volatile ColibriConference colibriConference;

    /**
     * Jitsi Meet tool used for specific operations like adding presence
     * extensions.
     */
    private OperationSetJitsiMeetTools meetTools;

    /**
     * The list of active conference participants.
     */
    private final List<Participant> participants
        = new CopyOnWriteArrayList<Participant>();

    /**
     * Information about Jitsi Meet conference services like videobridge,
     * SIP gateway, Jirecon.
     */
    private JitsiMeetServices services;

    /**
     * Recording functionality implementation.
     */
    private Recorder recorder;

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
    private boolean[] startMuted = new boolean[] {false, false};

    /**
     * The name of shared Etherpad document. Is advertised through MUC Presence
     * by Jicofo user.
     */
    private final String etherpadName;

    /**
     * Keeps a record whether user has activated recording before other
     * participants has joined and the actual conference has been created.
     */
    private RecordingState earlyRecordingState = null;

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
    public JitsiMeetConference(String roomName,
                               String focusUserName,
                               ProtocolProviderHandler protocolProviderHandler,
                               ConferenceListener listener,
                               JitsiMeetConfig config,
                               JitsiMeetGlobalConfig globalConfig)
    {
        if (protocolProviderHandler == null)
            throw new NullPointerException("protocolProviderHandler");

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
            chatOpSet
                = protocolProviderHandler.getOperationSet(
                        OperationSetMultiUserChat.class);
            meetTools
                = protocolProviderHandler.getOperationSet(
                        OperationSetJitsiMeetTools.class);

            services
                = ServiceUtils.getService(
                        FocusBundleActivator.bundleContext,
                        JitsiMeetServices.class);

            bridgeSelector = services.getBridgeSelector();

            bridgeSelector.addBridgeListener(this);

            // Set pre-configured videobridge
            String preConfiguredBridge = config.getPreConfiguredVideobridge();

            if (!StringUtils.isNullOrEmpty(preConfiguredBridge))
            {
                bridgeSelector.setPreConfiguredBridge(
                        preConfiguredBridge);
            }

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

        protocolProviderHandler.removeRegistrationListener(this);

        if (bridgeSelector != null)
            bridgeSelector.removeBridgeListener(this);

        disposeConference();

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
    }

    private OperationSetDirectSmackXmpp getDirectXmppOpSet()
    {
        return protocolProviderHandler.getOperationSet(
            OperationSetDirectSmackXmpp.class);
    }

    /**
     * Lazy initializer for {@link #recorder}. If there is Jirecon component
     * service available then {@link JireconRecorder} is used. Otherwise we fall
     * back to direct videobridge communication through {@link JvbRecorder}.
     *
     * @return {@link Recorder} implementation used by this instance.
     */
    private Recorder getRecorder()
    {
        if (recorder == null)
        {
            OperationSetDirectSmackXmpp xmppOpSet
                = protocolProviderHandler.getOperationSet(
                        OperationSetDirectSmackXmpp.class);

            String recorderService = services.getJireconRecorder();
            if (!StringUtils.isNullOrEmpty(recorderService))
            {
                recorder
                    = new JireconRecorder(
                            getFocusJid(),
                            services.getJireconRecorder(), xmppOpSet);
            }
            else
            {
                logger.warn("No recorder service discovered - using JVB");

                if(colibriConference == null)
                {
                    return null;
                }

                String videobridge = colibriConference.getJitsiVideobridge();
                if (StringUtils.isNullOrEmpty(videobridge))
                {
                    //Unable to create JVB recorder, conference not started yet
                    return null;
                }

                recorder
                    = new JvbRecorder(
                            colibriConference.getConferenceId(),
                            videobridge,
                            colibriConference.getName(),
                            xmppOpSet);
            }
        }
        return recorder;
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

        // FIXME: verify
        if (colibriConference == null)
        {
            colibriConference = colibri.createNewConference();

            colibriConference.setConfig(config);

            String roomName = MucUtil.extractName(chatRoom.getName());
            colibriConference.setName(roomName);
        }

        // Invite all not invited yet
        if (participants.size() == 0)
        {
            for (final ChatRoomMember member : chatRoom.getMembers())
            {
                final boolean[] startMuted = hasToStartMuted(member,
                    member == chatRoomMember);
                inviteChatMember(member, startMuted);
            }
        }
        // Only the one who has just joined
        else
        {
            final boolean[] startMuted = hasToStartMuted(chatRoomMember, true);
            inviteChatMember(chatRoomMember, startMuted);
        }
    }

    /**
     * Invites new member to the conference which means new Jingle session
     * established and videobridge channels being allocated.
     *
     * @param chatRoomMember the chat member to be invited into the conference.
     * @param startMuted array with values for audio and video that indicates
     * whether the participant should start muted.
     */
    private void inviteChatMember(final ChatRoomMember chatRoomMember,
        final boolean[] startMuted)
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
        FocusBundleActivator.getSharedThreadPool().submit(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    discoverFeaturesAndInvite(
                            newParticipant, address, startMuted);
                }
                catch (Exception e)
                {
                    logger.error("Exception on participant invite", e);
                }
            }
        });
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
    private final boolean[] hasToStartMuted(ChatRoomMember member,
        boolean justJoined)
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
     * Methods executed on thread pool is time consuming as is doing feature
     * discovery and channel allocation for new participants.
     * @param newParticipant new <tt>Participant</tt> instance.
     * @param address new participant full MUC address.
     * @param startMuted if the first element is <tt>true</tt> the participant
     * will start audio muted. if the second element is <tt>true</tt> the
     * participant will start video muted.
     */
    private void discoverFeaturesAndInvite(Participant     newParticipant,
                                           String          address,
                                           boolean[]       startMuted)
    {
        // Feature discovery
        List<String> features
            = DiscoveryUtil.discoverParticipantFeatures(
            getXmppProvider(), address);

        newParticipant.setSupportedFeatures(features);

        logger.info(
            address + " has bundle ? " + newParticipant.hasBundleSupport());

        // Store instance here as it is set to null when conference is disposed
        ColibriConference conference = this.colibriConference;
        List<ContentPacketExtension> offer;

        try
        {
            offer = createOffer(newParticipant);
            if (offer == null)
            {
                logger.info("Channel allocation cancelled for " + address);
                return;
            }
        }
        catch (OperationFailedException e)
        {
            //FIXME: retry ? sometimes it's just timeout
            logger.error("Failed to allocate channels for " + address, e);

            // Notify users about bridge is down event
            if (BRIDGE_FAILURE_ERR_CODE == e.getErrorCode() && chatRoom != null)
            {
                meetTools.sendPresenceExtension(
                    chatRoom, new BridgeIsDownPacketExt());
            }
            // Cancel - no channels allocated
            return;
        }
        /*
           This check makes sure that at the point when we're trying to
           invite new participant:
           - the conference has not been disposed in the meantime
           - he's still in the room
           - we have managed to send Jingle session-initiate
           Otherwise we expire allocated channels.
        */
        boolean expireChannels = false;

        if (chatRoom == null)
        {
            // Conference disposed
            logger.info(
                "Expiring " + address + " channels - conference disposed");

            expireChannels = true;
        }
        else if (findMember(address) == null)
        {
            // Participant has left the room
            logger.info(
                "Expiring " + address + " channels - participant has left");

            expireChannels = true;
        }
        else if (!jingle.initiateSession(
                     newParticipant.hasBundleSupport(), address, offer, this,
                     startMuted))
        {
            // Failed to invite
            logger.info(
                "Expiring " + address +
                    " channels - no RESULT for session-invite");

            expireChannels = true;
        }

        if (expireChannels)
        {
            conference.expireChannels(
                newParticipant.getColibriChannelsInfo());
        }
    }

    /**
     * Allocates Colibri channels for given {@link Participant} by trying all
     * available bridges returned by {@link BridgeSelector}.
     *
     * @param peer the for whom Colibri channel are to be allocated.
     * @param contents the media offer description passed to the bridge.
     *
     * @return {@link ColibriConferenceIQ} that describes channels allocated for
     *         given <tt>peer</tt>. <tt>null</tt> is returned if conference is
     *         disposed before we manage to allocate the channels.
     *
     * @throws OperationFailedException if we have failed to allocate channels
     *         using existing bridge and we can not switch to another bridge.
     */
    private ColibriConferenceIQ allocateChannels(
            Participant peer, List<ContentPacketExtension> contents)
        throws OperationFailedException
    {
        // This method is executed on thread pool.

        // Store colibri instance here to be able to free the channels even
        // after the conference has been disposed.
        ColibriConference colibriConference = this.colibriConference;
        if (this.colibriConference == null)
        {
            // Nope - the conference has been disposed, before the thread got
            // the chance to do anything
            return null;
        }

        // Set initial bridge if we haven't used any yet
        synchronized (bridgeSelectSync)
        {
            if (StringUtils.isNullOrEmpty(
                    colibriConference.getJitsiVideobridge()))
            {
                String bridge = null;

                if (!StringUtils.isNullOrEmpty(config.getEnforcedVideobridge()))
                {
                    bridge = config.getEnforcedVideobridge();
                    logger.info(
                        "Will force bridge: " + bridge
                            + " on: " + getRoomName());
                }
                else
                {
                    bridge = bridgeSelector.selectVideobridge();
                }

                if (StringUtils.isNullOrEmpty(bridge))
                {
                    throw new OperationFailedException(
                        "Failed to allocate channels - no bridge configured",
                        BRIDGE_FAILURE_ERR_CODE);
                }

                colibriConference.setJitsiVideobridge(bridge);
            }
        }

        String jvb = null;

        while (this.colibriConference != null)
        {
            try
            {
                synchronized (bridgeSelectSync)
                {
                    jvb = colibriConference.getJitsiVideobridge();
                }

                logger.info(
                    "Using " + jvb + " to allocate channels for: "
                        + peer.getChatMember().getContactAddress());

                ColibriConferenceIQ peerChannels
                    = colibriConference.createColibriChannels(
                            peer.hasBundleSupport(),
                            peer.getEndpointId(),
                            true, contents);

                bridgeSelector.updateBridgeOperationalStatus(jvb, true);

                if (colibriConference.hasJustAllocated())
                {
                    EventAdmin eventAdmin
                            = FocusBundleActivator.getEventAdmin();
                    if (eventAdmin != null)
                    {
                        eventAdmin.sendEvent(
                            EventFactory.conferenceRoom(
                                    colibriConference.getConferenceId(),
                                    roomName,
                                    getId(),
                                    jvb));
                    }
                }
                return peerChannels;
            }
            catch(OperationFailedException exc)
            {
                logger.error(
                    "Failed to allocate channels using bridge: " + jvb, exc);

                bridgeSelector.updateBridgeOperationalStatus(jvb, false);

                // Check if the conference is in progress
                if (!StringUtils.isNullOrEmpty(
                        colibriConference.getConferenceId()))
                {
                    // Restart
                    logger.error("Bridge failure - stopping the conference");
                    stop();
                }

                // Try next bridge
                synchronized (bridgeSelectSync)
                {
                    if (StringUtils.isNullOrEmpty(
                            config.getEnforcedVideobridge()))
                    {
                        jvb = bridgeSelector.selectVideobridge();
                    }
                    else
                    {
                        // If the "enforced" bridge has failed we do not try
                        // any other bridges, but fail immediately
                        jvb = null;
                    }

                    // Is it the same which has just failed ?
                    // (we do not always call iterator.next() at the beginning)
                    //if (faultyBridge.equals(nextBridge))
                    //    nextBridge = null;
                    if (!StringUtils.isNullOrEmpty(jvb))
                    {
                        colibriConference.setJitsiVideobridge(jvb);
                    }
                    else
                    {
                        // No more bridges to try
                        throw new OperationFailedException(
                            "Failed to allocate channels " +
                                "- all bridges are faulty",
                                BRIDGE_FAILURE_ERR_CODE);
                    }
                }
            }
        }
        // If we reach this point it means that the conference has been disposed
        // before we've managed to allocate anything
        return null;
    }

    /**
     * Creates Jingle offer for given {@link Participant}.
     *
     * @param peer the participant for whom Jingle offer will be created.
     *
     * @return the list of contents describing conference Jingle offer or
     *         <tt>null</tt> if the conference has been disposed before we've
     *         managed to allocate Colibri channels.
     *
     * @throws OperationFailedException if focus fails to allocate channels
     *         or something goes wrong.
     */
    private List<ContentPacketExtension> createOffer(Participant peer)
        throws OperationFailedException
    {
        List<ContentPacketExtension> contents
            = new ArrayList<ContentPacketExtension>();

        boolean disableIce = !peer.hasIceSupport();
        boolean useDtls = peer.hasDtlsSupport();

        if (peer.hasAudioSupport())
        {
            contents.add(
                JingleOfferFactory.createContentForMedia(
                    MediaType.AUDIO, disableIce, useDtls));
        }

        if (peer.hasVideoSupport())
        {
            contents.add(
                JingleOfferFactory.createContentForMedia(
                    MediaType.VIDEO, disableIce, useDtls));
        }

        // Is SCTP enabled ?
        boolean openSctp = config == null || config.openSctp() == null
                ? true : config.openSctp();

        if (openSctp && peer.hasSctpSupport())
        {
            contents.add(
                JingleOfferFactory.createContentForMedia(
                    MediaType.DATA, disableIce, useDtls));
        }

        boolean useBundle = peer.hasBundleSupport();

        ColibriConferenceIQ peerChannels = allocateChannels(peer, contents);

        if (peerChannels == null)
            return null;

        if (earlyRecordingState != null)
        {
            RecordingState recState = earlyRecordingState;
            earlyRecordingState = null;

            Recorder rec = getRecorder();
            if(rec == null)
                logger.error("No recorder found");
            else
            {
                boolean isTokenCorrect = recorder.setRecording(
                    recState.from,
                    recState.token,
                    recState.state,
                    recState.path);

                if (!isTokenCorrect)
                {
                    logger.info(
                        "Incorrect recording token received ! Session: "
                            + chatRoom.getName());
                }

                if (recorder.isRecording())
                {
                    ColibriConferenceIQ response = new ColibriConferenceIQ();

                    response.setType(IQ.Type.SET);
                    response.setTo(recState.from);
                    response.setFrom(recState.to);

                    if(colibriConference != null)
                        response.setName(colibriConference.getName());

                    response.setRecording(
                        new ColibriConferenceIQ.Recording(State.ON));

                    protocolProviderHandler.getOperationSet(
                        OperationSetDirectSmackXmpp.class).getXmppConnection()
                        .sendPacket(response);
                }
            }
        }

        peer.setColibriChannelsInfo(peerChannels);

        for (ContentPacketExtension cpe : contents)
        {
            ColibriConferenceIQ.Content colibriContent
                = peerChannels.getContent(cpe.getName());

            if (colibriContent == null)
                continue;

            // Channels
            for (ColibriConferenceIQ.Channel channel
                : colibriContent.getChannels())
            {
                IceUdpTransportPacketExtension transport;

                if (useBundle)
                {
                    ColibriConferenceIQ.ChannelBundle bundle
                        = peerChannels.getChannelBundle(
                                channel.getChannelBundleId());

                    if (bundle == null)
                    {
                        logger.error(
                            "No bundle for " + channel.getChannelBundleId());
                        continue;
                    }

                    transport = bundle.getTransport();

                    if (!transport.isRtcpMux())
                    {
                        transport.addChildExtension(
                            new RtcpmuxPacketExtension());
                    }
                }
                else
                {
                    transport = channel.getTransport();
                }

                try
                {
                    // Remove empty transport
                    IceUdpTransportPacketExtension empty
                        = cpe.getFirstChildOfType(
                                IceUdpTransportPacketExtension.class);
                    cpe.getChildExtensions().remove(empty);

                    cpe.addChildExtension(
                        IceUdpTransportPacketExtension
                            .cloneTransportAndCandidates(transport, true));
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
            // SCTP connections
            for (ColibriConferenceIQ.SctpConnection sctpConn
                : colibriContent.getSctpConnections())
            {
                IceUdpTransportPacketExtension transport;

                if (useBundle)
                {
                    ColibriConferenceIQ.ChannelBundle bundle
                        = peerChannels.getChannelBundle(
                                sctpConn.getChannelBundleId());

                    if (bundle == null)
                    {
                        logger.error(
                            "No bundle for " + sctpConn.getChannelBundleId());
                        continue;
                    }

                    transport = bundle.getTransport();
                }
                else
                {
                    transport = sctpConn.getTransport();
                }

                try
                {
                    // Remove empty transport
                    IceUdpTransportPacketExtension empty
                        = cpe.getFirstChildOfType(
                                IceUdpTransportPacketExtension.class);
                    cpe.getChildExtensions().remove(empty);

                    IceUdpTransportPacketExtension copy
                        = IceUdpTransportPacketExtension
                            .cloneTransportAndCandidates(transport, true);

                    // FIXME: hardcoded
                    SctpMapExtension sctpMap = new SctpMapExtension();
                    sctpMap.setPort(5000);
                    sctpMap.setProtocol(
                            SctpMapExtension.Protocol.WEBRTC_CHANNEL);
                    sctpMap.setStreams(1024);

                    copy.addChildExtension(sctpMap);

                    cpe.addChildExtension(copy);
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
            // Existing peers SSRCs
            RtpDescriptionPacketExtension rtpDescPe
                = JingleUtils.getRtpDescription(cpe);
            if (rtpDescPe != null)
            {
                if (useBundle)
                {
                    // rtcp-mux
                    rtpDescPe.addChildExtension(
                        new RtcpmuxPacketExtension());
                }

                // Copy SSRC sent from the bridge(only the first one)
                for (ColibriConferenceIQ.Channel channel
                    : colibriContent.getChannels())
                {
                    SourcePacketExtension ssrcPe
                        = channel.getSources().size() > 0
                                ? channel.getSources().get(0) : null;
                    if (ssrcPe == null)
                        continue;

                    try
                    {
                        String contentName = colibriContent.getName();
                        SourcePacketExtension ssrcCopy = ssrcPe.copy();

                        // FIXME: not all parameters are used currently
                        ssrcCopy.addParameter(
                            new ParameterPacketExtension("cname","mixed"));
                        ssrcCopy.addParameter(
                            new ParameterPacketExtension(
                                "label",
                                "mixedlabel" + contentName + "0"));
                        ssrcCopy.addParameter(
                            new ParameterPacketExtension(
                                "msid",
                                "mixedmslabel mixedlabel"
                                    + contentName + "0"));
                        ssrcCopy.addParameter(
                            new ParameterPacketExtension(
                                "mslabel", "mixedmslabel"));

                        // Mark 'jvb' as SSRC owner
                        SSRCInfoPacketExtension ssrcInfo
                            = new SSRCInfoPacketExtension();
                        ssrcInfo.setOwner("jvb");
                        ssrcCopy.addChildExtension(ssrcInfo);

                        rtpDescPe.addChildExtension(ssrcCopy);
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC error", e);
                    }
                }

                // Include all peers SSRCs
                List<SourcePacketExtension> mediaSources
                    = getAllSSRCs(cpe.getName());

                for (SourcePacketExtension ssrc : mediaSources)
                {
                    try
                    {
                        rtpDescPe.addChildExtension(ssrc.copy());
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC error", e);
                    }
                }

                // Include SSRC groups
                List<SourceGroupPacketExtension> sourceGroups
                    = getAllSSRCGroups(cpe.getName());
                for(SourceGroupPacketExtension ssrcGroup : sourceGroups)
                {
                    rtpDescPe.addChildExtension(ssrcGroup);
                }
            }
        }

        return contents;
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
     * Expires the conference on the bridge and other stuff realted to it.
     */
    private void disposeConference()
    {
        // FIXME: Does it make sense to put recorder here ?
        if (recorder != null)
        {
            recorder.dispose();
            recorder = null;
        }

        if (colibriConference != null)
        {
            if (protocolProviderHandler.isRegistered())
            {
                colibriConference.expireConference();
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

                removeSSRCs(peerJingleSession,
                        leftPeer.getSSRCsCopy(),
                        leftPeer.getSSRCGroupsCopy());

                ColibriConferenceIQ peerChannels
                        = leftPeer.getColibriChannelsInfo();
                if (peerChannels != null)
                {
                    logger.info("Expiring channels for: " + contactAddress);
                    colibriConference.expireChannels(
                        leftPeer.getColibriChannelsInfo());
                }
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

    ChatRoomMemberRole getRoleForMucJid(String mucJid)
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

        if (participant == null)
        {
            logger.error(
                "No participant found for: " + peerJingleSession.getAddress());
            return;
        }

        if (participant.getJingleSession() != null)
        {
            //FIXME: we should reject it ?
            logger.error(
                    "Reassigning jingle session for participant: "
                        + peerJingleSession.getAddress());
        }

        // XXX We will be acting on the received session-accept bellow.
        // Unfortunately, we may have not received an acknowledgment of our
        // session-initiate yet and whatever we do bellow will be torn down when
        // the acknowledgement timeout occurrs later on. Since we will have
        // acted on the session-accept by the time the acknowledgement timeout
        // occurs, we may as well ignore the timeout.
        peerJingleSession.setAccepted(true);

        participant.setJingleSession(peerJingleSession);

        participant.addSSRCsFromContent(answer);

        participant.addSSRCGroupsFromContent(answer);

        logger.info(
            "Received SSRCs from " + peerJingleSession.getAddress()
                + " " + participant.getSSRCS());

        // Update SSRC groups
        colibriConference.updateSourcesInfo(
            participant.getSSRCsCopy(),
            participant.getSSRCGroupsCopy(),
            participant.getColibriChannelsInfo());

        for (Participant peerToNotify : participants)
        {
            JingleSession jingleSessionToNotify
                    = peerToNotify.getJingleSession();
            if (jingleSessionToNotify == null)
            {
                logger.warn(
                    "No jingle session yet for "
                        + peerToNotify.getChatMember().getContactAddress());

                peerToNotify.scheduleSSRCsToAdd(participant.getSSRCS());

                peerToNotify.scheduleSSRCGroupsToAdd(
                    participant.getSSRCGroups());

                continue;
            }

            // Skip origin
            if (peerJingleSession.equals(jingleSessionToNotify))
                continue;

            jingle.sendAddSourceIQ(
                    participant.getSSRCS(),
                    participant.getSSRCGroups(),
                    jingleSessionToNotify);
        }

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

        // Notify the bridge about eventual transport included
        onTransportInfo(peerJingleSession, answer);

        // Notify the bridge about eventual RTP description included.
        onDescriptionInfo(peerJingleSession, answer);
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

        if (participant.hasBundleSupport())
        {
            // Select first transport
            IceUdpTransportPacketExtension transport = null;
            for (ContentPacketExtension cpe : contentList)
            {
                IceUdpTransportPacketExtension contentTransport
                    = cpe.getFirstChildOfType(
                        IceUdpTransportPacketExtension.class);
                if (contentTransport != null)
                {
                    transport = contentTransport;
                    break;
                }
            }
            if (transport == null)
            {
                logger.error(
                    "No valid transport suppied in transport-update from "
                        + participant.getChatMember().getContactAddress());
                return;
            }

            transport.addChildExtension(
                new RtcpmuxPacketExtension());

            // FIXME: initiator
            boolean initiator = true;
            colibriConference.updateBundleTransportInfo(
                initiator,
                transport,
                participant.getColibriChannelsInfo());
        }
        else
        {
            Map<String, IceUdpTransportPacketExtension> transportMap
                = new HashMap<String, IceUdpTransportPacketExtension>();

            for (ContentPacketExtension cpe : contentList)
            {
                IceUdpTransportPacketExtension transport
                    = cpe.getFirstChildOfType(
                            IceUdpTransportPacketExtension.class);
                if (transport != null)
                {
                    transportMap.put(cpe.getName(), transport);
                }
            }

            // FIXME: initiator
            boolean initiator = true;
            colibriConference.updateTransportInfo(
                initiator,
                transportMap,
                participant.getColibriChannelsInfo());
        }
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
        colibriConference.updateSourcesInfo(
            participant.getSSRCsCopy(),
            participant.getSSRCGroupsCopy(),
            participant.getColibriChannelsInfo());

        for (Participant peerToNotify : participants)
        {
            if (peerToNotify == participant)
                continue;

            JingleSession peerJingleSession = peerToNotify.getJingleSession();
            if (peerJingleSession == null)
            {
                logger.warn(
                    "Add source: no call for "
                        + peerToNotify.getChatMember().getContactAddress());

                peerToNotify.scheduleSSRCsToAdd(ssrcsToAdd);

                peerToNotify.scheduleSSRCGroupsToAdd(ssrcGroupsToAdd);

                continue;
            }

            jingle.sendAddSourceIQ(
                ssrcsToAdd, ssrcGroupsToAdd, peerJingleSession);
        }
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

        removeSSRCs(sourceJingleSession, ssrcsToRemove, ssrcGroupsToRemove);
    }

    public void onDescriptionInfo(JingleSession session,
                                  List<ContentPacketExtension> contents)
    {
        if (session == null)
        {
            logger.error("session is null.");
            return;
        }

        if (contents == null || contents.isEmpty())
        {
            logger.error("contents is null.");
            return;
        }

        Participant participant = findParticipantForJingleSession(session);
        if (participant == null)
        {
            logger.error("no peer state for " + session.getAddress());
            return;
        }

        Map<String, RtpDescriptionPacketExtension> rtpDescMap
                = new HashMap<String, RtpDescriptionPacketExtension>();

        for (ContentPacketExtension content : contents)
        {
            RtpDescriptionPacketExtension rtpDesc
                    = content.getFirstChildOfType(
                RtpDescriptionPacketExtension.class);

            if (rtpDesc == null)
            {
                continue;
            }

            rtpDescMap.put(content.getName(), rtpDesc);
        }

        if (!rtpDescMap.isEmpty())
        {
            colibriConference.updateRtpDescription(
                rtpDescMap, participant.getColibriChannelsInfo());
        }
    }

    /**
     * Removes SSRCs from the conference and notifies other participants.
     *
     * @param sourceJingleSession source Jingle session from which SSRCs are
     *                            being removed.
     * @param ssrcsToRemove the {@link MediaSSRCMap} of SSRCs to be removed from
     *                      the conference.
     */
    private void removeSSRCs(JingleSession sourceJingleSession,
                             MediaSSRCMap ssrcsToRemove,
                             MediaSSRCGroupMap ssrcGroupsToRemove)
    {
        Participant sourcePeer
            = findParticipantForJingleSession(sourceJingleSession);
        if (sourcePeer == null)
        {
            logger.error("Remove-source: no session for "
                             + sourceJingleSession.getAddress());
            return;
        }

        // Only SSRCs owned by this peer end up in "removed" set
        MediaSSRCMap removedSSRCs = sourcePeer.removeSSRCs(ssrcsToRemove);

        MediaSSRCGroupMap removedGroups
            = sourcePeer.removeSSRCGroups(ssrcGroupsToRemove);

        if (removedSSRCs.isEmpty() && removedGroups.isEmpty())
        {
            logger.warn(
                "No ssrcs or groups to be removed from: "
                    + sourceJingleSession.getAddress());
            return;
        }

        // This prevents from removing SSRCs which do not belong to this peer
        ssrcsToRemove = removedSSRCs;
        ssrcGroupsToRemove = removedGroups;

        // Updates SSRC Groups on the bridge
        colibriConference.updateSourcesInfo(
            sourcePeer.getSSRCsCopy(),
            sourcePeer.getSSRCGroupsCopy(),
            sourcePeer.getColibriChannelsInfo());

        logger.info(
            "Removing " + sourceJingleSession.getAddress()
                + " SSRCs " + ssrcsToRemove);

        for (Participant peer : participants)
        {
            if (peer == sourcePeer)
                continue;

            JingleSession jingleSessionToNotify = peer.getJingleSession();
            if (jingleSessionToNotify == null)
            {
                logger.warn(
                    "Remove source: no jingle session for "
                        + peer.getChatMember().getContactAddress());

                peer.scheduleSSRCsToRemove(ssrcsToRemove);

                peer.scheduleSSRCGroupsToRemove(ssrcGroupsToRemove);

                continue;
            }

            jingle.sendRemoveSourceIQ(
                    ssrcsToRemove, ssrcGroupsToRemove, jingleSessionToNotify);
        }
    }

    /**
     * Gathers the list of all SSRCs of given media type that exist in current
     * conference state.
     *
     * @param media the media type of SSRCs that are being returned.
     *
     * @return the list of all SSRCs of given media type that exist in current
     *         conference state.
     */
    private List<SourcePacketExtension> getAllSSRCs(String media)
    {
        List<SourcePacketExtension> mediaSSRCs
            = new ArrayList<SourcePacketExtension>();

        for (Participant peer : participants)
        {
            List<SourcePacketExtension> peerSSRC
                = peer.getSSRCS().getSSRCsForMedia(media);

            if (peerSSRC != null)
                mediaSSRCs.addAll(peerSSRC);
        }

        return mediaSSRCs;
    }

    /**
     * Gathers the list of all SSRC groups of given media type that exist in
     * current conference state.
     *
     * @param media the media type of SSRC groups that are being returned.
     *
     * @return the list of all SSRC groups of given media type that exist in
     *         current conference state.
     */
    private List<SourceGroupPacketExtension> getAllSSRCGroups(String media)
    {
        List<SourceGroupPacketExtension> ssrcGroups
            = new ArrayList<SourceGroupPacketExtension>();

        for (Participant peer : participants)
        {
            List<SSRCGroup> peerSSRCGroups
                = peer.getSSRCGroupsForMedia(media);

            for (SSRCGroup ssrcGroup : peerSSRCGroups)
            {
                try
                {
                    ssrcGroups.add(ssrcGroup.getExtensionCopy());
                }
                catch (Exception e)
                {
                    logger.error("Error copying source group extension");
                }
            }
        }

        return ssrcGroups;
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

    /**
     * Attempts to modify conference recording state.
     *
     * @param from JID of the participant that wants to modify recording state.
     * @param token recording security token that will be verified on modify
     *              attempt.
     * @param state the new recording state to set.
     * @param path output recording path(recorder implementation and deployment
     *             dependent).
     * @param to the received colibri packet destination.
     * @return new recording state(unchanged if modify attempt has failed).
     */
    public State modifyRecordingState(
            String from, String token, State state, String path, String to)
    {
        ChatRoomMember member = findMember(from);
        if (member == null)
        {
            logger.error("No member found for address: " + from);
            return State.OFF;
        }
        if (ChatRoomMemberRole.MODERATOR.compareTo(member.getRole()) < 0)
        {
            logger.info("Recording - request denied, not a moderator: " + from);
            return State.OFF;
        }

        Recorder recorder = getRecorder();
        if (recorder == null)
        {
            if(state.equals(State.OFF))
            {
                earlyRecordingState = null;
                return State.OFF;
            }

            // save for later dispatching
            earlyRecordingState = new RecordingState(
                from, token, state, path, to);
            return State.PENDING;
        }

        boolean isTokenCorrect
            = recorder.setRecording(from, token, state, path);
        if (!isTokenCorrect)
        {
            logger.info(
                "Incorrect recording token received ! Session: "
                    + chatRoom.getName());
        }

        return recorder.isRecording() ? State.ON : State.OFF;
    }

    private ChatRoomMember findMember(String from)
    {
        return chatRoom != null ?
            chatRoom.findChatMember(from) : null;
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

    /**
     * Handles on bridge up event(no action for now - we don't care here)
     */
    @Override
    public void onBridgeUp(BridgeSelector src, String bridgeJid) { }

    /**
     * Handles on bridge down event by shutting down the conference if it's the
     * one we're using here.
     */
    @Override
    public void onBridgeDown(BridgeSelector src, String bridgeJid)
    {
        if (colibriConference != null &&
            bridgeJid.equals(colibriConference.getJitsiVideobridge()))
        {
            stop();
        }
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
     * Sets <tt>startMuted</tt> property.
     * @param startMuted the new value to be set.
     */
    public void setStartMuted(boolean[] startMuted)
    {
        this.startMuted = startMuted;
    }

    /**
     * Saves early recording requests by user. Dispatched when new participant
     * joins.
     */
    private static class RecordingState
    {
        /**
         * JID of the participant that wants to modify recording state.
         */
        String from;

        /**
         * Recording security token that will be verified on modify attempt.
         */
        String token;

        /**
         * The new recording state to set.
         */
        State state;

        /**
         * Output recording path(recorder implementation
         * and deployment dependent).
         */
        String path;

        /**
         * The received colibri packet destination.
         */
        String to;

        public RecordingState(String from, String token,
            State state, String path, String to)
        {
            this.from = from;
            this.token = token;
            this.state = state;
            this.path = path;
            this.to = to;
        }
    }
}
