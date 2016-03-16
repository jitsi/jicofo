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
package org.jitsi.jicofo.recording.jibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.recording.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

/**
 * Handles conference recording through Jibri.
 * Waits for updates from {@link JibriDetector} about recorder instance
 * availability and publishes that information in Jicofo's MUC presence.
 * Handles incoming Jibri IQs coming from conference moderator to
 * start/stop the recording.
 *
 * @author Pawel Domas
 */
public class JibriRecorder
    extends Recorder
    implements JibriListener
{
    /**
     * The logger.
     */
    static private final Logger logger = Logger.getLogger(JibriRecorder.class);

    /**
     * Recorded <tt>JitsiMeetConference</tt>.
     */
    private final JitsiMeetConference conference;

    /**
     * Meet tools instance used to inject packet extensions to Jicofo's MUC
     * presence.
     */
    private final OperationSetJitsiMeetTools meetTools;

    /**
     * Jibri detector which notifies about Jibri status changes.
     */
    private final JibriDetector jibriDetector;

    /**
     * Current Jibri recording status.
     */
    private JibriIq.Status jibriStatus = JibriIq.Status.UNDEFINED;

    /**
     * Creates new instance of <tt>JibriRecorder</tt>.
     * @param conference <tt>JitsiMeetConference</tt> to be recorded by new
     *        instance.
     * @param xmpp XMPP operation set which wil be used to send XMPP queries.
     */
    public JibriRecorder(JitsiMeetConference conference,
                         OperationSetDirectSmackXmpp xmpp)
    {
        super(null, xmpp);

        this.conference = conference;

        ProtocolProviderService protocolService = conference.getXmppProvider();

        meetTools
            = protocolService.getOperationSet(OperationSetJitsiMeetTools.class);

        jibriDetector = conference.getServices().getJibriDetector();
    }

    /**
     * Starts listening for Jibri updates and initializes Jicofo presence.
     *
     * {@inheritDoc}
     */
    @Override
    public void init()
    {
        super.init();

        jibriDetector.addJibriListener(this);

        setJibriStatus(
                jibriDetector.selectJibri() != null ?
                    JibriIq.Status.OFF : JibriIq.Status.UNDEFINED);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose()
    {
        XMPPError error = sendStopIQ();
        if (error != null)
        {
            logger.error("Error when sending stop request: " + error.toXML());
        }

        jibriDetector.removeJibriListener(this);

        super.dispose();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecording()
    {
        return JibriIq.Status.ON.equals(jibriStatus);
    }

    /**
     * Not implemented in Jibri Recorder
     *
     * {@inheritDoc}
     */
    @Override
    public boolean setRecording(String from, String token,
                                ColibriConferenceIQ.Recording.State doRecord,
                                String path)
    {
        // NOT USED

        return false;
    }

    /**
     * Accepts only {@link JibriIq}
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        return packet instanceof JibriIq;
    }

    /**
     * <tt>JibriIq</tt> processing.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void processPacket(Packet packet)
    {
        JibriIq iq = (JibriIq) packet;

        String from = iq.getFrom();

        if (logger.isDebugEnabled())
            logger.debug("Got Jibri packet: " + packet.toXML());

        if (recorderComponentJid != null &&
            (from.equals(recorderComponentJid) ||

            (from +"/").startsWith(recorderComponentJid)))
        {
            processJibriIqFromJibri(iq);
        }
        else
        {
            String roomName = MucUtil.extractRoomNameFromMucJid(from);
            if (roomName == null)
            {
                return;
            }

            if (!conference.getRoomName().equals(roomName))
            {
                logger.debug(
                        "Ignored packet from: " + roomName
                            + ", my room: " + conference.getRoomName()
                            + " p: " + packet.toXML());
                return;
            }

            XmppChatMember chatMember
                = conference.getChatRoom().findChatMember(from);
            if (chatMember == null)
            {
                logger.error("ERROR chat member not found for: " + from);
                return;
            }

            processJibriIqFromMeet(iq, chatMember);
        }
    }

    private void processJibriIqFromMeet(JibriIq iq, XmppChatMember sender)
    {
        JibriIq.Action action = iq.getAction();

        if (JibriIq.Action.UNDEFINED.equals(action))
            return;

        String senderMucJid = sender.getContactAddress();

        logger.debug(
                "Jibri request from " + senderMucJid + " iq: " + iq.toXML());

        // start ?
        if (JibriIq.Action.START.equals(action) &&
            JibriIq.Status.OFF.equals(jibriStatus) &&
            recorderComponentJid == null)
        {
            if (!verifyModeratorRole(iq))
            {
                logger.warn(
                        "Ignored Jibri request from non-moderator: "
                            + senderMucJid);
                return;
            }

            // Check if we have Jibri available
            String jibriJid = jibriDetector.selectJibri();
            if (jibriJid == null)
            {
                sendErrorResponse(
                    iq, XMPPError.Condition.service_unavailable, null);
                return;
            }

            JibriIq startIq = new JibriIq();
            startIq.setTo(jibriJid);
            startIq.setType(IQ.Type.SET);
            startIq.setAction(JibriIq.Action.START);

            startIq.setStreamId(iq.getStreamId());
            startIq.setUrl(iq.getUrl());

            logger.debug("Starting Jibri recording: " + startIq.toXML());

            IQ startReply
                = (IQ) xmpp.getXmppConnection()
                        .sendPacketAndGetReply(startIq);

            logger.debug("Start response: " + startReply.toXML());

            if (startReply == null)
            {
                sendErrorResponse(
                        iq, XMPPError.Condition.request_timeout, null);
                return;
            }

            if (IQ.Type.RESULT.equals(startReply.getType()))
            {
                recorderComponentJid = jibriJid;

                setJibriStatus(JibriIq.Status.PENDING);

                sendResultResponse(iq);
                return;
            }
            else
            {
                XMPPError error = startReply.getError();
                if (error == null)
                {
                    error = new XMPPError(
                            XMPPError.Condition.interna_server_error);
                }
                sendPacket(IQ.createErrorResponse(iq, error));
                return;
            }
        }
        // stop ?
        else if (JibriIq.Action.STOP.equals(action) &&
            recorderComponentJid != null &&
            (JibriIq.Status.ON.equals(jibriStatus) ||
             JibriIq.Status.PENDING.equals(jibriStatus)))
        {
            if (!verifyModeratorRole(iq))
                return;

            XMPPError error = sendStopIQ();
            sendPacket(
                error == null
                    ? IQ.createResultIQ(iq)
                    : IQ.createErrorResponse(iq, error));
            return;
        }

        logger.warn(
            "Discarded: " + iq.toXML() + " - nothing to be done, " +
            "recording status:" + jibriStatus);

        // Bad request
        sendErrorResponse(
            iq, XMPPError.Condition.bad_request,
            "Unable to handle: '" + action
                + "' in state: '" + jibriStatus + "'");
    }

    private boolean verifyModeratorRole(JibriIq iq)
    {
        String from = iq.getFrom();
        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            sendErrorResponse(iq, XMPPError.Condition.forbidden, null);
            return false;
        }

        if (ChatRoomMemberRole.MODERATOR.compareTo(role) < 0)
        {
            // Moderator permission is required
            sendErrorResponse(iq, XMPPError.Condition.not_allowed, null);
            return false;
        }
        return true;
    }

    private void sendPacket(Packet packet)
    {
        xmpp.getXmppConnection().sendPacket(packet);
    }

    private void sendResultResponse(IQ request)
    {
        sendPacket(
            IQ.createResultIQ(request));
    }

    private void sendErrorResponse(IQ request,
                                   XMPPError.Condition condition,
                                   String msg)
    {
        sendPacket(
            IQ.createErrorResponse(
                request,
                new XMPPError(condition, msg)
            )
        );
    }

    private void processJibriIqFromJibri(JibriIq iq)
    {
        if (IQ.Type.RESULT.equals(iq.getType()))
            return;

        // We have something from Jibri - let's update recording status
        JibriIq.Status status = iq.getStatus();
        if (!JibriIq.Status.UNDEFINED.equals(status))
        {
            logger.info("Updating status from Jibri: " + iq.toXML()
                + " for " + conference.getRoomName());

            setJibriStatus(status);
        }

        sendPacket(IQ.createResultIQ(iq));
    }

    synchronized private void setJibriStatus(JibriIq.Status newStatus)
    {
        jibriStatus = newStatus;

        RecordingStatus recordingStatus = new RecordingStatus();

        recordingStatus.setStatus(newStatus);

        logger.info(
            "Publish new Jibri status: " + recordingStatus.toXML() +
            " in: " + conference.getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            meetTools.sendPresenceExtension(
                chatRoom2,
                recordingStatus);
        }
    }

    // Send stop IQ when recording initiator leaves the room
    private XMPPError sendStopIQ()
    {
        if (recorderComponentJid == null)
            return null;

        JibriIq stopRequest = new JibriIq();

        stopRequest.setType(IQ.Type.SET);
        stopRequest.setTo(recorderComponentJid);
        stopRequest.setAction(JibriIq.Action.STOP);

        logger.debug("Trying to stop: " + stopRequest.toXML());

        IQ stopReply
            = (IQ) xmpp.getXmppConnection()
                    .sendPacketAndGetReply(stopRequest);

        logger.debug(
                "Stop response: "
                    + (stopReply != null ? stopReply.toXML() : "timeout"));

        if (stopReply == null)
        {
            return new XMPPError(XMPPError.Condition.request_timeout, null);
        }

        if (IQ.Type.RESULT.equals(stopReply.getType()))
        {
            setJibriStatus(JibriIq.Status.OFF);

            recorderComponentJid = null;
            return null;
        }
        else
        {
            XMPPError error = stopReply.getError();
            if (error == null)
            {
                error
                    = new XMPPError(XMPPError.Condition.interna_server_error);
            }
            return error;
        }
    }

    @Override
    public void onJibriStatusChanged(String jibriJid, boolean idle)
    {
        // If we're recording then we listen to status coming from our Jibri
        // through IQs
        if (recorderComponentJid != null)
            return;

        String jibri = jibriDetector.selectJibri();
        if (jibri != null)
        {
            logger.info("Recording enabled");
            setJibriStatus(JibriIq.Status.OFF);
        }
        else
        {
            logger.info("Recording disabled - all jibris are busy");
            setJibriStatus(JibriIq.Status.UNDEFINED);
        }
    }

    @Override
    public void onJibriOffline(String jibriJid)
    {
        if (jibriJid.equals(recorderComponentJid))
        {
            logger.warn("Our recorder went offline: " + recorderComponentJid);
            recorderComponentJid = null;
        }

        String jibri = jibriDetector.selectJibri();
        if (jibri == null && recorderComponentJid == null)
        {
            setJibriStatus(JibriIq.Status.UNDEFINED);
        }
    }
}
