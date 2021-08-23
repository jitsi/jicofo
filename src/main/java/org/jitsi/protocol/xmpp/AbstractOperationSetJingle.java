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
package org.jitsi.protocol.xmpp;

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.protocol.xmpp.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.jxmpp.jid.Jid;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class provides template implementation of {@link OperationSetJingle}.
 *
 * @author Pawel Domas
 */
public abstract class AbstractOperationSetJingle
    extends AbstractIqRequestHandler
    implements OperationSetJingle
{
    /**
     * The {@code Logger} used by the class {@code AbstractOperationSetJingle}
     * and its instances to print debug-related information.
     */
    private static final Logger logger = new LoggerImpl(AbstractOperationSetJingle.class.getName());

    private static final JingleStats stats = new JingleStats();

    public static JSONObject getStats()
    {
        return stats.toJson();
    }

    /**
     * The list of active Jingle sessions.
     */
    protected final Map<String, JingleSession> sessions = new ConcurrentHashMap<>();

    protected AbstractOperationSetJingle()
    {
        super(JingleIQ.ELEMENT_NAME, JingleIQ.NAMESPACE, IQ.Type.set, Mode.sync);
    }

    @Override
    public IQ handleIQRequest(IQ iqRequest)
    {
        JingleIQ packet = (JingleIQ) iqRequest;
        JingleSession session = getSession(packet.getSID());
        if (session == null)
        {
            logger.warn("No session found for SID " + packet.getSID());
            return IQ.createErrorResponse(packet, XMPPError.getBuilder(XMPPError.Condition.bad_request));
        }

        return processJingleIQ(packet);
    }

    /**
     * Implementing classes should return our JID here.
     *
     * @return our JID
     */
    @Override
    public Jid getOurJID()
    {
        return getConnection().getUser();
    }

    protected abstract AbstractXMPPConnection getConnection();

    /**
     * Finds Jingle session for given session identifier.
     *
     * @param sid the identifier of the session which we're looking for.
     * @return Jingle session for given session identifier or <tt>null</tt> if
     * no such session exists.
     */
    public JingleSession getSession(String sid)
    {
        return sessions.get(sid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean initiateSession(JingleIQ inviteIQ, JingleRequestHandler requestHandler)
        throws SmackException.NotConnectedException
    {
        String sid = inviteIQ.getSID();
        JingleSession session = new JingleSession(sid, inviteIQ.getTo(), requestHandler);

        sessions.put(sid, session);

        inviteIQ.addExtension(GroupPacketExtension.createBundleGroup(inviteIQ.getContentList()));
        IQ reply = UtilKt.sendIqAndGetResponse(getConnection(), inviteIQ);
        stats.stanzaSent(inviteIQ.getAction());

        if (reply == null || IQ.Type.result.equals(reply.getType()))
        {
            return true;
        }
        else
        {
            logger.error(
                    "Unexpected response to 'session-initiate' from " + session.getAddress() + ": " + reply.toXML());
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean replaceTransport(JingleIQ jingleIQ, JingleSession session)
        throws SmackException.NotConnectedException
    {
        Jid address = session.getAddress();

        logger.info("RE-INVITE PEER: " + address);

        if (!sessions.containsValue(session))
        {
            throw new IllegalStateException("Session does not exist for: " + address);
        }

        jingleIQ.addExtension(GroupPacketExtension.createBundleGroup(jingleIQ.getContentList()));

        IQ reply = UtilKt.sendIqAndGetResponse(getConnection(), jingleIQ);
        stats.stanzaSent(jingleIQ.getAction());

        if (reply == null || IQ.Type.result.equals(reply.getType()))
        {
            return true;
        }
        else
        {
            logger.error(
                    "Unexpected response to 'transport-replace' from " + session.getAddress() + ": " + reply.toXML());
            return false;
        }
    }

    /**
     * The logic for processing received <tt>JingleIQ</tt>s.
     *
     * @param iq the <tt>JingleIQ</tt> to process.
     */
    protected IQ processJingleIQ(JingleIQ iq)
    {
        JingleSession session = getSession(iq.getSID());
        JingleAction action = iq.getAction();

        if (action == null)
        {
            // bad-request
            return IQ.createErrorResponse(iq, XMPPError.getBuilder(XMPPError.Condition.bad_request));
        }
        stats.stanzaReceived(action);

        if (session == null)
        {
            logger.warn("Action: " + action + ", no session found for SID " + iq.getSID());
            return IQ.createErrorResponse(iq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        JingleRequestHandler requestHandler = session.getRequestHandler();
        XMPPError error = null;
        switch (action)
        {
        case SESSION_ACCEPT:
            error = requestHandler.onSessionAccept(session, iq.getContentList());
            break;
        case SESSION_INFO:
            error = requestHandler.onSessionInfo(session, iq);
            break;
        case SESSION_TERMINATE:
            error = requestHandler.onSessionTerminate(session, iq);
            break;
        case TRANSPORT_ACCEPT:
            error = requestHandler.onTransportAccept(session, iq.getContentList());
            break;
        case TRANSPORT_INFO:
            requestHandler.onTransportInfo(session, iq.getContentList());
            break;
        case TRANSPORT_REJECT:
            requestHandler.onTransportReject(session, iq);
            break;
        case ADDSOURCE:
        case SOURCEADD:
            error = requestHandler.onAddSource(session, iq.getContentList());
            break;
        case REMOVESOURCE:
        case SOURCEREMOVE:
            error = requestHandler.onRemoveSource(session, iq.getContentList());
            break;
        default:
            error = XMPPError.getBuilder(XMPPError.Condition.feature_not_implemented).build();
            logger.warn("unsupported action " + action);
        }

        // FIXME IQ type is not taken into account
        if (error == null)
        {
            return IQ.createResultIQ(iq);
        }
        else
        {
            return IQ.createErrorResponse(iq, error);
        }
    }

    /**
     * Sends 'source-add' notification to the peer of given
     * <tt>JingleSession</tt>.
     *
     * @param sources the sources to be included in the source-add message.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    @Override
    public void sendAddSourceIQ(ConferenceSourceMap sources, JingleSession session)
    {
        JingleIQ addSourceIq = new JingleIQ(JingleAction.SOURCEADD, session.getSessionID());
        addSourceIq.setFrom(getOurJID());
        addSourceIq.setType(IQ.Type.set);
        addSourceIq.setTo(session.getAddress());
        List<ContentPacketExtension> contents = sources.toJingle();
        contents.forEach(addSourceIq::addContent);

        logger.debug("Sending source-add to " + session.getAddress()
                + ", SID=" + session.getSessionID() + ", sources= " + sources);

        UtilKt.tryToSendStanza(getConnection(), addSourceIq);
        stats.stanzaSent(JingleAction.SOURCEADD);
    }

    /**
     * Sends 'source-remove' notification to the peer of given
     * <tt>JingleSession</tt>.
     *
     * @param sourcesToRemove the sources to remove.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    @Override
    public void sendRemoveSourceIQ(ConferenceSourceMap sourcesToRemove, JingleSession session)
    {
        JingleIQ removeSourceIq = new JingleIQ(JingleAction.SOURCEREMOVE, session.getSessionID());

        removeSourceIq.setFrom(getOurJID());
        removeSourceIq.setType(IQ.Type.set);
        removeSourceIq.setTo(session.getAddress());
        sourcesToRemove.toJingle().forEach(removeSourceIq::addContent);

        logger.debug(
            "Sending source-remove to " + session.getAddress() + ", SID=" + session.getSessionID()
                    + ", sources=" + sourcesToRemove);

        UtilKt.tryToSendStanza(getConnection(), removeSourceIq);
        stats.stanzaSent(JingleAction.SOURCEREMOVE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminateHandlersSessions(JingleRequestHandler requestHandler)
    {
        List<JingleSession> sessions = new ArrayList<>(this.sessions.values());

        for (JingleSession session : sessions)
        {
            if (session.getRequestHandler() == requestHandler)
            {
                terminateSession(session, Reason.GONE, null, true);
            }
        }
    }

    /**
     * Terminates given Jingle session. This method is to be called either to send 'session-terminate' or to inform
     * this operation set that the session has been terminated as a result of 'session-terminate' received from
     * the other peer in which case {@code sendTerminate} should be set to {@code false}.
     *
     * @param session the <tt>JingleSession</tt> to terminate.
     * @param reason one of {@link Reason} enum that indicates why the session
     *               is being ended or <tt>null</tt> to omit.
     * @param sendTerminate when {@code true} it means that a 'session-terminate' is to be sent, otherwise it means
     * the session is being ended on the remote peer's request.
     * {@inheritDoc}
     */
    @Override
    public void terminateSession(
            JingleSession session,
            Reason reason,
            String message,
            boolean sendTerminate)
    {
        logger.info(String.format(
                "Terminate session: %s, reason: %s, send terminate: %s",
                session.getAddress(),
                reason,
                sendTerminate));

        if (sendTerminate)
        {
            JingleIQ terminate
                    = JinglePacketFactory.createSessionTerminate(
                    getOurJID(),
                    session.getAddress(),
                    session.getSessionID(),
                    reason,
                    message);

            UtilKt.tryToSendStanza(getConnection(), terminate);
            stats.stanzaSent(JingleAction.SESSION_TERMINATE);
        }

        sessions.remove(session.getSessionID());
    }
}
