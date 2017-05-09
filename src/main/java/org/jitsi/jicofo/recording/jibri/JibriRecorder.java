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
     * Accepts only {@link JibriIq}
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        // Do not process if it belongs to the recording session
        // FIXME should accept only packets coming from MUC
        return !(jibriSession != null && jibriSession.accept(packet))
            && packet instanceof JibriIq
            // and does not contain SIP address
            && StringUtils.isNullOrEmpty(((JibriIq)packet).getSipAddress());
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
        String streamID = iq.getStreamId();
        String displayName = iq.getDisplayName();

        // Proceed if not empty
        if (!StringUtils.isNullOrEmpty(streamID))
        {
            jibriSession
                = new JibriSession(
                        this,
                        conference.getRoomName(),
                        globalConfig.getJibriPendingTimeout(),
                        connection,
                        scheduledExecutor,
                        jibriDetector,
                        false, null, displayName, streamID,
                        classLogger);
            // Try starting Jibri on separate thread with retries
            jibriSession.start();
            // This will ACK the request immediately to simplify the flow,
            // any error will be passed with the FAILED state
            return IQ.createResultIQ(iq);
        }
        else
        {
            // Bad request - no stream ID
            return IQ.createErrorResponse(
                    iq,
                    new XMPPError(
                            XMPPError.Condition.bad_request,
                            "Stream ID is empty or undefined"));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSessionStateChanged(
        JibriSession jibriSession, JibriIq.Status newStatus, XMPPError error)
    {
        if (this.jibriSession != jibriSession)
        {
            logger.error(
                "onSessionStateChanged for unknown session: " + jibriSession);
            return;
        }

        // FIXME go through the stop logic
        boolean recordingStopped
            = JibriIq.Status.FAILED.equals(newStatus) ||
                    JibriIq.Status.OFF.equals(newStatus);

        setAvailabilityStatus(newStatus, error);

        if (recordingStopped)
        {
            this.jibriSession = null;

            updateJibriAvailability();
        }
    }

    /**
     * The method is supposed to update Jibri availability status to OFF if we
     * have any Jibris available or to UNDEFINED if there are no any.
     */
    @Override
    protected void updateJibriAvailability()
    {
        // We listen to status updates coming from the current Jibri
        // through IQs if the recording is in progress(jibriSession
        // is not null)
        if (jibriSession != null)
            return;

        if (jibriDetector.selectJibri() != null)
        {
            setAvailabilityStatus(JibriIq.Status.OFF);
        }
        else if (jibriDetector.isAnyInstanceConnected())
        {
            setAvailabilityStatus(JibriIq.Status.BUSY);
        }
        else
        {
            setAvailabilityStatus(JibriIq.Status.UNDEFINED);
        }
    }

    private void setAvailabilityStatus(JibriIq.Status newStatus)
    {
        setAvailabilityStatus(newStatus, null);
    }

    private void setAvailabilityStatus(
            JibriIq.Status newStatus, XMPPError error)
    {
        RecordingStatus recordingStatus = new RecordingStatus();

        recordingStatus.setStatus(newStatus);

        recordingStatus.setError(error);

        logger.info(
            "Publish new JIBRI status: "
                + recordingStatus.toXML() + " in: " + conference.getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            meetTools.sendPresenceExtension(chatRoom2, recordingStatus);
        }
    }
}
