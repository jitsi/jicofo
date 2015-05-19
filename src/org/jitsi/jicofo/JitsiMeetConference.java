/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
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
import org.jitsi.protocol.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.eventadmin.*;

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
               JingleRequestHandler

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
     * XMPP protocol provider handler used by the focus.
     */
    private ProtocolProviderHandler protocolProviderHandler
        = new ProtocolProviderHandler();

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
    private ChatRoom chatRoom;

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
    private ColibriConference colibriConference;

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
     * Creates new instance of {@link JitsiMeetConference}.
     *
     * @param roomName name of MUC room that is hosting the conference.
     * @param focusUserName focus user login.
     * @param listener the listener that will be notified about this instance
     *        events.
     * @param config the conference configuration instance.
     */
    public JitsiMeetConference(String roomName,
                               String focusUserName,
                               ProtocolProviderHandler protocolProviderHandler,
                               ConferenceListener listener,
                               JitsiMeetConfig config)
    {
        this.id = ID_DATE_FORMAT.format(new Date()) + "_" + hashCode();
        this.roomName = roomName;
        this.focusUserName = focusUserName;
        this.protocolProviderHandler = protocolProviderHandler;
        this.listener = listener;
        this.config = config;
    }

    /**
     * Starts conference focus processing, bind listeners and so on...
     *
     * @throws Exception if error occurs during initialization. Instance is
     *         considered broken in that case.
     */
    public void start()
        throws Exception
    {
        if (started)
            return;

        started = true;

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

        // Set pre-configured videobridge
        services.getBridgeSelector()
            .setPreConfiguredBridge(config.getPreConfiguredVideobridge());

        // Set pre-configured SIP gateway
        if (config.getPreConfiguredSipGateway() != null)
        {
            services.setSipGateway(config.getPreConfiguredSipGateway());
        }

        if (protocolProviderHandler.isRegistered())
        {
            joinTheRoom();
        }
        else
        {
            // Wait until it registers
            protocolProviderHandler.addRegistrationListener(this);
        }

        idleTimestamp = System.currentTimeMillis();
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
     */
    private void joinTheRoom()
    {
        logger.info("Joining the room: " + roomName);

        try
        {
            chatRoom = chatOpSet.findRoom(roomName);

            rolesAndPresence = new ChatRoomRoleAndPresence(this, chatRoom);
            rolesAndPresence.init();

            chatRoom.join();
        }
        catch (Exception e)
        {
            logger.error(e, e);

            stop();
        }
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

                String videobridge = colibriConference.getJitsiVideobridge();
                if (StringUtils.isNullOrEmpty(videobridge))
                {
                    logger.error(
                        "Unable to create JVB recorder - conferenc enot started yet.");
                    return null;
                }

                recorder
                    = new JvbRecorder(
                            colibriConference.getConferenceId(),
                            videobridge, xmppOpSet);
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

        newParticipant = new Participant((XmppChatMember) chatRoomMember);

        participants.add(newParticipant);

        logger.info("Added participant for: " + address);

        // Invite peer takes time because of channel allocation, so schedule
        // this on separate thread.
        // FIXME:
        // Because channel allocation is done on separate thread it is
        // possible that participant will leave while channels are being
        // allocated. In "on participant left" event channel ids will not yet be
        // assigned, so we won't expire them. We are letting those channels to
        // leek and get expired automatically on the bridge.
        FocusBundleActivator.getSharedThreadPool().submit(new Runnable()
        {
            @Override
            public void run()
            {
                discoverFeaturesAndInvite(newParticipant, address, startMuted);
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

        try
        {
            List<ContentPacketExtension> offer
                = createOffer(newParticipant);

            jingle.initiateSession(
                    newParticipant.hasBundleSupport(), address, offer, this,
                    startMuted);
        }
        catch (OperationFailedException e)
        {
            //FIXME: retry ? sometimes it's just timeout
            logger.error("Failed to invite " + address, e);

            // Notify users about bridge is down event
            if (BRIDGE_FAILURE_ERR_CODE == e.getErrorCode())
            {
                meetTools.sendPresenceExtension(
                    chatRoom, new BridgeIsDownPacketExt());
            }
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
     *         given <tt>peer</tt>.
     *
     * @throws OperationFailedException if we have failed to allocate channels
     *         using existing bridge and we can not switch to another bridge.
     */
    private ColibriConferenceIQ allocateChannels(
            Participant peer, List<ContentPacketExtension> contents)
        throws OperationFailedException
    {
        // Allocate by trying all bridges on prioritized list
        BridgeSelector bridgeSelector = services.getBridgeSelector();

        Iterator<String> bridgesIterator
            = bridgeSelector.getPrioritizedBridgesList().iterator();

        // Set initial bridge if we haven't used any yet
        if (StringUtils.isNullOrEmpty(colibriConference.getJitsiVideobridge()))
        {
            if (!bridgesIterator.hasNext())
            {
                throw new OperationFailedException(
                    "Failed to allocate channels - no bridge configured",
                    OperationFailedException.GENERAL_ERROR);
            }

            colibriConference.setJitsiVideobridge(
                bridgesIterator.next());
        }

        while (true)
        {
            try
            {
                logger.info(
                    "Using " + colibriConference.getJitsiVideobridge()
                        + " to allocate channels for: "
                        + peer.getChatMember().getContactAddress());

                ColibriConferenceIQ peerChannels
                    = colibriConference.createColibriChannels(
                            peer.hasBundleSupport(),
                            peer.getEndpointId(),
                            true, contents);

                bridgeSelector.updateBridgeOperationalStatus(
                    colibriConference.getJitsiVideobridge(), true);

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
                                    colibriConference.getJitsiVideobridge()));
                    }
                }
                return peerChannels;
            }
            catch(OperationFailedException exc)
            {
                String faultyBridge = colibriConference.getJitsiVideobridge();

                logger.error(
                    "Failed to allocate channels using bridge: "
                        + colibriConference.getJitsiVideobridge(), exc);

                bridgeSelector.updateBridgeOperationalStatus(
                    faultyBridge, false);

                // Check if the conference is in progress
                if (!StringUtils.isNullOrEmpty(
                        colibriConference.getConferenceId()))
                {
                    // Restart
                    logger.error("Bridge failure - stopping the conference");
                    stop();
                }

                // Try next bridge
                String nextBridge = null;
                if (bridgesIterator.hasNext())
                    nextBridge = bridgesIterator.next();

                // Is it the same which has just failed ?
                // (we do not always call iterator.next() at the beginning)
                if (faultyBridge.equals(nextBridge))
                    nextBridge = null;

                if (nextBridge != null)
                {
                    colibriConference.setJitsiVideobridge(nextBridge);
                }
                else
                {
                    // No more bridges to try
                    throw new OperationFailedException(
                        "Failed to allocate channels - all bridges are faulty",
                        BRIDGE_FAILURE_ERR_CODE);
                }
            }
        }
    }

    /**
     * Creates Jingle offer for given {@link Participant}.
     *
     * @param peer the participant for whom Jingle offer will be created.
     *
     * @return the list of contents describing conference Jingle offer.
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

        if (peer.hasAudioSupport())
        {
            contents.add(
                JingleOfferFactory.createContentForMedia(
                    MediaType.AUDIO, disableIce));
        }

        if (peer.hasVideoSupport())
        {
            contents.add(
                JingleOfferFactory.createContentForMedia(
                    MediaType.VIDEO, disableIce));
        }

        // Is SCTP enabled ?
        boolean openSctp = config == null || config.openSctp() == null
                ? true : config.openSctp();

        if (openSctp && peer.hasSctpSupport())
        {
            contents.add(
                JingleOfferFactory.createContentForMedia(
                    MediaType.DATA, disableIce));
        }

        boolean useBundle = peer.hasBundleSupport();

        ColibriConferenceIQ peerChannels = allocateChannels(peer, contents);

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

                // Include all peers SSRCs
                List<SourcePacketExtension> mediaSources
                    = getAllSSRCs(cpe.getName());

                for (SourcePacketExtension ssrc : mediaSources)
                {
                    try
                    {
                        rtpDescPe.addChildExtension(
                            ssrc.copy());
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

                // Copy SSRC sent from the bridge(only the first one)
                for (ColibriConferenceIQ.Channel channel
                        : colibriContent.getChannels())
                {
                    SourcePacketExtension ssrcPe
                        = channel.getSources().size() > 0
                            ? channel.getSources().get(0) : null;
                    if (ssrcPe != null)
                    {
                        try
                        {
                            String contentName = colibriContent.getName();
                            SourcePacketExtension ssrcCopy = ssrcPe.copy();

                            // FIXME: not all parameters are used currently
                            ssrcCopy.addParameter(
                                new ParameterPacketExtension(
                                    "cname","mixed"));
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
                                    "mslabel","mixedmslabel"));

                            rtpDescPe.addChildExtension(ssrcCopy);
                        }
                        catch (Exception e)
                        {
                            logger.error("Copy SSRC error", e);
                        }
                    }
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
            colibriConference.expireConference();
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
                //jingle.terminateSession(session.getJingleSession());
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
     * Stops the conference, disposes colibri channels and releases all
     * resources used by the focus.
     */
    void stop()
    {
        if (!started)
            return;

        started = false;

        disposeConference();

        leaveTheRoom();

        jingle.terminateHandlersSessions(this);

        listener.conferenceEnded(this);
    }

    @Override
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        logger.info("Reg state changed: " + evt);

        if (RegistrationState.REGISTERED.equals(evt.getNewState()))
        {

            if (chatRoom == null)
            {
                joinTheRoom();
            }
            // We're not interested in event other that REGISTERED
            protocolProviderHandler.removeRegistrationListener(this);
        }
    }

    private Participant findParticipantForJingleSession(
            JingleSession jingleSession)
    {
        for (Participant participant : participants)
        {
            if (participant.getChatMember()
                .getContactAddress().equals(jingleSession.getAddress()))
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

        participant.setJingleSession(peerJingleSession);

        participant.addSSRCsFromContent(answer);

        participant.addSSRCGroupsFromContent(answer);

        // Update SSRC groups
        colibriConference.updateSourcesInfo(
            participant.getSSRCsCopy(),
            participant.getSSRCGroupsCopy(),
            participant.getColibriChannelsInfo());

        logger.info("Got SSRCs from " + peerJingleSession.getAddress());

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

        participant.addSSRCsFromContent(contents);

        participant.addSSRCGroupsFromContent(contents);

        MediaSSRCMap ssrcsToAdd
            = MediaSSRCMap.getSSRCsFromContent(contents);

        MediaSSRCGroupMap ssrcGroupsToAdd
            = MediaSSRCGroupMap.getSSRCGroupsForContents(contents);

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

        sourcePeer.removeSSRCs(ssrcsToRemove);

        sourcePeer.removeSSRCGroups(ssrcGroupsToRemove);

        // Updates SSRC Groups on the bridge
        colibriConference.updateSourcesInfo(
            sourcePeer.getSSRCsCopy(),
            sourcePeer.getSSRCGroupsCopy(),
            sourcePeer.getColibriChannelsInfo());

        logger.info("Remove SSRC " + sourceJingleSession.getAddress());

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
     * @return new recording state(unchanged if modify attempt has failed).
     */
    public boolean modifyRecordingState(
            String from, String token, boolean state, String path)
    {
        ChatRoomMember member = findMember(from);
        if (member == null)
        {
            logger.error("No member found for address: " + from);
            return false;
        }
        if (ChatRoomMemberRole.MODERATOR.compareTo(member.getRole()) < 0)
        {
            logger.info("Recording - request denied, not a moderator: " + from);
            return false;
        }

        Recorder recorder = getRecorder();
        if (recorder == null)
        {
            return false;
        }

        boolean isTokenCorrect
            = recorder.setRecording(from, token, state, path);
        if (!isTokenCorrect)
        {
            logger.info(
                "Incorrect recording token received ! Session: "
                    + chatRoom.getName());
        }

        return recorder.isRecording();
    }

    private ChatRoomMember findMember(String from)
    {
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (member.getContactAddress().equals(from))
            {
                return member;
            }
        }
        return null;
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
     * Gets the full real (as opposed to the room JID in a MUC) JID of the
     * focus.
     * @return the full real JID of the focus.
     */
    private String getFocusRealJid()
    {
        return getXmppProvider().getAccountID().getAccountAddress();
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
}
