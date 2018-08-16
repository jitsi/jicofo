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
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriIq.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class holds the information about Jibri session. It can be either live
 * streaming or SIP gateway session {@link #isSIP}. Encapsulates the retry logic
 * which is supposed to try another instance when the current one fails. To make
 * this happen it needs to cache all the information required to start new
 * session. It uses {@link JibriDetector} to select new Jibri.
 *
 * @author Pawel Domas
 */
public class JibriSession
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger
        = Logger.getLogger(JibriSession.class);

    /**
     * Returns <tt>true</tt> if given <tt>status</tt> indicates that Jibri is in
     * the middle of starting of the recording process.
     */
    static private boolean isStartingStatus(JibriIq.Status status)
    {
        return JibriIq.Status.PENDING.equals(status);
    }

    /**
     * The JID of the Jibri currently being used by this session or
     * <tt>null</tt> otherwise.
     */
    private Jid currentJibriJid;

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
     * Reference to scheduled {@link PendingStatusTimeout}
     */
    private ScheduledFuture<?> pendingTimeoutTask;

    /**
     * How long this session can stay in "pending" status, before retry is made
     * (given in seconds).
     */
    private final long pendingTimeout;

    /**
     * The (bare) JID of the MUC room.
     */
    private final EntityBareJid roomName;

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

    private final String sessionId;

    /**
     * The broadcast id of the YouTube broadcast, if available.  This is used
     * to generate and distribute the viewing url of the live stream
     */
    private final String youTubeBroadcastId;

    /**
     * A JSON-encoded string containing arbitrary application data for Jibri
     */
    private final String applicationData;

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
     * @param youTubeBroadcastId the YouTube broadcast id (optional)
     * @param applicationData a JSON-encoded string containing application-specific
     * data for Jibri
     * @param logLevelDelegate logging level delegate which will be used to
     * select logging level for this instance {@link #logger}.
     */
    JibriSession(
            JibriSession.Owner owner,
            EntityBareJid roomName,
            long pendingTimeout,
            XmppConnection connection,
            ScheduledExecutorService scheduledExecutor,
            JibriDetector jibriDetector,
            boolean isSIP,
            String sipAddress,
            String displayName,
            String streamID,
            String youTubeBroadcastId,
            String sessionId,
            String applicationData,
            Logger logLevelDelegate)
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
        this.youTubeBroadcastId = youTubeBroadcastId;
        this.sessionId = sessionId;
        this.applicationData = applicationData;
        this.xmpp = connection;
        logger = Logger.getLogger(classLogger, logLevelDelegate);
    }

    /**
     * Starts this session. A new Jibri instance will be selected and start
     * request will be sent (in non blocking mode).
     * @return true if the start is successful, false otherwise
     */
    synchronized public boolean start()
    {
        final Jid jibriJid = jibriDetector.selectJibri();
        if (jibriJid != null)
        {
            try
            {
                jibriEventHandler.start(FocusBundleActivator.bundleContext);
                sendJibriStartIq(jibriJid);
                return true;
            }
            catch (Exception e)
            {
                logger.error("Failed to start Jibri event handler: " + e, e);
            }
        }
        else
        {
            logger.error("Unable to find an available Jibri, can't start");
        }
        return false;
    }

    /**
     * Stops this session if it's not already stopped.
     */
    synchronized public void stop()
    {
        if (currentJibriJid == null)
        {
            return;
        }

        JibriIq stopRequest = new JibriIq();

        stopRequest.setType(IQ.Type.set);
        stopRequest.setTo(currentJibriJid);
        stopRequest.setAction(JibriIq.Action.STOP);

        logger.info("Trying to stop: " + stopRequest.toXML());

        // When we send stop, we won't get an OFF presence back (just
        // a response to this message) so clean up the session
        // in the processing of the response.
        try
        {
            xmpp.sendIqWithResponseCallback(
                    stopRequest,
                    stanza -> {
                        JibriIq resp = (JibriIq)stanza;
                        setJibriStatus(resp.getStatus(), null);
                        cleanupSession();
                    },
                    exception -> {
                        logger.error("Error sending stop iq: " + exception.toString());
                    },
                    60000);
        } catch (SmackException.NotConnectedException | InterruptedException e)
        {
            logger.error("Error sending stop iq: " + e.toString());
        }
    }

    private void cleanupSession()
    {
        logger.info("Cleaning up current JibriSession");
        currentJibriJid = null;
        try
        {
            jibriEventHandler.stop(FocusBundleActivator.bundleContext);
        }
        catch (Exception e)
        {
            logger.error("Failed to stop Jibri event handler: " + e, e);
        }
    }

    /**
     * Accept only XMPP packets which are coming from the Jibri currently used
     * by this session.
     * {@inheritDoc}
     */
    public boolean accept(JibriIq packet)
    {
        return currentJibriJid != null
            && (packet.getFrom().equals(currentJibriJid));
    }

    /**
     * @return a string describing this session instance, used for logging
     * purpose
     */
    private String nickname()
    {
        return this.isSIP ? "SIP Jibri" : "Jibri";
    }

    IQ processJibriIqFromJibri(JibriIq iq)
    {
        // We have something from Jibri - let's update recording status
        JibriIq.Status status = iq.getStatus();
        if (!JibriIq.Status.UNDEFINED.equals(status))
        {
            logger.info(
                "Updating status from JIBRI: "
                    + iq.toXML() + " for " + roomName);

            setJibriStatus(status, iq.getFailureReason());
            if (JibriIq.Status.OFF.equals(status))
            {
                cleanupSession();
            }
        }
        else
        {
            logger.error("Received UNDEFINED status from jibri: " + iq.toString());
        }

        return IQ.createResultIQ(iq);
    }

    /**
     * Gets the recording mode of this jibri session
     * @return the recording mode for this session (STREAM, FILE or UNDEFINED
     * in the case that this isn't a recording session but actually a SIP
     * session)
     */
    JibriIq.RecordingMode getRecordingMode()
    {
        if (sipAddress != null)
        {
            return RecordingMode.UNDEFINED;
        }
        else if (streamID != null)
        {
            return RecordingMode.STREAM;
        }
        return RecordingMode.FILE;
    }

    /**
     * Sends an IQ to the given Jibri instance and asks it to start
     * recording/SIP call.
     */
    private void sendJibriStartIq(final Jid jibriJid)
    {
        // Store Jibri JID to make the packet filter accept the response
        currentJibriJid = jibriJid;
        logger.info(
            "Starting Jibri " + jibriJid
                + (isSIP
                    ? ("for SIP address: " + sipAddress)
                    : (" for stream ID: " + streamID))
                + " in room: " + roomName);

        final JibriIq startIq = new JibriIq();

        startIq.setTo(jibriJid);
        startIq.setType(IQ.Type.set);
        startIq.setAction(JibriIq.Action.START);
        startIq.setSessionId(this.sessionId);
        logger.debug("Passing on jibri application data: " + this.applicationData);
        startIq.setAppData(this.applicationData);
        if (streamID != null)
        {
            startIq.setStreamId(streamID);
            startIq.setRecordingMode(RecordingMode.STREAM);
            if (youTubeBroadcastId != null) {
                startIq.setYouTubeBroadcastId(youTubeBroadcastId);
            }
        }
        else
        {
            startIq.setRecordingMode(RecordingMode.FILE);
        }
        startIq.setSipAddress(sipAddress);
        startIq.setDisplayName(displayName);

        // Insert name of the room into Jibri START IQ
        startIq.setRoom(roomName);

        // We will not wait forever for the Jibri to start. This method can be
        // run multiple times on retry, so we want to restart the pending
        // timeout each time.
        reschedulePendingTimeout();

        try
        {
            JibriIq result = (JibriIq)xmpp.sendPacketAndGetReply(startIq);
            setJibriStatus(result.getStatus(), null);
        }
        catch (OperationFailedException e)
        {
            logger.error("Error sending Jibri start IQ: " + e.toString());
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
     * @param failureReason optional error for failed state.
     */
    private void setJibriStatus(JibriIq.Status newStatus, JibriIq.FailureReason failureReason)
    {
        logger.info("Setting jibri status to " + newStatus + " with failure reason " + failureReason);
        jibriStatus = newStatus;

        // Clear "pending" status timeout if we enter state other than "pending"
        if (pendingTimeoutTask != null
            && !JibriIq.Status.PENDING.equals(newStatus))
        {
            pendingTimeoutTask.cancel(false);
            pendingTimeoutTask = null;
        }

        owner.onSessionStateChanged(this, newStatus, failureReason);
    }

    /**
     * @return SIP address received from Jitsi Meet, which is used for SIP
     * gateway session (makes sense only for SIP sessions).
     */
    String getSipAddress()
    {
        return sipAddress;
    }

    /**
     * Get the unique ID for this session.  This is used to uniquely
     * identify a Jibri session instance, even of the same type (meaning,
     * for example, that two file recordings would have different session
     * IDs).  It will be passed to Jibri and Jibri will put the session ID
     * in its presence, so the Jibri user for a particular session can
     * be identified by the clients.
     * @return the session ID
     */
    public String getSessionId()
    {
        return this.sessionId;
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
            final Jid jibriJid = jibriEvent.getJibriJid();

            synchronized (JibriSession.this)
            {
                if (JibriEvent.WENT_OFFLINE.equals(topic)
                    && jibriJid.equals(currentJibriJid))
                {
                    logger.error(
                        nickname() + " went offline: " + jibriJid
                            + " for room: " + roomName);
                    setJibriStatus(JibriIq.Status.OFF, JibriIq.FailureReason.ERROR);
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
                    stop();
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
         * @param failureReason optional error for {@link JibriIq.Status#OFF}.
         */
        void onSessionStateChanged(
                JibriSession      jibriSession,
                JibriIq.Status    newStatus,
                JibriIq.FailureReason         failureReason);
    }
}
