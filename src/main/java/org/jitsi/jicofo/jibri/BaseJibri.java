/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.queue.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.jicofo.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

import static org.jivesoftware.smack.packet.XMPPError.Condition.*;
import static org.jivesoftware.smack.packet.XMPPError.from;
import static org.jivesoftware.smack.packet.XMPPError.getBuilder;

/**
 * Common stuff shared between {@link JibriRecorder} (which can deal with only
 * 1 {@link JibriSession}) and {@link JibriSipGateway} (which is capable of
 * handling multiple, simultaneous {@link JibriSession}s).
 *
 * @author Pawel Domas
 */
public abstract class BaseJibri
{
    /**
     * The length of the session id field we generate to uniquely identify a
     * Jibri session
     */
    static final int SESSION_ID_LENGTH = 16;

    /**
     * The Jitsi Meet conference instance.
     */
    @NotNull
    protected final JitsiMeetConferenceImpl conference;

    /**
     * The {@link ExtendedXmppConnection} used for communication.
     */
    @NotNull
    protected final ExtendedXmppConnection connection;

    @NotNull
    private final XmppProvider xmppProvider;

    /**
     * The logger instance pass to the constructor that wil be used by this
     * instance for logging.
     */
    @NotNull
    protected final Logger logger;

    /**
     * Jibri detector which notifies about Jibri availability status changes.
     */
    @NotNull
    final JibriDetector jibriDetector;

    private final PacketQueue<JibriIq> incomingIqQueue;

    /**
     * Creates new instance of <tt>JibriRecorder</tt>.
     * @param conference <tt>JitsiMeetConference</tt> to be recorded by new instance.
     */
    BaseJibri(
            @NotNull JitsiMeetConferenceImpl conference,
            @NotNull XmppProvider xmppProvider,
            @NotNull Logger logger,
            @NotNull JibriDetector jibriDetector)
    {
        this.xmppProvider = xmppProvider;
        this.connection = xmppProvider.getXmppConnection();
        this.conference = conference;
        this.jibriDetector = jibriDetector;

        this.logger = logger;

        xmppProvider.addJibriIqHandler(this);
        incomingIqQueue = new PacketQueue<>(
                50,
                true,
                "jibri-iq-queue-" + conference.getRoomName().getLocalpart().toString(),
                jibriIq -> {
                    IQ response = doHandleIQRequest(jibriIq);
                    try
                    {
                        xmppProvider.getXmppConnection().sendStanza(response);
                    }
                    catch (SmackException.NotConnectedException e)
                    {
                        logger.warn("Failed to send response, smack is not connected.");
                    }
                    catch (InterruptedException e)
                    {
                        logger.warn("Failed to send response, interrupted.");
                    }
                    return true;
                },
                TaskPools.getIoPool()
        );

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
     * @return a list with all {@link JibriSession}s used by this instance.
     */
    public abstract List<JibriSession> getJibriSessions();

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
     * Method called by {@link JitsiMeetConferenceImpl} when the conference is
     * being stopped.
     */
    public void dispose()
    {
        xmppProvider.removeJibriIqHandler(this);
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

        ChatRoomMember chatMember = conference.findMember(from);
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
     * Enqueue a request, assuming responsibility for sending a response (whether a 'result' or 'error').
     */
    public final void handleIQRequest(JibriIq iq)
    {
        incomingIqQueue.add(iq);
    }

    /**
     * Handles an incoming Jibri IQ from either a jibri instance or a participant in the conference. This may block
     * waiting for a response over the network.
     *
     * @return the IQ to be sent back as a response ('result' or 'error').
     */
    private IQ doHandleIQRequest(JibriIq iq)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Jibri request. IQ: " + iq.toXML());
        }

        // Process if it belongs to an active recording session
        JibriSession session = getJibriSessionForMeetIq(iq);
        if (session != null && session.accept(iq))
        {
            return session.processJibriIqRequestFromJibri(iq);
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
        if (JibriIq.Action.START.equals(action))
        {
            if (jibriSession == null)
            {
                return handleStartRequest(iq);
            }
            else
            {
                // If there's a session active, we know there are Jibri's connected
                // (so it isn't XMPPError.Condition.service_unavailable), so it
                // must be that they're all busy.
                logger.info("Failed to start a Jibri session, all Jibris were busy");
                return ErrorResponse.create(
                        iq,
                        XMPPError.Condition.resource_constraint,
                        "all Jibris are busy");
            }
        }
        // stop ?
        else if (JibriIq.Action.STOP.equals(action) &&
            jibriSession != null)
        {
            jibriSession.stop(iq.getFrom());
            return IQ.createResultIQ(iq);
        }

        logger.warn("Discarded: " + iq.toXML() + " - nothing to be done, ");

        // Bad request
        return IQ.createErrorResponse(
            iq, from(bad_request, "Unable to handle: " + action));
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

    protected String generateSessionId()
    {
        return Utils.generateSessionId(SESSION_ID_LENGTH);
    }
}
