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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriIq.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;
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
    extends CommonJibriStuff
    implements JibriSession.Owner
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger
        = Logger.getLogger(JibriRecorder.class);

    /**
     * The current recording session or <tt>null</tt>.
     */
    private JibriSession jibriSession;

    /**
     * Creates new instance of <tt>JibriRecorder</tt>.
     * @param conference <tt>JitsiMeetConference</tt> to be recorded by new
     *        instance.
     * @param connection the XMPP connection which will be used for
     *        communication.
     * @param scheduledExecutor the executor service used by this instance
     * @param globalConfig the global config that provides some values required
     *                     by <tt>JibriRecorder</tt> to work.
     */
    public JibriRecorder(JitsiMeetConferenceImpl         conference,
                         XmppConnection                  connection,
                         ScheduledExecutorService        scheduledExecutor,
                         JitsiMeetGlobalConfig           globalConfig)
    {
        super(
            false /* deals with non SIP Jibri events */,
            conference,
            connection,
            scheduledExecutor,
            globalConfig,
            Logger.getLogger(classLogger, conference.getLogger()));
    }

    /**
     * {@inheritDoc}
     */
    public void dispose()
    {
        if (this.jibriSession != null)
        {
            this.jibriSession.stop();
            this.jibriSession = null;
        }

        super.dispose();
    }

    /**
     * Accepts only {@link JibriIq} without SIP address.
     * {@inheritDoc}
     */
    @Override
    protected boolean acceptType(JibriIq packet)
    {
        // the packet cannot contain a SIP address (must be handled
        // by JibriSipGateway)
        return StringUtils.isNullOrEmpty(packet.getSipAddress());
    }

    /**
     * Returns the current recording session if any.
     * {@inheritDoc}
     */
    @Override
    protected JibriSession getJibriSessionForMeetIq(JibriIq iq)
    {
        return jibriSession;
    }

    /**
     * Starts new session for given iq. It is assumed that
     * {@link CommonJibriStuff} has checked that there is no recording session
     * currently active.
     * {@inheritDoc}
     */
    @Override
    protected IQ handleStartRequest(JibriIq iq)
    {
        RecordingMode recordingMode = iq.getRecordingMode();
        String streamID = iq.getStreamId();
        boolean emptyStreamId = StringUtils.isNullOrEmpty(streamID);
        String youTubeBroadcastId = iq.getYoutubeBroadcastId();
        String displayName = iq.getDisplayName();
        String applicationData = iq.getAppData();

        if ((!emptyStreamId
                && (recordingMode.equals(RecordingMode.STREAM))
                    || recordingMode.equals(RecordingMode.UNDEFINED))
            || (emptyStreamId && recordingMode.equals(RecordingMode.FILE)))
        {
            String sessionId = generateSessionId();
            jibriSession
                = new JibriSession(
                    this,
                    conference.getRoomName(),
                    globalConfig.getJibriPendingTimeout(),
                    connection,
                    scheduledExecutor,
                    jibriDetector,
                    false, null, displayName, streamID, youTubeBroadcastId, sessionId, applicationData,
                    classLogger);
            if (jibriSession.start())
            {
                logger.info("Started Jibri session");
                return JibriIq.createResult(iq, sessionId);
            }
            else
            {
                ErrorIQ errorIq;
                if (jibriDetector.isAnyInstanceConnected())
                {
                    logger.info("Failed to start a Jibri session, all Jibris were busy");
                    errorIq = IQ.createErrorResponse(iq, XMPPError.Condition.resource_constraint);
                }
                else
                {
                    logger.info("Failed to start a Jibri session, no Jibris available");
                    errorIq = IQ.createErrorResponse(iq, XMPPError.Condition.service_unavailable);
                }
                jibriSession = null;
                return errorIq;
            }

        }
        else if (emptyStreamId && !recordingMode.equals(RecordingMode.FILE))
        {
            // Bad request - no stream ID and no recording mode
            return IQ.createErrorResponse(
                iq,
                XMPPError.from(
                    XMPPError.Condition.bad_request,
                    "Stream ID is empty and recording mode is not FILE"));
        }
        else if (emptyStreamId && recordingMode.equals(RecordingMode.STREAM))
        {
            // Bad request - no stream ID
            return IQ.createErrorResponse(
                    iq,
                    XMPPError.from(
                            XMPPError.Condition.bad_request,
                            "Stream ID is empty or undefined"));
        }
        else
        {
            // Bad request - catch all
            return IQ.createErrorResponse(
                    iq,
                    XMPPError.from(
                        XMPPError.Condition.bad_request,
                        "Invalid recording mode and stream ID combination"));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSessionStateChanged(
        JibriSession jibriSession, JibriIq.Status newStatus, JibriIq.FailureReason failureReason)
    {
        if (this.jibriSession != jibriSession)
        {
            logger.error(
                "onSessionStateChanged for unknown session: " + jibriSession);
            return;
        }
        publishJibriRecordingStatus(newStatus, failureReason);

        if (JibriIq.Status.OFF.equals(newStatus))
        {
            this.jibriSession = null;
        }
    }

    private void publishJibriRecordingStatus(
            JibriIq.Status newStatus, JibriIq.FailureReason failureReason)
    {
        logger.info("Got jibri status " + newStatus + " and failure " + failureReason);
        if (jibriSession == null)
        {
            // It's possible back-to-back 'stop' requests could be received, and while processing
            // the result of the first we set jibriSession to null, so in the processing
            // of the second one it will already be null.
            logger.info("Jibri session was already cleaned up, not sending new status");
            return;
        }
        RecordingStatus recordingStatus = new RecordingStatus();
        recordingStatus.setStatus(newStatus);
        recordingStatus.setFailureReason(failureReason);
        recordingStatus.setSessionId(jibriSession.getSessionId());
        JibriIq.RecordingMode recordingMode = jibriSession.getRecordingMode();
        if (recordingMode != RecordingMode.UNDEFINED)
        {
            recordingStatus.setRecordingMode(recordingMode);
        }

        logger.info(
                "Publishing new jibri-recording-status: "
                        + recordingStatus.toXML() + " in: " + conference.getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        if (chatRoom2 != null)
        {
            meetTools.sendPresenceExtension(chatRoom2, recordingStatus);
        }
    }
}
