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
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * A Jibri SIP gateway that manages SIP calls for single conference. Relies on
 * the information provided by {@link JibriDetector} to tell whether any Jibris
 * are currently available and to select one for new {@link JibriSession}s
 * (JibriSession does the actual selection).
 *
 * @author Pawel Domas
 */
public class JibriSipGateway
    extends CommonJibriStuff
    implements JibriSession.Owner
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger
        = Logger.getLogger(JibriSipGateway.class);

    /**
     * Map of SIP {@link JibriSession}s mapped per SIP address which
     * identifies a SIP Jibri session.
     */
    private Map<String, JibriSession> sipSessions = new HashMap<>();

    /**
     * Creates new instance of {@link JibriSipGateway}.
     * @param conference parent conference for which the new instance will be
     * managing Jibri SIP sessions.
     * @param xmppConnection the connection which will be used to send XMPP
     * queries.
     * @param scheduledExecutor the executor service used by this instance
     * @param globalConfig the global config that provides some values required
     * by {@link JibriSession} to work.
     */
    public JibriSipGateway( JitsiMeetConferenceImpl         conference,
                            XmppConnection                  xmppConnection,
                            ScheduledExecutorService        scheduledExecutor,
                            JitsiMeetGlobalConfig           globalConfig)
    {
        super(
            true /* handles SIP Jibri events */,
            conference,
            xmppConnection,
            scheduledExecutor,
            globalConfig,
            Logger.getLogger(classLogger, conference.getLogger()));

    }

    /**
     * Accepts only {@link JibriIq} with a SIP address.
     * {@inheritDoc}
     */
    @Override
    protected boolean acceptType(JibriIq packet)
    {
        // the packet must contain a SIP address (otherwise it will be handled
        // by JibriRecorder)
        return !StringUtils.isNullOrEmpty(packet.getSipAddress());
    }

    /**
     * {@inheritDoc}
     */
    public void dispose()
    {
        try
        {
            List<JibriSession> sessions = new ArrayList<>(sipSessions.values());
            for (JibriSession session : sessions)
            {
                session.stop();
            }
        }
        finally
        {
            sipSessions.clear();
        }

        super.dispose();
    }

    @Override
    protected JibriSession getJibriSessionForMeetIq(JibriIq iq)
    {
        String sipAddress = iq.getSipAddress();

        return sipSessions.get(sipAddress);
    }

    @Override
    protected IQ handleStartRequest(JibriIq iq)
    {
        String sipAddress = iq.getSipAddress();
        String displayName = iq.getDisplayName();

        // Proceed if not empty
        if (!StringUtils.isNullOrEmpty(sipAddress))
        {
            String sessionId = generateSessionId();
            JibriSession jibriSession
                = new JibriSession(
                        this,
                        conference.getRoomName(),
                        globalConfig.getJibriPendingTimeout(),
                        connection,
                        scheduledExecutor,
                        jibriDetector,
                        false,
                        sipAddress,
                        displayName, null, null, sessionId, null,
                        classLogger);
            sipSessions.put(sipAddress, jibriSession);
            // Try starting Jibri
            if (jibriSession.start())
            {
                logger.info("Started Jibri session");
                return JibriIq.createResult(iq, sessionId);
            }
            else
            {
                logger.info("Failed to start a Jibri session");
                sipSessions.remove(sipAddress);
                ErrorIQ errorIq;
                if (jibriDetector.isAnyInstanceConnected())
                {
                    errorIq = IQ.createErrorResponse(iq, XMPPError.Condition.resource_constraint);
                }
                else
                {
                    errorIq = IQ.createErrorResponse(iq, XMPPError.Condition.service_unavailable);
                }
                return errorIq;
            }
        }
        else
        {
            // Bad request - no SIP address
            return IQ.createErrorResponse(
                    iq,
                    XMPPError.from(
                            XMPPError.Condition.bad_request,
                            "Stream ID is empty or undefined").build());
        }
    }

    @Override
    public void onSessionStateChanged(
        JibriSession jibriSession, JibriIq.Status newStatus, JibriIq.FailureReason failureReason)
    {
        if (!sipSessions.values().contains(jibriSession))
        {
            logger.error(
                "onSessionStateChanged for unknown session: " + jibriSession);
            return;
        }

        publishJibriSipCallState(jibriSession, newStatus, failureReason);

        if (JibriIq.Status.OFF.equals(newStatus))
        {
            String sipAddress = jibriSession.getSipAddress();
            sipSessions.remove(sipAddress);

            logger.info("Removing SIP call: " + sipAddress);
        }
    }

    /**
     * Updates status of specific {@link JibriSession}. Jicofo adds multiple
     * {@link SipCallState} MUC presence extensions to it's presence. One for
     * each active SIP Jibri session.
     * @param session the session for which the new status will be set
     * @param newStatus the new status
     * @param failureReason option error for OFF state
     */
    private void publishJibriSipCallState(JibriSession session,
                                          JibriIq.Status newStatus, JibriIq.FailureReason failureReason)
    {
        SipCallState sipCallState = new SipCallState();
        sipCallState.setState(newStatus);
        sipCallState.setFailureReason(failureReason);
        sipCallState.setSipAddress(session.getSipAddress());
        sipCallState.setSessionId(session.getSessionId());

        logger.info(
            "Publishing new jibri-sip-call-state: " + session.getSipAddress()
                + sipCallState.toXML() + " in: " + conference.getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            LinkedList<ExtensionElement> toRemove = new LinkedList<>();
            for (ExtensionElement ext : chatRoom2.getPresenceExtensions())
            {
                // Exclude all that do not match
                if (ext instanceof  SipCallState
                        && session.getSipAddress().equals(
                                ((SipCallState)ext).getSipAddress()))
                {
                    toRemove.add(ext);
                }
            }
            ArrayList<ExtensionElement> newExt = new ArrayList<>();
            newExt.add(sipCallState);

            chatRoom2.modifyPresence(toRemove, newExt);
        }
    }
}
