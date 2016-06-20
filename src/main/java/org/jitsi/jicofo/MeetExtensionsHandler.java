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

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ.Recording.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.rayo.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.log.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
import org.jitsi.eventadmin.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.Message; //disambiguation
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smackx.packet.*;

/**
 * Class handles various Jitsi Meet extensions IQs like {@link MuteIq} and
 * Colibri for recording.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class MeetExtensionsHandler
    implements PacketFilter,
               PacketListener
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(MeetExtensionsHandler.class);

    /**
     * <tt>FocusManager</tt> instance for accessing info about all active
     * conferences.
     */
    private final FocusManager focusManager;

    /**
     * Operation set that provider XMPP connection.
     */
    private OperationSetDirectSmackXmpp smackXmpp;

    /**
     * Creates new instance of {@link MeetExtensionsHandler}.
     * @param focusManager <tt>FocusManager</tt> that will be used by new
     *                     instance to access active conferences and focus
     *                     XMPP connection.
     */
    public MeetExtensionsHandler(FocusManager focusManager)
    {
        this.focusManager = focusManager;

        MuteIqProvider muteIqProvider = new MuteIqProvider();
        muteIqProvider.registerMuteIqProvider(
            ProviderManager.getInstance());

        RayoIqProvider rayoIqProvider = new RayoIqProvider();
        rayoIqProvider.registerRayoIQs(
                ProviderManager.getInstance());

        StartMutedProvider startMutedProvider = new StartMutedProvider();
        startMutedProvider.registerStartMutedProvider(
            ProviderManager.getInstance());
    }

    /**
     * Initializes this instance and bind packet listeners.
     */
    public void init()
    {
        this.smackXmpp
            = focusManager.getOperationSet(
                    OperationSetDirectSmackXmpp.class);

        smackXmpp.addPacketHandler(this, this);
    }

    /**
     * Disposes this instance and stop listening for extensions packets.
     */
    public void dispose()
    {
        if (smackXmpp != null)
        {
            smackXmpp.removePacketHandler(this);
            smackXmpp = null;
        }
    }

    @Override
    public boolean accept(Packet packet)
    {
        return acceptMuteIq(packet)
                || acceptColibriIQ(packet)
                || acceptRayoIq(packet)
                || acceptMessage(packet)
                || acceptPresence(packet);
    }

    @Override
    public void processPacket(Packet packet)
    {
        if (smackXmpp == null)
        {
            logger.error("Not initialized");
            return;
        }

        if (packet instanceof ColibriConferenceIQ)
        {
            handleColibriIq((ColibriConferenceIQ) packet);
        }
        else if (packet instanceof MuteIq)
        {
            handleMuteIq((MuteIq) packet);
        }
        else if (packet instanceof RayoIqProvider.DialIq)
        {
            handleRayoIQ((RayoIqProvider.DialIq) packet);
        }
        else if (packet instanceof Message)
        {
            handleMessage((Message) packet);
        }
        else if (packet instanceof Presence)
        {
            handlePresence((Presence) packet);
        }
        else
        {
            logger.error("Unexpected packet: " + packet.toXML());
        }
    }

    private boolean acceptColibriIQ(Packet packet)
    {
        return packet instanceof ColibriConferenceIQ
            // And with recording element
            && ((ColibriConferenceIQ)packet).getRecording() != null;
    }

    private void handleColibriIq(ColibriConferenceIQ colibriIQ)
    {
        ColibriConferenceIQ.Recording recording = colibriIQ.getRecording();
        String from = colibriIQ.getFrom();
        JitsiMeetConference conference
            = getConferenceForMucJid(colibriIQ.getFrom());
        if (conference == null)
        {
            logger.debug("Room not found for JID: " + from);
            return;
        }

        JitsiMeetRecording recordingHandler = conference.getRecording();
        if (recordingHandler == null)
        {
            logger.error(
                    "JitsiMeetRecording is null for iq: " + colibriIQ.toXML());

            // Internal server error
            smackXmpp.getXmppConnection().sendPacket(
                    IQ.createErrorResponse(
                            colibriIQ,
                            new XMPPError(
                                    XMPPError.Condition.interna_server_error)));
            return;
        }

        State recordingState =
            recordingHandler.modifyRecordingState(
                    colibriIQ.getFrom(),
                    recording.getToken(),
                    recording.getState(),
                    recording.getDirectory(),
                    colibriIQ.getTo());

        ColibriConferenceIQ response = new ColibriConferenceIQ();

        response.setType(IQ.Type.RESULT);
        response.setPacketID(colibriIQ.getPacketID());
        response.setTo(colibriIQ.getFrom());
        response.setFrom(colibriIQ.getTo());
        response.setName(colibriIQ.getName());

        response.setRecording(
            new ColibriConferenceIQ.Recording(recordingState));

        smackXmpp.getXmppConnection().sendPacket(response);
    }

    private boolean acceptMuteIq(Packet packet)
    {
        return packet instanceof MuteIq;
    }

    private JitsiMeetConference getConferenceForMucJid(String mucJid)
    {
        String roomName = MucUtil.extractRoomNameFromMucJid(mucJid);
        if (roomName == null)
        {
            return null;
        }
        return focusManager.getConference(roomName);
    }

    private void handleMuteIq(MuteIq muteIq)
    {
        Boolean doMute = muteIq.getMute();
        String jid = muteIq.getJid();

        if (doMute == null || StringUtils.isNullOrEmpty(jid))
            return;

        String from = muteIq.getFrom();
        JitsiMeetConference conference = getConferenceForMucJid(from);
        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return;
        }

        IQ result;

        if (conference.handleMuteRequest(muteIq.getFrom(), jid, doMute))
        {
            result = IQ.createResultIQ(muteIq);

            if (!muteIq.getFrom().equals(jid))
            {
                MuteIq muteStatusUpdate = new MuteIq();
                muteStatusUpdate.setType(IQ.Type.SET);
                muteStatusUpdate.setTo(jid);

                muteStatusUpdate.setMute(doMute);

                smackXmpp.getXmppConnection().sendPacket(muteStatusUpdate);
            }
        }
        else
        {
            result = IQ.createErrorResponse(
                muteIq,
                new XMPPError(XMPPError.Condition.interna_server_error));
        }

        smackXmpp.getXmppConnection().sendPacket(result);
    }

    private boolean acceptRayoIq(Packet p)
    {
        return p instanceof RayoIqProvider.DialIq;
    }

    private void handleRayoIQ(RayoIqProvider.DialIq dialIq)
    {
        String from = dialIq.getFrom();

        JitsiMeetConference conference = getConferenceForMucJid(from);

        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return;
        }

        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            IQ error = createErrorResponse(
                dialIq, new XMPPError(XMPPError.Condition.forbidden));

            smackXmpp.getXmppConnection().sendPacket(error);

            return;
        }

        if (ChatRoomMemberRole.MODERATOR.compareTo(role) < 0)
        {
            // Moderator permission is required
            IQ error = createErrorResponse(
                dialIq, new XMPPError(XMPPError.Condition.not_allowed));

            smackXmpp.getXmppConnection().sendPacket(error);

            return;
        }

        // Check if Jigasi is available
        String jigasiJid = conference.getServices().getSipGateway();

        if (StringUtils.isNullOrEmpty(jigasiJid))
        {
            // Not available
            IQ error = createErrorResponse(
                dialIq, new XMPPError(XMPPError.Condition.service_unavailable));

            smackXmpp.getXmppConnection().sendPacket(error);

            return;
        }

        // Redirect original request to Jigasi component
        String originalPacketId = dialIq.getPacketID();

        dialIq.setFrom(null);
        dialIq.setTo(jigasiJid);
        dialIq.setPacketID(IQ.nextID());

        IQ reply
            = (IQ) smackXmpp.getXmppConnection().sendPacketAndGetReply(dialIq);

        if (reply != null)
        {
            // Send Jigasi response back to the client
            reply.setFrom(null);
            reply.setTo(from);
            reply.setPacketID(originalPacketId);
        }
        else
        {
            reply = createErrorResponse(
                    dialIq,
                    new XMPPError(XMPPError.Condition.remote_server_timeout));
        }

        smackXmpp.getXmppConnection().sendPacket(reply);
    }

    private boolean acceptMessage(Packet packet)
    {
        if (packet != null && packet instanceof Message)
        {
            for (PacketExtension pe : packet.getExtensions())
                if (pe instanceof LogPacketExtension)
                    return true;
        }
        return false;
    }

    /**
     * Handles "message" stanzas.
     */
    private void handleMessage(Message message)
    {
        for (PacketExtension ext : message.getExtensions())
            if (ext instanceof LogPacketExtension)
                handleLogRequest((LogPacketExtension) ext, message.getFrom());
    }

    /**
     * Handles XEP-0337 "log" extensions.
     */
    private void handleLogRequest(LogPacketExtension log, String jid)
    {
        JitsiMeetConference conference = getConferenceForMucJid(jid);

        if (conference == null)
        {
            logger.debug("Room not found for JID: " + jid);
            return;
        }

        Participant participant = conference.findParticipantForRoomJid(jid);

        if (participant == null)
        {
            logger.info("Ignoring log request from an unknown JID: " + jid);
            return;
        }

        EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();

        if (eventAdmin == null)
            return;

        if (LogUtil.LOG_ID_PC_STATS.equals(log.getID()))
        {
            String content = LogUtil.getContent(log);

            if (content != null)
            {
                ColibriConference colibriConference
                    = conference.getColibriConference();

                if (colibriConference != null)
                {
                    Event event
                        = EventFactory.peerConnectionStats(
                                colibriConference.getConferenceId(),
                                participant.getEndpointId(),
                                content);

                    if (event != null)
                        eventAdmin.sendEvent(event);
                }
                else
                {
                    logger.warn(
                            "Unhandled log request"
                                + " - no valid Colibri conference");
                }
            }
        }
        else if (logger.isInfoEnabled())
        {
            logger.info(
                    "Ignoring log request with an unknown ID:" + log.getID());
        }
    }

    private boolean acceptPresence(Packet packet)
    {
        return packet instanceof Presence;
    }

    /**
     * Handles presence stanzas
     * @param presence
     */
    private void handlePresence(Presence presence)
    {
        // unavailable is sent when user leaves the room
        if (!presence.isAvailable())
        {
            return;
        }

        String from = presence.getFrom();
        JitsiMeetConference conference = getConferenceForMucJid(from);

        if (conference == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Room not found for JID: " + from);
            }
            return;
        }

        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role != null && role.compareTo(ChatRoomMemberRole.MODERATOR) < 0)
        {
            StartMutedPacketExtension ext
                = (StartMutedPacketExtension)
                    presence.getExtension(
                            StartMutedPacketExtension.ELEMENT_NAME,
                            StartMutedPacketExtension.NAMESPACE);

            if (ext != null)
            {
                boolean[] startMuted
                    = { ext.getAudioMuted(), ext.getVideoMuted() };

                conference.setStartMuted(startMuted);
            }
        }

        Participant participant = conference.findParticipantForRoomJid(from);
        ColibriConference colibriConference = conference.getColibriConference();

        if (participant != null && colibriConference != null)
        {
            // Check if this conference is valid
            String conferenceId = colibriConference.getConferenceId();
            if (StringUtils.isNullOrEmpty(conferenceId))
            {
                logger.error(
                        "Unable to send DisplayNameChanged event"
                            + " - no conference id");
                return;
            }

            // Check for changes to the display name
            String oldDisplayName = participant.getDisplayName();
            String newDisplayName = null;
            for (PacketExtension pe : presence.getExtensions())
            {
                if (pe instanceof Nick)
                {
                    newDisplayName = ((Nick) pe).getName();
                    break;
                }
            }

            if (!Objects.equals(oldDisplayName, newDisplayName))
            {
                participant.setDisplayName(newDisplayName);

                EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
                if (eventAdmin != null)
                {
                    // Prevent NPE when adding to event hashtable
                    if (newDisplayName == null)
                    {
                        newDisplayName = "";
                    }
                    eventAdmin.sendEvent(
                            EventFactory.endpointDisplayNameChanged(
                                    conferenceId,
                                    participant.getEndpointId(),
                                    newDisplayName));
                }
            }
        }
    }

    /**
     * FIXME: replace with IQ.createErrorResponse
     * Prosody does not allow to include request body in error
     * response. Replace this method with IQ.createErrorResponse once fixed.
     */
    private IQ createErrorResponse(IQ request, XMPPError error)
    {
        IQ.Type requestType = request.getType();
        if (!(requestType == IQ.Type.GET || requestType == IQ.Type.SET))
        {
            throw new IllegalArgumentException(
                    "IQ must be of type 'set' or 'get'. Original IQ: "
                        + request.toXML());
        }

        final IQ result
            = new IQ()
            {
                @Override
                public String getChildElementXML()
                {
                    return "";
                }
            };
        result.setType(IQ.Type.ERROR);
        result.setPacketID(request.getPacketID());
        result.setFrom(request.getTo());
        result.setTo(request.getFrom());
        result.setError(error);
        return result;
    }
}
