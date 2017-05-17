/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ Atlassian Pty Ltd
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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.Logger;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class holds the information about Jibri session. It can be either live
 * streaming or SIP gateway session {@link #isSIP}. Encapsulates the retry logic
 * which is supposed to try another instance when the current one fails. To make
 * this happen it needs to cache all the information required to start new
 * session. It uses {@link JibriDetector} to select new Jibri.
 *
 * It also contains it's own {@link QueuePacketProcessor} and processes XMPP
 * packets associated with the current session.
 *
 * @author Pawel Domas
 */
public class JibriSession
    implements PacketFilter,
               PacketListener
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger
        = Logger.getLogger(JibriSession.class);

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
     * The JID of the Jibri currently being used by this session or
     * <tt>null</tt> otherwise.
     */
    private String currentJibriJid;

    /**
     * The display name Jibri attribute received from Jitsi Meet to be passed
     * further to Jibri instance that will be used.
     */
    private final String displayName;

    /**
     * Indicates whether this session is for a SIP Jibri (<tt>true</tt>) or for
     * regular Jibri (<tt>false</tt>).
     */
    private final boolean isSIP;

    /**
     * {@link JibriDetector} instance used to select a Jibri which will be used
     * by this session.
     */
    private final JibriDetector jibriDetector;

    /**
     * Helper class that registers for {@link JibriEvent}s in the OSGi context
     * obtained from the {@link FocusBundleActivator}.
     */
    private final JibriEventHandler jibriEventHandler = new JibriEventHandler();

    /**
     * Current Jibri recording status.
     */
    private JibriIq.Status jibriStatus = JibriIq.Status.UNDEFINED;

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    /**
     * The owner which will be notified about status changes of this session.
     */
    private final Owner owner;

    /**
     * The {@link QueuePacketProcessor} which executes
     * {@link #processPacket(Packet)} on dedicated single threaded queue.
     */
    private QueuePacketProcessor packetProcessor;

    /**
     * Reference to scheduled {@link PendingStatusTimeout}
     */
    private ScheduledFuture<?> pendingTimeoutTask;

    /**
     * How long this session can stay in "pending" status, before retry is made
     * (given in seconds).
     */
    private final long pendingTimeout;

    /**
     * Counts retry attempts.
     * FIXME it makes sense to retry as long as there are Jibris available, but
     * currently if one Jibri will not go offline, but keep returning some error
     * JibriDetector may keep selecting it infinitely, as we do not blacklist
     * such instances yet
     */
    private int retryAttempt = 0;

    /**
     * Name of the MUC room (full MUC address).
     */
    private final String roomName;

    /**
     * Executor service for used to schedule pending timeout tasks.
     */
    private final ScheduledExecutorService scheduledExecutor;

    /**
     * The SIP address attribute received from Jitsi Meet which is to be used to
     * start a SIP call. This field's used only if {@link #isSIP} is set to
     * <tt>true</tt>.
     */
    private final String sipAddress;

    /**
     * The id of the live stream received from Jitsi Meet, which will be used to
     * start live streaming session (used only if {@link #isSIP is set to
     * <tt>true</tt>}.
     */
    private final String streamID;

    /**
     * {@link XmppConnection} instance used to send/listen for XMPP packets.
     */
    private final XmppConnection xmpp;

    /**
     * Creates new {@link JibriSession} instance.
     * @param owner the session owner which will be notified about this session
     * state changes.
     * @param roomName the name if the XMPP MUC room (full address).
     * @param pendingTimeout how many seconds this session can wait in pending
     * state, before trying another Jibri instance or failing with an error.
     * @param connection the XMPP connection which will be used to send/listen
     * for packets.
     * @param scheduledExecutor the executor service which will be used to
     * schedule pending timeout task execution.
     * @param jibriDetector the Jibri detector which will be used to select
     * Jibri instance.
     * @param isSIP <tt>true</tt> if it's a SIP session or <tt>false</tt> for
     * a regular live streaming Jibri type of session.
     * @param sipAddress a SIP address if it's a SIP session
     * @param displayName a display name to be used by Jibri participant
     * entering the conference once the session starts.
     * @param streamID a live streaming ID if it's not a SIP session
     * @param logLevelDelegate logging level delegate which will be used to
     * select logging level for this instance {@link #logger}.
     */
    public JibriSession(
            JibriSession.Owner          owner,
            String                      roomName,
            long                        pendingTimeout,
            XmppConnection              connection,
            ScheduledExecutorService    scheduledExecutor,
            JibriDetector               jibriDetector,
            boolean                     isSIP,
            String                      sipAddress,
            String                      displayName,
            String                      streamID,
            Logger                      logLevelDelegate)
    {
        this.owner = owner;
        this.roomName = roomName;
        this.scheduledExecutor
            = Objects.requireNonNull(scheduledExecutor, "scheduledExecutor");
        this.pendingTimeout = pendingTimeout;
        this.isSIP = isSIP;
        this.jibriDetector = jibriDetector;
        this.sipAddress = sipAddress;
        this.displayName = displayName;
        this.streamID = streamID;
        this.xmpp = connection;
        logger = Logger.getLogger(classLogger, logLevelDelegate);
    }

    /**
     * Starts this session. A new Jibri instance will be selected and start
     * request will be sent (in non blocking mode).
     */
    synchronized public void start()
    {
        if (packetProcessor == null)
        {
            this.packetProcessor = new QueuePacketProcessor(xmpp, this, this);
            this.packetProcessor.start();

            tryStartRestartJibri(null);
        }
    }

    /**
     * Stops this session. It will block until result or error response is
     * received.
     * @return {@link XMPPError} if returned by the Jibri instance or
     * <tt>null</tt> if the session was stopped gracefully.
     */
    synchronized public XMPPError stop()
    {
        if (packetProcessor == null)
        {
            return null;
        }

        try
        {
            jibriEventHandler.stop(FocusBundleActivator.bundleContext);
        }
        catch (Exception e)
        {
            logger.error("Failed to stop Jibri event handler: " + e, e);
        }

        XMPPError error = null;
        /**
         * When sendStopIQ() succeeds without any errors it will reset the state
         * to "recording stopped", but in case something goes wrong the decision
         * must be made outside of that method.
         */
        boolean stoppedGracefully;
        try
        {
            error = sendStopIQ();
            if (error != null)
            {
                logger.error(
                    "An error response to the stop request: " + error.toXML());
            }
            stoppedGracefully = error == null;
        }
        catch (OperationFailedException e)
        {
            logger.error("Failed to send stop IQ - XMPP disconnected", e);
            stoppedGracefully = false;
        }

        if (!stoppedGracefully) {
            // The instance is going down which means that
            // the JitsiMeetConference is being disposed. We don't want any
            // updates to be sent, but it makes sense to reset the state
            // (and that's what recordingSopped() will do).
            recordingStopped(
                null, false /* do not send any status updates */);
        }
        else
        {
            setJibriStatus(JibriIq.Status.OFF, null);
        }

        packetProcessor.stop();
        packetProcessor = null;

        return error;
    }

    /**
     * Sends a "stop" command to the current Jibri(if any). If the operation is
     * accepted by Jibri (with a RESULT response) then the instance state will
     * be adjusted to stopped and new Jibri availability status will be
     * sent. Otherwise the decision whether the instance should go to
     * the stopped state has to be taken outside of this method based on
     * the result returned/Exception thrown.
     *
     * @return XMPPError if Jibri replies with an error or <tt>null</tt> if
     * the recording was stopped gracefully.
     *
     * @throws OperationFailedException if the XMPP connection is broken.
     */
    private XMPPError sendStopIQ()
        throws OperationFailedException
    {
        if (currentJibriJid == null)
            return null;

        JibriIq stopRequest = new JibriIq();

        stopRequest.setType(IQ.Type.SET);
        stopRequest.setTo(currentJibriJid);
        stopRequest.setAction(JibriIq.Action.STOP);

        logger.info("Trying to stop: " + stopRequest.toXML());

        IQ stopReply = (IQ) xmpp.sendPacketAndGetReply(stopRequest);

        logger.info("Stop response: " + IQUtils.responseToXML(stopReply));

        if (stopReply == null)
        {
            return new XMPPError(XMPPError.Condition.request_timeout, null);
        }

        if (IQ.Type.RESULT.equals(stopReply.getType()))
        {
            logger.info(
                this.isSIP ? "SIP call" : "recording"
                    + " stopped on user request in " + roomName);
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
     * Accept only XMPP packets which are coming from the Jibri currently used
     * by this session.
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        return packet instanceof IQ
            && currentJibriJid != null
            && (packet.getFrom().equals(currentJibriJid) ||
                    (packet.getFrom() + "/").startsWith(currentJibriJid));
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
            logger.debug(
                "Processing an IQ from Jibri: " + packet.toXML());
        }

        IQ iq = (IQ) packet;

        if (IQ.Type.RESULT.equals(iq.getType()))
        {
            return;
        }

        if (iq instanceof JibriIq)
        {
            JibriIq jibriIq = (JibriIq) iq;

            processJibriIqFromJibri(jibriIq);

            xmpp.sendPacket(IQ.createResultIQ(iq));
        }
        else if (IQ.Type.ERROR.equals(iq.getType()))
        {
            //processJibriError(iq.getError());
            XMPPError error = iq.getError();
            logger.info(currentJibriJid + " failed for room "
                + roomName + " with "
                + (error != null ? error.toXML() : "null"));

            tryStartRestartJibri(error);
        }
    }

    /**
     * @return a string describing this session instance, used for logging
     * purpose
     */
    private String nickname()
    {
        return this.isSIP ? "SIP Jibri" : "Jibri";
    }

    private void processJibriIqFromJibri(JibriIq iq)
    {
        // We have something from Jibri - let's update recording status
        JibriIq.Status status = iq.getStatus();
        if (!JibriIq.Status.UNDEFINED.equals(status))
        {
            logger.info(
                "Updating status from JIBRI: "
                    + iq.toXML() + " for " + roomName);

            // We stop either on "off" or on "failed"
            if ((JibriIq.Status.OFF.equals(status)
                    || JibriIq.Status.FAILED.equals(status))
                    && currentJibriJid != null)
            {
                // Make sure that there is XMPPError for eventual ERROR status
                XMPPError error = iq.getError();
                if (JibriIq.Status.FAILED.equals(status) && error == null)
                {
                    error = new XMPPError(
                        XMPPError.Condition.interna_server_error,
                        "Unknown error");
                }
                this.currentJibriJid = null;
                tryStartRestartJibri(error);
            }
            else
            {
                setJibriStatus(status, null);
            }
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
        boolean doRetry
            = error == null // on the first time there will be no error
                || error.getExtension(
                        "retry", "http://jitsi.org/protocol/jibri") != null;

        logger.debug(
            "Do retry? " + doRetry
                + " retries: " + retryAttempt + " limit: " + NUM_RETRIES
                + " in " + this.roomName);

        if (doRetry && retryAttempt++ < NUM_RETRIES)
        {
            final String newJibriJid = jibriDetector.selectJibri();

            logger.debug(
                "Selected JIBRI: " + newJibriJid + " in " + this.roomName);

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
        setJibriStatus(JibriIq.Status.FAILED, error);
        // Stop packet processor, etc.
        stop();
    }

    /**
     * Methods clears {@link #currentJibriJid} which means we're no longer
     * recording nor in contact with any Jibri instance.
     * Refreshes recording status in the room based on Jibri availability.
     *
     * @param error if the recording stopped because of an error it should be
     * passed as an argument here which will result in stopping with
     * the {@link JibriIq.Status#FAILED} status passed to the application.
     */
    private void recordingStopped(XMPPError error)
    {
        recordingStopped(error, true /* send recording status update */);
    }

    /**
     * Methods clears {@link #currentJibriJid} which means we're no longer
     * recording nor in contact with any Jibri instance.
     * Refreshes recording status in the room based on Jibri availability.
     *
     * @param error if the recording stopped because of an error it should be
     * passed as an argument here which will result in stopping with
     * the {@link JibriIq.Status#FAILED} status passed to the application.
     * @param updateStatus <tt>true</tt> if the Jibri availability status
     * broadcast should follow the transition to the stopped state.
     */
    private void recordingStopped(XMPPError error, boolean updateStatus)
    {
        if (isSIP)
        {
            logger.info(
                "Jibri SIP stopped for: "
                    + sipAddress + " in: " + roomName);
        }
        else
        {
            logger.info("Recording stopped for: " + roomName);
        }

        currentJibriJid = null;
        retryAttempt = 0;

        // First we'll send an error and then follow with availability status
        if (error != null && updateStatus)
        {
            setJibriStatus(JibriIq.Status.FAILED, error);
        }
    }

    /**
     * Sends an IQ to the given Jibri instance and asks it to start
     * recording/SIP call.
     */
    private void startJibri(final String jibriJid)
    {
        logger.info(
            "Starting Jibri " + jibriJid
                + (isSIP
                    ? ("for SIP address: " + sipAddress)
                    : (" for stream ID: " + streamID))
                + " in room: " + roomName);

        final JibriIq startIq = new JibriIq();

        startIq.setTo(jibriJid);
        startIq.setType(IQ.Type.SET);
        startIq.setAction(JibriIq.Action.START);
        startIq.setStreamId(streamID);
        startIq.setSipAddress(sipAddress);
        startIq.setDisplayName(displayName);

        // Insert name of the room into Jibri START IQ
        startIq.setRoom(roomName);

        // Store Jibri JID to make the packet filter accept the response
        currentJibriJid = jibriJid;

        // We're now in PENDING state (waiting for Jibri ON update)
        // Setting PENDING status also blocks from accepting
        // new start requests
        setJibriStatus(isPreRetryStatus(jibriStatus)
            ? JibriIq.Status.RETRYING : JibriIq.Status.PENDING, null);

        // We will not wait forever for the Jibri to start. This method can be
        // run multiple times on retry, so we want to restart the pending
        // timeout each time.
        reschedulePendingTimeout();

        xmpp.sendPacket(startIq);
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
            logger.info(
                "Rescheduling pending timeout task for room: " + roomName);
            pendingTimeoutTask.cancel(false);
        }

        if (pendingTimeout > 0)
        {
            pendingTimeoutTask
                = scheduledExecutor.schedule(
                        new PendingStatusTimeout(),
                        pendingTimeout, TimeUnit.SECONDS);
        }
    }

    /**
     * Stores current Jibri status and notifies {@link #owner}.
     * @param newStatus the new Jibri status to be set
     * @param error optional error for failed state.
     */
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

        owner.onSessionStateChanged(this, newStatus, error);
    }

    /**
     * @return SIP address received from Jitsi Meet, which is used for SIP
     * gateway session (makes sense only for SIP sessions).
     */
    public String getSipAddress()
    {
        return sipAddress;
    }

    /**
     * Helper class handles registration for the {@link JibriEvent}s.
     */
    private class JibriEventHandler
        extends EventHandlerActivator
    {

        private JibriEventHandler()
        {
            super(new String[]{
                JibriEvent.STATUS_CHANGED, JibriEvent.WENT_OFFLINE});
        }

        @Override
        public void handleEvent(Event event)
        {
            if (!JibriEvent.isJibriEvent(event))
            {
                logger.error("Invalid event: " + event);
                return;
            }

            final JibriEvent jibriEvent = (JibriEvent) event;
            final String topic = jibriEvent.getTopic();
            final String jibriJid = jibriEvent.getJibriJid();

            synchronized (JibriSession.this)
            {
                if (JibriEvent.WENT_OFFLINE.equals(topic)
                    && jibriJid.equals(currentJibriJid))
                {
                    logger.warn(
                        nickname() + " went offline: " + jibriJid
                            + " for room: " + roomName);

                    tryStartRestartJibri(
                        new XMPPError(
                            XMPPError.Condition.remote_server_error,
                            nickname() + " disconnected unexpectedly"));
                }
            }
        }
    }

    /**
     * Task scheduled after we have received RESULT response from Jibri and
     * entered PENDING state. Will abort the recording if we do not transit to
     * ON state, after {@link JitsiMeetGlobalConfig#getJibriPendingTimeout()}
     * limit is exceeded.
     */
    private class PendingStatusTimeout implements Runnable
    {
        public void run()
        {
            synchronized (JibriSession.this)
            {
                // Clear this task reference, so it won't be
                // cancelling itself on status change from PENDING
                pendingTimeoutTask = null;

                if (isStartingStatus(jibriStatus))
                {
                    logger.error(
                        nickname() + " pending timeout! " + roomName);
                    XMPPError error
                        = new XMPPError(
                                XMPPError.Condition.remote_server_timeout);
                    recordingStopped(error);
                }
            }
        }
    }

    /**
     * Interface instance passed to {@link JibriSession} constructor which
     * specifies the session owner which will be notified about any status
     * changes.
     */
    public interface Owner
    {
        /**
         * Called on {@link JibriSession} status update.
         * @param jibriSession which status has changed
         * @param newStatus the new status
         * @param error optional error for {@link JibriIq.Status#FAILED}.
         */
        void onSessionStateChanged(
                JibriSession      jibriSession,
                JibriIq.Status    newStatus,
                XMPPError         error);
    }
}
