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
import net.java.sip.communicator.util.*;

import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.recording.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.concurrent.*;

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
     * The global config used by this instance.
     */
    private final JitsiMeetGlobalConfig globalConfig;

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
     * Executor service for used to schedule pending timeout tasks.
     */
    private final ScheduledExecutorService scheduledExecutor;

    /**
     * Reference to scheduled {@link PendingStatusTimeout}
     */
    private ScheduledFuture<?> pendingTimeoutTask;

    /**
     * Creates new instance of <tt>JibriRecorder</tt>.
     * @param conference <tt>JitsiMeetConference</tt> to be recorded by new
     *        instance.
     * @param xmpp XMPP operation set which wil be used to send XMPP queries.
     * @param scheduledExecutor the executor service used by this instance
     * @param globalConfig the global config that provides some values required
     *                     by <tt>JibriRecorder</tt> to work.
     */
    public JibriRecorder(JitsiMeetConference         conference,
                         OperationSetDirectSmackXmpp xmpp,
                         ScheduledExecutorService    scheduledExecutor,
                         JitsiMeetGlobalConfig       globalConfig)
    {
        super(null, xmpp);

        this.conference = conference;
        this.scheduledExecutor = scheduledExecutor;
        this.globalConfig = globalConfig;

        Assert.notNull(conference, "conference");
        Assert.notNull(globalConfig, "globalConfig");
        Assert.notNull(scheduledExecutor, "scheduledExecutor");

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

        updateJibriAvailability();
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

        // verifyModeratorRole sends 'not_allowed' error on false
        if (!verifyModeratorRole(iq))
        {
            logger.warn(
                "Ignored Jibri request from non-moderator: "
                        + senderMucJid);
            return;
        }

        // start ?
        if (JibriIq.Action.START.equals(action) &&
            JibriIq.Status.OFF.equals(jibriStatus) &&
            recorderComponentJid == null)
        {
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

            // Insert name of the room into Jibri START IQ
            String roomName = MucUtil.extractName(senderMucJid);
            startIq.setRoom(roomName);

            logger.debug("Starting Jibri recording: " + startIq.toXML());

            IQ startReply
                = (IQ) xmpp.getXmppConnection()
                        .sendPacketAndGetReply(startIq);

            logger.debug(
                    "Start response: " + IQUtils.responseToXML(startReply));

            if (startReply == null)
            {
                sendErrorResponse(
                        iq, XMPPError.Condition.request_timeout, null);
                return;
            }

            if (IQ.Type.RESULT.equals(startReply.getType()))
            {
                // Store Jibri JID
                recorderComponentJid = jibriJid;
                // We're now in PENDING state(waiting for Jibri ON update)
                setJibriStatus(JibriIq.Status.PENDING);
                // We will not wait forever for the Jibri to start
                schedulePendingTimeout();
                // ACK the original request
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

    /**
     * Method schedules {@link PendingStatusTimeout} which will clear recording
     * state after {@link JitsiMeetGlobalConfig#getJibriPendingTimeout()}.
     */
    private void schedulePendingTimeout()
    {
        if (pendingTimeoutTask != null)
        {
            logger.error("Pending timeout scheduled already!?");
            return;
        }

        int pendingTimeout = globalConfig.getJibriPendingTimeout();
        if (pendingTimeout > 0)
        {
            pendingTimeoutTask
                = scheduledExecutor.schedule(
                        new PendingStatusTimeout(),
                        pendingTimeout,
                        TimeUnit.SECONDS);
        }
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
            String roomName = conference.getRoomName();

            logger.info("Updating status from Jibri: " + iq.toXML()
                + " for " + roomName);

            // We stop either on "off" or on "failed"
            if ((JibriIq.Status.OFF.equals(status) ||
                JibriIq.Status.FAILED.equals(status))
                && recorderComponentJid != null/* This means we're recording */)
            {
                logger.info("Recording stopped for: " + roomName);
                // Make sure that there is XMPPError for ERROR status
                XMPPError error = iq.getError();
                if (JibriIq.Status.FAILED.equals(status) && error == null)
                {
                    error = new XMPPError(
                            XMPPError.Condition.interna_server_error,
                            "Unknown error");
                }
                recordingStopped(error);
            }
            else
            {
                setJibriStatus(status);
            }
        }

        sendPacket(IQ.createResultIQ(iq));
    }

    private void setJibriStatus(JibriIq.Status newStatus)
    {
        setJibriStatus(newStatus, null);
    }

    private void setJibriStatus(JibriIq.Status newStatus, XMPPError error)
    {
        jibriStatus = newStatus;

        // Clear "pending" status timeout if we enter state other than "pending"
        if (pendingTimeoutTask != null
                && !JibriIq.Status.PENDING.equals(newStatus))
        {
            pendingTimeoutTask.cancel(false);
            pendingTimeoutTask = null;
        }

        RecordingStatus recordingStatus = new RecordingStatus();

        recordingStatus.setStatus(newStatus);

        recordingStatus.setError(error);

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

        logger.debug("Stop response: " + IQUtils.responseToXML(stopReply));

        if (stopReply == null)
        {
            return new XMPPError(XMPPError.Condition.request_timeout, null);
        }

        if (IQ.Type.RESULT.equals(stopReply.getType()))
        {
            logger.info(
                    "Recording stopped on user request in "
                        + conference.getRoomName());
            recordingStopped(null);
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

    /**
     * Methods clears {@link #recorderComponentJid} which means we're no longer
     * recording nor in contact with any Jibri instance.
     * Refreshes recording status in the room based on Jibri availability.
     *
     * @param error if the recording stopped because of an error it should be
     * passed as an argument here which will result in stopping with
     * the {@link JibriIq.Status#FAILED} status passed to the application.
     */
    private void recordingStopped(XMPPError error)
    {
        recorderComponentJid = null;
        // First we'll send an error and then follow with availability status
        if (error != null)
        {
            setJibriStatus(JibriIq.Status.FAILED, error);
        }
        // Update based on availability
        updateJibriAvailability();
    }

    /**
     * The method is supposed to update Jibri availability status to OFF if we
     * have any Jibris available or to UNDEFINED if there are no any.
     */
    private void updateJibriAvailability()
    {
        if (jibriDetector.selectJibri() != null)
        {
            setJibriStatus(JibriIq.Status.OFF);
        }
        else if (jibriDetector.isAnyJibriConnected())
        {
            setJibriStatus(JibriIq.Status.BUSY);
        }
        else
        {
            setJibriStatus(JibriIq.Status.UNDEFINED);
        }
    }

    @Override
    synchronized public void onJibriStatusChanged(String jibriJid, boolean idle)
    {
        // We listen to status updates coming from our Jibri through IQs
        // if recording is in progress(recorder JID is not null),
        // otherwise it is fine to update Jibri recording availability here
        if (recorderComponentJid == null)
        {
            updateJibriAvailability();
        }
    }

    @Override
    synchronized public void onJibriOffline(String jibriJid)
    {
        if (jibriJid.equals(recorderComponentJid))
        {
            logger.warn("Our recorder went offline: " + recorderComponentJid);

            recordingStopped(null);
        }
    }

    /**
     * Task scheduled after we have received RESULT response from Jibri and
     * entered PENDING state. Will abort the recording if we do not transit to
     * ON state after {@link JitsiMeetGlobalConfig#getJibriPendingTimeout()}
     * limit is exceeded.
     */
    private class PendingStatusTimeout implements Runnable
    {
        public void run()
        {
            synchronized (JibriRecorder.this)
            {
                // Clear this task reference, so it won't be
                // cancelling itself on status change from PENDING
                pendingTimeoutTask = null;

                if (JibriIq.Status.PENDING.equals(jibriStatus))
                {
                    logger.warn(
                        "Jibri pending timeout! " + conference.getRoomName());
                    XMPPError error
                        = new XMPPError(
                                XMPPError.Condition.remote_server_timeout);
                    recordingStopped(error);
                }
            }
        }
    }
}
