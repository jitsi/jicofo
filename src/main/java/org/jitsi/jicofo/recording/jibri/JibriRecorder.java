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

import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.recording.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
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
 * @author Sam Whited
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
     * The number of times to retry connecting to a Jibri.
     */
    static private final int NUM_RETRIES = 3;

    /**
     * Returns <tt>true> if given <tt>status</tt> precedes the <tt>RETRYING</tt>
     * status or <tt>false</tt> otherwise.
     */
    static private boolean isPreRetryStatus(JibriIq.Status status)
    {
        return JibriIq.Status.ON.equals(status)
            || JibriIq.Status.RETRYING.equals(status);
    }

    /**
     * Returns <tt>true</tt> if given <tt>status</tt> indicates that Jibri is in
     * the middle of starting of the recording process.
     */
    static private boolean isStartingStatus(JibriIq.Status status)
    {
        return JibriIq.Status.PENDING.equals(status)
            || JibriIq.Status.RETRYING.equals(status);
    }

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
     * Counts retry attempts.
     * FIXME it makes sense to retry as long as there are Jibris available, but
     * currently if one Jibri will not go offline, but keep returning some error
     * JibriDetector may keep selecting it infinitely, as we do not blacklist
     * such instances yet
     */
    private int retryAttempt = 0;

    /**
     * The stream ID received in the first Start IQ.
     */
    private String streamID;

    /**
     * Stores reference to the reply timeout task, so that it can be cancelled
     * once becomes irrelevant.
     */
    private Future<?> timeoutTrigger;

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

    private String getRoomName()
    {
        return conference.getRoomName();
    }

    /**
     * Accepts only {@link JibriIq}
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        return packet instanceof JibriIq ||
            (packet instanceof IQ &&
                recorderComponentJid != null &&
                recorderComponentJid.equals(packet.getFrom()));
    }

    /**
     * Sends an IQ to the given Jibri instance and asks it to start recording.
     */
    private void startJibri(final String jibriJid)
    {
        logger.info("Starting Jibri " + jibriJid + " for stream ID: "
            + streamID + " in room: " + getRoomName());

        final JibriIq startIq = new JibriIq();
        startIq.setTo(jibriJid);
        startIq.setType(IQ.Type.SET);
        startIq.setAction(JibriIq.Action.START);
        startIq.setStreamId(streamID);

        // Insert name of the room into Jibri START IQ
        startIq.setRoom(getRoomName());

        // Store Jibri JID to make the packet filter accept the response
        recorderComponentJid = jibriJid;

        // We're now in PENDING state(waiting for Jibri ON update)
        // Setting PENDING status also blocks from accepting
        // new start requests
        setJibriStatus(isPreRetryStatus(jibriStatus)
                ? JibriIq.Status.RETRYING : JibriIq.Status.PENDING);

        // We will not wait forever for the Jibri to start. This method can be
        // run multiple times on retry, so we want to restart the pending
        // timeout each time.
        reschedulePendingTimeout();

        // Clear the old timeout trigger if any
        cancelTimeoutTrigger();
        // Send start IQ on separate thread to not block, the packet processor
        // thread and still be able to detect eventual timeout. The response is
        // processed in processPacket().
        timeoutTrigger = scheduledExecutor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                final IQ startReply
                    = (IQ) xmpp.getXmppConnection()
                    .sendPacketAndGetReply(startIq);
                if (startReply == null)
                {
                    synchronized (JibriRecorder.this)
                    {
                        // Trigger request timeout
                        logger.info(
                            "Will trigger timeout in room: " + getRoomName());
                        processJibriError(new XMPPError(
                                XMPPError.Condition.request_timeout));
                    }
                }
                //else the response will be handled in processPacket()
            }
        });
    }

    private void cancelTimeoutTrigger()
    {
        if (timeoutTrigger != null)
        {
            timeoutTrigger.cancel(false);
            timeoutTrigger = null;
        }
    }

    /**
     * <tt>JibriIq</tt> processing.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void processPacket(Packet packet)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Processing an IQ from Jibri: " + packet.toXML());
        }

        IQ iq = (IQ) packet;

        String from = iq.getFrom();

        if (iq instanceof JibriIq)
        {
            JibriIq jibriIq = (JibriIq) iq;

            if (recorderComponentJid != null &&
                (from.equals(recorderComponentJid) ||
                    (from + "/").startsWith(recorderComponentJid)))
            {
                processJibriIqFromJibri(jibriIq);
            }
            else
            {
                String roomName = MucUtil.extractRoomNameFromMucJid(from);
                if (roomName == null)
                {
                    logger.warn("Could not extract room name from jid:" + from);
                    return;
                }

                String actualRoomName = getRoomName();
                if (!actualRoomName.equals(roomName))
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Ignored packet from: " + roomName
                                + ", my room: " + actualRoomName
                                + " p: " + packet.toXML());
                    }
                    return;
                }

                XmppChatMember chatMember = conference.findMember(from);
                if (chatMember == null)
                {
                    logger.warn("ERROR chat member not found for: " + from
                            + " in " + roomName);
                    return;
                }

                processJibriIqFromMeet(jibriIq, chatMember);
            }
        }
        else
        {
            // We're processing Jibri response, probably an error
            if (IQ.Type.ERROR.equals(iq.getType()))
            {
                processJibriError(iq.getError());
            }
        }
    }

    private void processJibriIqFromMeet(final JibriIq           iq,
                                        final XmppChatMember    sender)
    {
        String senderMucJid = sender.getContactAddress();
        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Jibri request from " + senderMucJid + " iq: " + iq.toXML());
        }

        JibriIq.Action action = iq.getAction();
        if (JibriIq.Action.UNDEFINED.equals(action))
            return;

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
            // Store stream ID
            streamID = iq.getStreamId();
            // Proceed if not empty
            if (!StringUtils.isNullOrEmpty(streamID))
            {
                // ACK the request immediately to simplify the flow,
                // any error will be passed with the FAILED state
                sendResultResponse(iq);
                // Try starting Jibri on separate thread with retries
                tryStartRestartJibri(null);
                return;
            }
            else
            {
                // Bad request - no stream ID
                sendErrorResponse(
                        iq,
                        XMPPError.Condition.bad_request,
                        "Stream ID is empty or undefined");
                return;
            }
        }
        // stop ?
        else if (JibriIq.Action.STOP.equals(action) &&
            recorderComponentJid != null &&
            (JibriIq.Status.ON.equals(jibriStatus) ||
                isStartingStatus(jibriStatus)))
        {
            // XXX FIXME: this is synchronous and will probably block the smack
            // thread that executes processPacket().
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
     * Processes an error received from Jibri.
     */
    private void processJibriError(XMPPError error)
    {
        if (recorderComponentJid != null)
        {
            logger.info(recorderComponentJid + " failed for room "
                    + getRoomName() + " with "
                    + (error != null ? error.toXML() : "null"));

            tryStartRestartJibri(error);
        }
        else
        {
            logger.warn("Triggered error while not recording: " + error.toXML()
                    + " in: " + getRoomName());
        }
    }

    /**
     * Method schedules/reschedules {@link PendingStatusTimeout} which will
     * clear recording state after
     * {@link JitsiMeetGlobalConfig#getJibriPendingTimeout()}.
     */
    private void reschedulePendingTimeout()
    {
        if (pendingTimeoutTask != null)
        {
            logger.info("Rescheduling pending timeout task for room: "
                    + getRoomName());
            pendingTimeoutTask.cancel(false);
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
            String roomName = getRoomName();

            logger.info("Updating status from Jibri: " + iq.toXML()
                + " for " + roomName);

            // We stop either on "off" or on "failed"
            if ((JibriIq.Status.OFF.equals(status) ||
                JibriIq.Status.FAILED.equals(status))
                && recorderComponentJid != null/* This means we're recording */)
            {
                // Make sure that there is XMPPError for eventual ERROR status
                XMPPError error = iq.getError();
                if (JibriIq.Status.FAILED.equals(status) && error == null)
                {
                    error = new XMPPError(
                            XMPPError.Condition.interna_server_error,
                            "Unknown error");
                }
                processJibriError(error);
            }
            else
            {
                setJibriStatus(status);
            }
        }

        sendResultResponse(iq);
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

        if (JibriIq.Status.ON.equals(newStatus))
        {
            // Reset retry counter
            retryAttempt = 0;
        }

        RecordingStatus recordingStatus = new RecordingStatus();

        recordingStatus.setStatus(newStatus);

        recordingStatus.setError(error);

        logger.info(
            "Publish new Jibri status: " + recordingStatus.toXML() +
            " in: " + getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            meetTools.sendPresenceExtension(
                chatRoom2,
                recordingStatus);
        }
    }

    /**
     * Sends a "stop" command to jibri.
     */
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
                    "Recording stopped on user request in " + getRoomName());
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
        logger.info("Recording stopped for: " + getRoomName());
        recorderComponentJid = null;
        retryAttempt = 0;
        cancelTimeoutTrigger();
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
    synchronized public void onJibriOffline(final String jibriJid)
    {
        if (jibriJid.equals(recorderComponentJid))
        {
            logger.warn("Jibri went offline: " + recorderComponentJid
                        + " for room: " + getRoomName());

            tryStartRestartJibri(
                    new XMPPError(
                            XMPPError.Condition.remote_server_error,
                            "Jibri disconnected unexpectedly"));
        }
        else if (recorderComponentJid == null)
        {
            updateJibriAvailability();
        }
    }

    /**
     * Will try to start Jibri recording if {@link #retryAttempt} <
     * {@link #NUM_RETRIES}. If retry limit is exceeded then will fail with
     * the given <tt>error</tt>. If <tt>error</tt> is <tt>null</tt> either
     * "service unavailable"(no Jibri available) or "retry limit exceeded"
     * will be used.
     * @param error optional <tt>XMPPError</tt> to fail with if the retry count
     * limit has been exceeded or there are no more Jibris to try with.
     */
    private void tryStartRestartJibri(XMPPError error)
    {
        if (retryAttempt++ < NUM_RETRIES)
        {
            final String newJibriJid = jibriDetector.selectJibri();
            if (newJibriJid != null)
            {
                startJibri(newJibriJid);
                return;
            }
            else if (error == null)
            {
                // Classify this failure as 'service not available'
                error = new XMPPError(XMPPError.Condition.service_unavailable);
            }
        }
        if (error == null)
        {
            error = new XMPPError(
                    XMPPError.Condition.interna_server_error,
                    "Retry limit exceeded");
        }
        // No more retries, stop either with the error passed as an argument
        // or with one defined here in this method, which will provide more
        // details about the reason
        recordingStopped(error);
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

                if (isStartingStatus(jibriStatus))
                {
                    logger.error("Jibri pending timeout! " + getRoomName());
                    XMPPError error
                        = new XMPPError(
                                XMPPError.Condition.remote_server_timeout);
                    recordingStopped(error);
                }
            }
        }
    }
}
