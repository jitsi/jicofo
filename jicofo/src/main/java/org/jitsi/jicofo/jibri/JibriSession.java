/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017-Present 8x8, Inc.
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
package org.jitsi.jicofo.jibri;

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.Nullable;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.xmpp.extensions.jibri.JibriIq.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Class holds the information about Jibri session. It can be either live
 * streaming or SIP gateway session {@link #isSIP}. Encapsulates the retry logic
 * which is supposed to try another instance when the current one fails. To make
 * this happen it needs to cache all the information required to start new
 * session. It uses {@link JibriDetector} to select new Jibri.
 *
 * @author Pawel Domas
 *
 * This is not meant to be `public`, but has to be exposed because of compatibility with kotlin (JibriStats.kt takes
 * a parameter type that exposes JibriSession and it can not be restricted to java's "package private").
 */
public class JibriSession
{
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
     * TODO: Fix the inconsistent synchronization
     */
    @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
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
     * Helper class that registers for events from the {@link JibriDetector}.
     */
    private final JibriDetectorEventHandler jibriEventHandler = new JibriDetectorEventHandler();

    /**
     * Current Jibri recording status.
     */
    private JibriIq.Status jibriStatus = JibriIq.Status.UNDEFINED;

    private final Logger logger;

    /**
     * The listener which will be notified about status changes of this session.
     */
    private final StateListener stateListener;

    /**
     * Reference to scheduled {@link PendingStatusTimeout}
     */
    @NotNull
    private final AtomicReference<ScheduledFuture<?>> pendingTimeoutTask = new AtomicReference<>();

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
     * The maximum amount of retries we'll attempt
     */
    private final int maxNumRetries;

    /**
     * How many times we've retried this request to another Jibri
     */
    private int numRetries = 0;

    /**
     * The full JID of the entity that has initiated the recording flow.
     */
    private final Jid initiator;

    /**
     * The full JID of the entity that has initiated the stop of the recording.
     */
    private Jid terminator;

    /**
     * Creates new {@link JibriSession} instance.
     * @param stateListener the listener to be notified about this session state changes.
     * @param roomName the name if the XMPP MUC room (full address).
     * @param pendingTimeout how many seconds this session can wait in pending
     * state, before trying another Jibri instance or failing with an error.
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
     * @param parentLogger the parent logger whose context will be inherited by {@link #logger}.
     */
    JibriSession(
            StateListener stateListener,
            EntityBareJid roomName,
            Jid initiator,
            long pendingTimeout,
            int maxNumRetries,
            JibriDetector jibriDetector,
            boolean isSIP,
            String sipAddress,
            String displayName,
            String streamID,
            String youTubeBroadcastId,
            String sessionId,
            String applicationData,
            Logger parentLogger)
    {
        this.stateListener = stateListener;
        this.roomName = roomName;
        this.initiator = initiator;
        this.pendingTimeout = pendingTimeout;
        this.maxNumRetries = maxNumRetries;
        this.isSIP = isSIP;
        this.jibriDetector = jibriDetector;
        this.sipAddress = sipAddress;
        this.displayName = displayName;
        this.streamID = streamID;
        this.youTubeBroadcastId = youTubeBroadcastId;
        this.sessionId = sessionId;
        this.applicationData = applicationData;
        jibriDetector.addHandler(jibriEventHandler);
        logger = parentLogger.createChildLogger(getClass().getName());
    }

    /**
     * Used internally to call
     * {@link StateListener#onSessionStateChanged(JibriSession, Status, FailureReason)}.
     * @param newStatus the new status to dispatch.
     * @param failureReason the failure reason associated with the state
     * transition if any.
     */
    private void dispatchSessionStateChanged(Status newStatus, FailureReason failureReason)
    {
        if (failureReason != null)
        {
            JibriStats.sessionFailed(getJibriType());
        }
        stateListener.onSessionStateChanged(this, newStatus, failureReason);
    }

    /**
     * @return The {@link JibriSession.Type} of this session.
     */
    public Type getJibriType()
    {
        if (isSIP)
        {
            return Type.SIP_CALL;
        }
        else if (isBlank(streamID))
        {
            return Type.RECORDING;
        }
        else
        {
            return Type.LIVE_STREAMING;
        }
    }

    /**
     * @return {@code true} if this sessions is active or {@code false}
     * otherwise.
     */
    public boolean isActive()
    {
        return Status.ON.equals(jibriStatus);
    }

    /**
     * @return {@code true} if this session is pending or {@code false}
     * otherwise.
     */
    public boolean isPending()
    {
        return Status.UNDEFINED.equals(jibriStatus) || Status.PENDING.equals(jibriStatus);
    }

    /**
     * Starts this session. A new Jibri instance will be selected and start
     * request will be sent (in non blocking mode).
     * @throws StartException if failed to start.
     */
    synchronized public void start()
        throws StartException
    {
        try
        {
            startInternal();
        }
        catch (Exception e)
        {
            JibriStats.sessionFailed(getJibriType());

            throw e;
        }
    }

    /**
     * Does the actual start logic.
     *
     * @throws StartException if fails  to start.
     */
    private void startInternal()
        throws StartException
    {
        final Jid jibriJid = jibriDetector.selectJibri();

        if (jibriJid == null)
        {
            logger.error("Unable to find an available Jibri, can't start");

            if (jibriDetector.isAnyInstanceConnected())
            {
                throw new StartException.AllBusy();
            }

            throw new StartException.NotAvailable();
        }

        try
        {
            logger.info("Starting session with Jibri " + jibriJid);

            sendJibriStartIq(jibriJid);
        }
        catch (Exception e)
        {
            logger.error("Failed to send start Jibri IQ: " + e, e);
            jibriDetector.instanceFailed(jibriJid);
            if (!maxRetriesExceeded())
            {
                retryRequestWithAnotherJibri();
            }
            else
            {
                throw new StartException.InternalServerError();
            }
        }
    }

    /**
     * Stops this session if it's not already stopped.
     * @param initiator The jid of the initiator of the stop request.
     */
    synchronized public void stop(Jid initiator)
    {
        if (currentJibriJid == null)
        {
            return;
        }
        this.terminator = initiator;

        JibriIq stopRequest = new JibriIq();

        stopRequest.setType(IQ.Type.set);
        stopRequest.setTo(currentJibriJid);
        stopRequest.setAction(JibriIq.Action.STOP);
        stopRequest.setSessionId(this.sessionId);

        logger.info("Trying to stop: " + stopRequest.toXML());

        SmackFuture<IQ, Exception> future =
            jibriDetector.getXmppConnection().sendIqRequestAsync(stopRequest, 60000);

        future.onSuccess(stanza ->
        {
            if (stanza instanceof JibriIq)
            {
                processJibriIqFromJibri((JibriIq) stanza);
            }
            else
            {
                logger.error(
                    "Unexpected response to stop iq: "
                        + (stanza != null ? stanza.toXML() : "null"));
                stopError(stopRequest.getTo());
            }
        }).onError(exception ->
        {
            logger.error("Error from stop request: " + exception.toString());
            stopError(stopRequest.getTo());
        });
    }

    private void stopError(Jid jibriJid)
    {
        JibriIq error = new JibriIq();

        error.setFrom(jibriJid);
        error.setFailureReason(FailureReason.ERROR);
        error.setStatus(Status.OFF);

        processJibriIqFromJibri(error);
    }

    private void cleanupSession()
    {
        logger.info("Cleaning up current JibriSession");
        currentJibriJid = null;
        numRetries = 0;
        jibriDetector.removeHandler(jibriEventHandler);
    }

    /**
     * Accept only XMPP packets which are coming from the Jibri currently used
     * by this session.
     * {@inheritDoc}
     */
    public boolean accept(JibriIq packet)
    {
        return currentJibriJid != null && (packet.getFrom().equals(currentJibriJid));
    }

    /**
     * @return a string describing this session instance, used for logging
     * purpose
     */
    private String nickname()
    {
        return this.isSIP ? "SIP Jibri" : "Jibri";
    }

    /**
     * Process a {@link JibriIq} *request* from Jibri
     * @return the response
     */
    IQ processJibriIqRequestFromJibri(JibriIq request)
    {
        processJibriIqFromJibri(request);
        return IQ.createResultIQ(request);
    }

    /**
     * Process a {@link JibriIq} from Jibri (note that this
     * may be an IQ request or an IQ response)
     */
    private void processJibriIqFromJibri(JibriIq iq)
    {
        // We have something from Jibri - let's update recording status
        JibriIq.Status status = iq.getStatus();
        if (!JibriIq.Status.UNDEFINED.equals(status))
        {
            logger.info("Updating status from JIBRI: " + iq.toXML() + " for " + roomName);

            handleJibriStatusUpdate(iq.getFrom(), status, iq.getFailureReason(), iq.getShouldRetry());
        }
        else
        {
            logger.error("Received UNDEFINED status from jibri: " + iq);
        }
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
        throws SmackException.NotConnectedException,
               StartException
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
        startIq.setSessionId(sessionId);
        logger.debug(
            "Passing on jibri application data: " + this.applicationData);
        startIq.setAppData(this.applicationData);
        if (streamID != null)
        {
            startIq.setStreamId(streamID);
            startIq.setRecordingMode(RecordingMode.STREAM);
            if (youTubeBroadcastId != null)
            {
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

        IQ reply = UtilKt.sendIqAndGetResponse(jibriDetector.getXmppConnection(), startIq);

        if (reply == null)
        {
            logger.error("Jibri start request timed out, sending a stop command.");

            // The timeout may be due to internal jibri processing and not because of the network (e.g. chrome
            // taking a long time to start up). Since we won't be keeping a track of this jibri session anymore, send
            // a "stop" to prevent it from staying in the conference (if it succeeds to get there).
            JibriIq stopRequest = new JibriIq();

            stopRequest.setType(IQ.Type.set);
            stopRequest.setTo(jibriJid);
            stopRequest.setAction(JibriIq.Action.STOP);
            stopRequest.setSessionId(sessionId);
            try
            {
                jibriDetector.getXmppConnection().trySendStanza(stopRequest);
            }
            catch (SmackException.NotConnectedException e)
            {
                logger.error("Can't send stop IQ, not connected");
            }

            throw new StartException.UnexpectedResponse();
        }
        if (!(reply instanceof JibriIq))
        {
            logger.error("Unexpected response to start request: " + reply.toXML());

            throw new StartException.UnexpectedResponse();
        }

        JibriIq jibriIq = (JibriIq) reply;
        if (isBusyResponse(jibriIq))
        {
            logger.info("Jibri " + jibriIq.getFrom() + " was busy");
            throw new StartException.OneBusy();
        }
        if (!isPendingResponse(jibriIq))
        {
            logger.error(
                "Unexpected status received in response to the start IQ: " + jibriIq.toXML());

            throw new StartException.UnexpectedResponse();
        }

        processJibriIqFromJibri(jibriIq);
    }

    /**
     * Method schedules/reschedules {@link PendingStatusTimeout} which will clear recording state after a timeout of
     * {@link JibriConfig#getPendingTimeout()}.
     */
    private void reschedulePendingTimeout()
    {
        // If pendingTimeout <= 0, no tasks are ever scheduled, so there is nothing to cancel.
        if (pendingTimeout > 0)
        {
            ScheduledFuture<?> newTask
                = TaskPools.getScheduledPool().schedule(new PendingStatusTimeout(), pendingTimeout, TimeUnit.SECONDS);
            ScheduledFuture<?> oldTask = pendingTimeoutTask.getAndSet(newTask);
            if (oldTask != null)
            {
                logger.info("Rescheduling pending timeout task for room: " + roomName);
                oldTask.cancel(false);
            }
        }
    }

    /**
     * Clear the pending timeout task.
     *
     * @param cancel whether to cancel the previous task if it exists.
     */
    private void clearPendingTimeout(boolean cancel)
    {
        ScheduledFuture<?> oldTask = pendingTimeoutTask.getAndSet(null);
        if (cancel)
        {
            if (oldTask != null)
            {
                logger.info("Jibri is no longer pending, cancelling pending timeout task");
                oldTask.cancel(false);
            }
        }
    }

    /**
     * Check whether or not we should retry the current request to another Jibri
     * @return true if we've not exceeded the max amount of retries,
     * false otherwise
     */
    private boolean maxRetriesExceeded()
    {
        return (maxNumRetries >= 0 && numRetries >= maxNumRetries);
    }

    /**
     * Retry the current request with another Jibri (if one is available)
     * @throws StartException if failed to start.
     */
    private void retryRequestWithAnotherJibri()
        throws StartException
    {
        numRetries++;
        start();
    }

    /**
     * Handle a Jibri status update (this could come from an IQ response, a new
     * IQ from Jibri, an XMPP event, etc.).
     * This will handle:
     * 1) Retrying with a new Jibri in case of an error
     * 2) Cleaning up the session when the Jibri session finished successfully
     * (or there was an error but we have no more Jibris left to try)
     * @param jibriJid the jid of the jibri for which this status update applies
     * @param newStatus the jibri's new status
     * @param failureReason the jibri's failure reason, if any (otherwise null)
     * @param shouldRetryParam if {@code failureReason} is not null, shouldRetry
     *                    denotes whether or not we should retry the same
     *                    request with another Jibri
     */
    private void handleJibriStatusUpdate(
            @NotNull Jid jibriJid,
            JibriIq.Status newStatus,
            @Nullable JibriIq.FailureReason failureReason,
            @Nullable Boolean shouldRetryParam)
    {
        jibriStatus = newStatus;
        logger.info("Got Jibri status update: Jibri " + jibriJid
            + " has status " + newStatus
            + " and failure reason " + failureReason
            + ", current Jibri jid is " + currentJibriJid);
        if (currentJibriJid == null)
        {
            logger.info("Current session has already been cleaned up, ignoring");
            return;
        }
        if (jibriJid.compareTo(currentJibriJid) != 0)
        {
            logger.info("This status update is from " + jibriJid +
                    " but the current Jibri is " + currentJibriJid + ", ignoring");
            return;
        }
        // First: if we're no longer pending (regardless of the Jibri's new state), make sure we stop the pending
        // timeout task.
        if (!Status.PENDING.equals(newStatus))
        {
            clearPendingTimeout(true);
        }

        // Now, if there was a failure of any kind we'll try and find another
        // Jibri to keep things going
        if (failureReason != null)
        {
            boolean shouldRetry;
            if (shouldRetryParam == null)
            {
                logger.warn("failureReason was non-null but shouldRetry wasn't set, will NOT retry");
                shouldRetry = false;
            }
            else
            {
                shouldRetry = shouldRetryParam;
            }
            // There was an error with the current Jibri, see if we should retry
            if (shouldRetry && !maxRetriesExceeded())
            {
                logger.info("Jibri failed, trying to fall back to another Jibri");

                try
                {
                    retryRequestWithAnotherJibri();

                    // The fallback to another Jibri succeeded.
                    logger.info("Successfully resumed session with another Jibri");
                }
                catch (StartException exc)
                {
                    logger.warn("Failed to fall back to another Jibri, this session has now failed: " + exc, exc);
                    // Propagate up that the session has failed entirely.
                    // We'll pass the original failure reason.
                    dispatchSessionStateChanged(newStatus, failureReason);
                    cleanupSession();
                }
            }
            else
            {
                if (!shouldRetry)
                {
                    logger.info("Jibri failed and signaled that we should not retry the same request");
                }
                else
                {
                    // The Jibri we tried failed and we've reached the maxmium
                    // amount of retries we've been configured to attempt, so we'll
                    // give up trying to handle this request.
                    logger.info("Jibri failed, but max amount of retries (" + maxNumRetries + ") reached, giving up");
                }
                dispatchSessionStateChanged(newStatus, failureReason);
                cleanupSession();
            }
        }
        else if (Status.OFF.equals(newStatus))
        {
            logger.info("Jibri session ended cleanly, notifying owner and "
                + "cleaning up session");
            // The Jibri stopped for some non-error reason
            dispatchSessionStateChanged(newStatus, null);
            cleanupSession();
        }
        else if (Status.ON.equals(newStatus))
        {
            logger.info("Jibri session started, notifying owner");
            dispatchSessionStateChanged(newStatus, null);
        }
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
     * Task scheduled after we have received RESULT response from Jibri and entered PENDING state. Will abort the
     * recording if we do not transit to ON state, after a timeout of {@link JibriConfig#getPendingTimeout()}.
     */
    private class PendingStatusTimeout implements Runnable
    {
        public void run()
        {
            synchronized (JibriSession.this)
            {
                // Clear this task reference, so it won't be
                // cancelling itself on status change from PENDING
                clearPendingTimeout(false);

                if (isStartingStatus(jibriStatus))
                {
                    logger.error(nickname() + " pending timeout! " + roomName);
                    // If a Jibri times out during the pending phase, it's
                    // likely hung or having some issue.  We'll send a stop (so
                    // if/when it does 'recover', it knows to stop) and simulate
                    // an error status (like we do in
                    // JibriEventHandler#handleEvent when a Jibri goes offline)
                    // to trigger the fallback logic.
                    stop(null);
                    handleJibriStatusUpdate(currentJibriJid, Status.OFF, FailureReason.ERROR, true);
                }
            }
        }
    }

    /**
     * The JID of the entity that has initiated the recording flow.
     * @return The JID of the entity that has initiated the recording flow.
     */
    public Jid getInitiator()
    {
        return initiator;
    }

    /**
     * The JID of the entity that has initiated the stop of the recording.
     * @return The JID of the entity that has stopped the recording.
     */
    public Jid getTerminator()
    {
        return terminator;
    }

    /**
     * Interface instance passed to {@link JibriSession} constructor which
     * specifies the session owner which will be notified about any status
     * changes.
     */
    public interface StateListener
    {
        /**
         * Called on {@link JibriSession} status update.
         * @param jibriSession which status has changed
         * @param newStatus the new status
         * @param failureReason optional error for {@link JibriIq.Status#OFF}.
         */
        void onSessionStateChanged(
                JibriSession jibriSession,
                JibriIq.Status newStatus,
                JibriIq.FailureReason failureReason);
    }

    static public abstract class StartException extends Exception
    {
        public StartException(String message)
        {
            super(message);
        }

        static public class AllBusy extends StartException
        {
            public AllBusy()
            {
                super("All jibri instances are busy");
            }
        }
        static public class InternalServerError extends StartException
        {
            public InternalServerError()
            {
                super("Internal server error");
            }
        }
        static public class NotAvailable extends StartException
        {
            public NotAvailable()
            {
                super("No Jibris available");
            }
        }
        static public class UnexpectedResponse extends StartException
        {
            public UnexpectedResponse()
            {
                super("Unexpected response");
            }
        }
        static public class OneBusy extends StartException
        {
            public OneBusy()
            {
                super("This Jibri instance was busy");
            }
        }
    }

    /**
     * A Jibri session type.
     */
    public enum Type
    {
        /**
         * SIP Jibri call.
         */
        SIP_CALL,
        /**
         * Jibri live streaming session.
         */
        LIVE_STREAMING,
        /**
         * Jibri recording session.
         */
        RECORDING
    }

    private class JibriDetectorEventHandler implements JibriDetector.EventHandler
    {
        @Override
        public void instanceOffline(Jid jid)
        {
            if (jid.equals(currentJibriJid))
            {
                logger.warn(nickname() + " went offline: " + jid
                        + " for room: " + roomName);
                handleJibriStatusUpdate(
                        jid, Status.OFF, FailureReason.ERROR, true);
            }
        }
    }

    /**
     * Returns true if the given IQ represens a busy response from Jibri
     */
    private boolean isBusyResponse(JibriIq iq)
    {
        return Status.OFF.equals(iq.getStatus()) &&
            iq.isFailure() &&
            FailureReason.BUSY.equals(iq.getFailureReason());
    }

    private boolean isPendingResponse(JibriIq iq)
    {
        return Status.PENDING.equals(iq.getStatus());
    }

}
