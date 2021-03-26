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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.jicofo.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

import static org.jitsi.jicofo.jibri.JibriSession.StartException;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * A Jibri SIP gateway that manages SIP calls for single conference. Relies on
 * the information provided by {@link JibriDetector} to tell whether any Jibris
 * are currently available and to select one for new {@link JibriSession}s
 * (JibriSession does the actual selection).
 *
 * @author Pawel Domas
 */
public class JibriSipGateway
    extends BaseJibri
    implements JibriSession.Owner
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger = new LoggerImpl(JibriSipGateway.class.getName());

    /**
     * Map of SIP {@link JibriSession}s mapped per SIP address which
     * identifies a SIP Jibri session.
     */
    private final Map<String, JibriSession> sipSessions = new HashMap<>();

    /**
     * Creates new instance of {@link JibriSipGateway}.
     * @param conference parent conference for which the new instance will be managing Jibri SIP sessions.
     * @param scheduledExecutor the executor service used by this instance
     */
    public JibriSipGateway(
           @NotNull JitsiMeetConferenceImpl conference,
           @NotNull XmppProvider xmppProvider,
           @NotNull ScheduledExecutorService scheduledExecutor,
           @NotNull JibriDetector jibriDetector,
           @NotNull Logger parentLogger)
    {
        super(
            conference,
            xmppProvider,
            scheduledExecutor,
            parentLogger,
            jibriDetector);

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
        return isNotBlank(packet.getSipAddress());
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
                session.stop(null);
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

    /**
     * {@inheritDoc}
     */
    @Override
    public List<JibriSession> getJibriSessions()
    {
        List<JibriSession> sessions = new ArrayList<>(sipSessions.size());

        sessions.addAll(sipSessions.values());

        return sessions;
    }

    @Override
    protected IQ handleStartRequest(JibriIq iq)
    {
        String sipAddress = iq.getSipAddress();
        String displayName = iq.getDisplayName();

        // Proceed if not empty
        if (isNotBlank(sipAddress))
        {
            String sessionId = generateSessionId();
            JibriSession jibriSession
                = new JibriSession(
                        this,
                        conference.getRoomName(),
                        iq.getFrom(),
                        JibriConfig.config.getPendingTimeout().getSeconds(),
                        JibriConfig.config.getNumRetries(),
                        connection,
                        scheduledExecutor,
                        jibriDetector,
                        false,
                        sipAddress,
                        displayName, null, null, sessionId, null,
                        classLogger);
            sipSessions.put(sipAddress, jibriSession);

            try
            {
                jibriSession.start();
                logger.info("Started Jibri session");

                return JibriIq.createResult(iq, sessionId);
            }
            catch (StartException exc)
            {
                String reason = exc.getMessage();
                logger.warn("Failed to start a Jibri session: "  +  reason, exc);
                sipSessions.remove(sipAddress);
                ErrorIQ errorIq;
                if (exc instanceof StartException.AllBusy)
                {
                    errorIq = ErrorResponse.create(
                            iq,
                            XMPPError.Condition.resource_constraint,
                            "all Jibris are busy");
                }
                else if(exc instanceof StartException.NotAvailable)
                {
                    errorIq = ErrorResponse.create(
                            iq,
                            XMPPError.Condition.service_unavailable,
                            "no Jibri instances available");
                } else {
                    errorIq = ErrorResponse.create(
                            iq,
                            XMPPError.Condition.internal_server_error,
                            reason);
                }
                return errorIq;
            }
        }
        else
        {
            // Bad request - no SIP address
            return ErrorResponse.create(
                    iq,
                    XMPPError.Condition.bad_request,
                    "Stream ID is empty or undefined");
        }
    }

    @Override
    public void onSessionStateChanged(
        JibriSession jibriSession, JibriIq.Status newStatus, JibriIq.FailureReason failureReason)
    {
        if (!sipSessions.containsValue(jibriSession))
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

        ChatRoom chatRoom = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom != null)
        {
            LinkedList<ExtensionElement> toRemove = new LinkedList<>();
            for (ExtensionElement ext : chatRoom.getPresenceExtensions())
            {
                // Exclude all that do not match
                if (ext instanceof SipCallState
                        && session.getSipAddress().equals(((SipCallState)ext).getSipAddress()))
                {
                    toRemove.add(ext);
                }
            }
            ArrayList<ExtensionElement> newExt = new ArrayList<>();
            newExt.add(sipCallState);

            chatRoom.modifyPresence(toRemove, newExt);
        }
    }
}
