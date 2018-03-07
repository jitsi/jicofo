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
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;

import static org.jivesoftware.smack.packet.XMPPError.*;
import static org.jivesoftware.smack.packet.XMPPError.Condition.*;

/**
 * Common stuff shared between {@link JibriRecorder} (which can deal with only
 * 1 {@link JibriSession}) and {@link JibriSipGateway} (which is capable of
 * handling multiple, simultaneous {@link JibriSession}s).
 *
 * @author Pawel Domas
 */
public abstract class CommonJibriStuff
{
    /**
     * The Jitsi Meet conference instance.
     */
    protected final JitsiMeetConferenceImpl conference;

    /**
     * The {@link XmppConnection} used for communication.
     */
    protected final XmppConnection connection;

    /**
     * The global config used by this instance to obtain some config options
     * like {@link JitsiMeetGlobalConfig#getJibriPendingTimeout()}.
     */
    protected final JitsiMeetGlobalConfig globalConfig;

    /**
     * Indicates whether {@link JibriEventHandler} for this instance should be
     * accepting events from SIP or non-SIP Jibris.
     */
    private final boolean isSIP;

    /**
     * Jibri detector which notifies about Jibri availability status changes.
     */
    protected final JibriDetector jibriDetector;

    /**
     * Helper class that registers for {@link JibriEvent}s in the OSGi context
     * obtained from the {@link FocusBundleActivator}.
     */
    private final JibriEventHandler jibriEventHandler = new JibriEventHandler();

    /**
     * The logger instance pass to the constructor that wil be used by this
     * instance for logging.
     */
    protected final Logger logger;

    /**
     * Meet tools instance used to inject packet extensions to Jicofo's MUC
     * presence.
     */
    protected final OperationSetJitsiMeetTools meetTools;

    /**
     * Executor service used by {@link JibriSession} to schedule pending timeout
     * tasks.
     */
    protected final ScheduledExecutorService scheduledExecutor;

    /**
     * Creates new instance of <tt>JibriRecorder</tt>.
     * @param isSIP indicates whether this stuff is for SIP Jibri or for regular
     *        Jibris.
     * @param conference <tt>JitsiMeetConference</tt> to be recorded by new
     *        instance.
     * @param xmppConnection XMPP operation set which wil be used to send XMPP
     *        queries.
     * @param scheduledExecutor the executor service used by this instance
     * @param globalConfig the global config that provides some values required
     *        by <tt>JibriRecorder</tt> to work.
     */
    public CommonJibriStuff(
                           boolean                         isSIP,
                           JitsiMeetConferenceImpl         conference,
                           XmppConnection                  xmppConnection,
                           ScheduledExecutorService        scheduledExecutor,
                           JitsiMeetGlobalConfig           globalConfig,
                           Logger                          logger)
    {
        this.isSIP = isSIP;
        this.connection
            = Objects.requireNonNull(xmppConnection, "xmppConnection");
        this.conference = Objects.requireNonNull(conference, "conference");
        this.scheduledExecutor
            = Objects.requireNonNull(scheduledExecutor, "scheduledExecutor");
        this.globalConfig
            = Objects.requireNonNull(globalConfig, "globalConfig");
        this.jibriDetector
            = isSIP
                ? conference.getServices().getSipJibriDetector()
                : conference.getServices().getJibriDetector();

        ProtocolProviderService protocolService = conference.getXmppProvider();

        this.meetTools
            = protocolService.getOperationSet(OperationSetJitsiMeetTools.class);

        this.logger = logger;
    }

    /**
     * Tries to figure out if there is any current {@link JibriSession} for
     * the given IQ coming from the Jitsi Meet. If extending class can deal with
     * only 1 {@link JibriSession} at a time it should return it. If it's
     * capable of handling multiple sessions then it should try to identify the
     * session based on the information specified in the <tt>iq</tt>. If it's
     * unable to match any session it should return <tt>null</tt>.
     *
     * The purpose of having this method abstract is to share the logic for
     * handling start and stop requests. For example if there's incoming stop
     * request it will be handled if this method return a valid
     * {@link JibriSession} instance. In case of a start request a new session
     * will be created if this method returns <tt>null</tt>.
     *
     * @param iq the IQ originated from the Jitsi Meet participant (start or
     *        stop request)
     * @return {@link JibriSession} if there is any {@link JibriSession}
     * currently active for given IQ.
     */
    protected abstract JibriSession getJibriSessionForMeetIq(JibriIq iq);

    /**
     * This method will be called when start IQ arrives from Jitsi Meet
     * participant and {@link #getJibriSessionForMeetIq(JibriIq)} returns
     * <tt>null</tt>. The implementing class should allocate and store new
     * {@link JibriSession}. Once {@link JibriSession} is created it must be
     * started by the implementing class.
     * @param iq the Jibri IQ which is a start request coming from Jitsi Meet
     * participant
     * @return the response to the given <tt>iq</tt>. It should be 'result' if
     * new session has been started or 'error' otherwise.
     */
    protected abstract IQ handleStartRequest(JibriIq iq);

    /**
     * The method is called when Jicofo's MUC presence regarding Jibri status
     * needs to updated. For example when the current session ends or when new
     * Jibri connects.
     */
    protected abstract void updateJibriAvailability();

    /**
     * Starts listening for Jibri updates and calls
     * {@link #updateJibriAvailability()} to update Jicofo presence.
     */
    public void init()
    {
        try
        {
            jibriEventHandler.start(FocusBundleActivator.bundleContext);
        }
        catch (Exception e)
        {
            logger.error("Failed to start Jibri event handler: " + e, e);
        }

        updateJibriAvailability();
    }

    /**
     * Method called by {@link JitsiMeetConferenceImpl} when the conference is
     * being stopped. Unregisters {@link JibriEventHandler} and stops processing
     * Jibri XMPP packets.
     */
    public void dispose()
    {
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
     * Checks if the IQ is from a member of this room or from an active Jibri
     * session.
     * @param iq a random incoming Jibri IQ.
     * @return <tt>true</tt>, when the IQ is from a member of this room or from
     * an active Jibri session.
     */
    public final boolean accept(JibriIq iq)
    {
        // Process if it belongs to an active recording session
        JibriSession session = getJibriSessionForMeetIq(iq);
        if (session != null && session.accept(iq))
        {
            return true;
        }

        // Check if the implementation wants to deal with this IQ sub-type
        if (!acceptType(iq))
        {
            return false;
        }

        Jid from = iq.getFrom();
        BareJid roomName = from.asBareJid();
        if (!conference.getRoomName().equals(roomName))
        {
            return false;
        }

        XmppChatMember chatMember = conference.findMember(from);
        if (chatMember == null)
        {
            logger.warn("Chat member not found for: " + from);
            return false;
        }

        return true;
    }

    /**
     * Implementors of this class decided here if they want to deal with
     * the incoming JibriIQ.
     * @param packet the Jibri IQ to check.
     * @return <tt>true</tt> if the implementation should handle it.
     */
    protected abstract boolean acceptType(JibriIq packet);

    /**
     * <tt>JibriIq</tt> processing. Handles start and stop requests. Will verify
     * if the user is a moderator.
     */
    public final synchronized IQ handleIQRequest(JibriIq iq)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Jibri request. IQ: " + iq.toXML());
        }

        // Process if it belongs to an active recording session
        JibriSession session = getJibriSessionForMeetIq(iq);
        if (session != null && session.accept(iq))
        {
            return session.processJibriIqFromJibri(iq);
        }

        JibriIq.Action action = iq.getAction();
        if (JibriIq.Action.UNDEFINED.equals(action))
        {
            return IQ.createErrorResponse(iq, getBuilder(bad_request));
        }

        // verifyModeratorRole create 'not_allowed' error on when not moderator
        XMPPError error = verifyModeratorRole(iq);
        if (error != null)
        {
            logger.warn("Ignored Jibri request from non-moderator.");
            return IQ.createErrorResponse(iq, error);
        }

        JibriSession jibriSession = getJibriSessionForMeetIq(iq);

        // start ?
        if (JibriIq.Action.START.equals(action) &&
            jibriSession == null)
        {
            return handleStartRequest(iq);
        }
        // stop ?
        else if (JibriIq.Action.STOP.equals(action) &&
            jibriSession != null)
        {
            jibriSession.stop();
            return IQ.createResultIQ(iq);
        }

        logger.warn("Discarded: " + iq.toXML() + " - nothing to be done, ");

        // Bad request
        return IQ.createErrorResponse(
            iq, from(bad_request, "Unable to handle: '" + action));
    }

    private XMPPError verifyModeratorRole(JibriIq iq)
    {
        Jid from = iq.getFrom();
        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            return getBuilder(forbidden).build();
        }

        if (ChatRoomMemberRole.MODERATOR.compareTo(role) < 0)
        {
            // Moderator permission is required
            return getBuilder(not_allowed).build();
        }

        return null;
    }

    /**
     * Helper class handles registration for the {@link JibriEvent}s which are
     * triggered whenever Jibri availability status changes or when it
     * disconnects. It will react only to the events that match {@link #isSIP}
     * with {@link JibriEvent#isSIP()}.
     */
    private class JibriEventHandler extends EventHandlerActivator
    {
        private JibriEventHandler()
        {
            super(new String[]{
                JibriEvent.STATUS_CHANGED, JibriEvent.WENT_OFFLINE });
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
            if (isSIP != jibriEvent.isSIP())
            {
                return;
            }

            switch (topic)
            {
                case JibriEvent.WENT_OFFLINE:
                case JibriEvent.STATUS_CHANGED:
                    synchronized (CommonJibriStuff.this)
                    {
                        updateJibriAvailability();
                    }
                    break;
                default:
                    logger.error("Invalid topic: " + topic);
            }
        }
    }
}
