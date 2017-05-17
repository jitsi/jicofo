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
     * Accepts only {@link JibriIq}
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        // Do not process if it belongs to the recording session
        // FIXME should accept only packets coming from MUC
        return !comesFromSipSession(packet)
            && packet instanceof JibriIq
            // and contains SIP address
            && !StringUtils.isNullOrEmpty(((JibriIq)(packet)).getSipAddress());
    }

    private boolean comesFromSipSession(Packet p)
    {
        for (JibriSession session : sipSessions.values())
        {
            if (session.accept(p))
                return true;
        }
        return false;
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
                        displayName, null,
                        classLogger);
            sipSessions.put(sipAddress, jibriSession);
            // Try starting Jibri
            jibriSession.start();
            // This will ACK the request immediately to simplify the flow,
            // any error will be passed with the FAILED state
            return IQ.createResultIQ(iq);
        }
        else
        {
            // Bad request - no SIP address
            return IQ.createErrorResponse(
                    iq,
                    new XMPPError(
                            XMPPError.Condition.bad_request,
                            "Stream ID is empty or undefined"));
        }
    }

    @Override
    public void onSessionStateChanged(
        JibriSession jibriSession, JibriIq.Status newStatus, XMPPError error)
    {
        if (!sipSessions.values().contains(jibriSession))
        {
            logger.error(
                "onSessionStateChanged for unknown session: " + jibriSession);
            return;
        }

        boolean sessionStopped
            = JibriIq.Status.FAILED.equals(newStatus)
                    || JibriIq.Status.OFF.equals(newStatus);

        setJibriStatus(jibriSession, newStatus, error);

        if (sessionStopped)
        {
            String sipAddress = jibriSession.getSipAddress();
            sipSessions.remove(sipAddress);

            logger.info("Removing SIP call: " + sipAddress);

            updateJibriAvailability();
        }
    }

    /**
     * The method is supposed to update SIP Jibri availability status to
     * AVAILABLE if there any Jibris available or to UNDEFINED if there are no
     * any. If all instances are BUSY, {@link JibriIq.Status#BUSY} will be set.
     */
    @Override
    protected void updateJibriAvailability()
    {
        if (jibriDetector.selectJibri() != null)
        {
            setAvailabilityStatus(JibriIq.Status.AVAILABLE);
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

    /**
     * Publishes new SIP Jibri availability status which informs Jitsi Meet
     * whether or not there are any SIP Jibri instances available.
     * @param newStatus the new availability status to be advertised
     */
    private void setAvailabilityStatus(JibriIq.Status newStatus)
    {
        SipGatewayStatus sipGatewayStatus = new SipGatewayStatus();

        sipGatewayStatus.setStatus(newStatus);

        logger.info(
            "Publish new SIP JIBRI status: "
                + sipGatewayStatus.toXML()
                + " in: " + conference.getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            meetTools.sendPresenceExtension(chatRoom2, sipGatewayStatus);
        }
    }

    /**
     * Updates status of specific {@link JibriSession}. Jicofo adds multiple
     * {@link SipCallState} MUC presence extensions to it's presence. One for
     * each active SIP Jibri session.
     * @param session the session for which the new status will be set
     * @param newStatus the new status
     * @param error option error for FAILED state
     */
    private void setJibriStatus(JibriSession session,
                                JibriIq.Status newStatus, XMPPError error)
    {
        SipCallState sipCallState = new SipCallState();

        sipCallState.setState(newStatus);

        sipCallState.setError(error);

        sipCallState.setSipAddress(session.getSipAddress());

        logger.info(
            "Publish new Jibri SIP status for: " + session.getSipAddress()
                + sipCallState.toXML() + " in: " + conference.getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            Collection<PacketExtension> oldExtension
                = chatRoom2.getPresenceExtensions();
            LinkedList<PacketExtension> toRemove = new LinkedList<>();
            for (PacketExtension ext : oldExtension)
            {
                // Exclude all that do not match
                if (ext instanceof  SipCallState
                        && session.getSipAddress().equals(
                                ((SipCallState)ext).getSipAddress()))
                {
                    toRemove.add(ext);
                }
            }
            ArrayList<PacketExtension> newExt = new ArrayList<>();
            newExt.add(sipCallState);

            chatRoom2.modifyPresence(toRemove, newExt);
        }
    }
}
