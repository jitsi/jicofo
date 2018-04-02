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
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.protocol.xmpp.util.*;

import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
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
    private static final Logger logger
        = Logger.getLogger(AbstractOperationSetJingle.class);

    /**
     * The list of active Jingle sessions.
     */
    protected final Map<String, JingleSession> sessions
        = new ConcurrentHashMap<>();

    protected AbstractOperationSetJingle()
    {
        super(JingleIQ.ELEMENT_NAME, JingleIQ.NAMESPACE,
                IQ.Type.set, Mode.sync);
    }

    @Override
    public IQ handleIQRequest(IQ iqRequest)
    {
        JingleIQ packet = (JingleIQ) iqRequest;
        JingleSession session = getSession(packet.getSID());
        if (session == null)
        {
            logger.error("No session found for SID " + packet.getSID());
            return IQ.createErrorResponse(packet,
                    XMPPError.getBuilder(XMPPError.Condition.bad_request));
        }

        return processJingleIQ(packet);
    }

    /**
     * Implementing classes should return our JID here.
     *
     * @return our JID
     */
    protected abstract Jid getOurJID();

    /**
     * Returns {@link XmppConnection} implementation.
     *
     * @return {@link XmppConnection} implementation
     */
    protected abstract XmppConnection getConnection();

    /**
     * Finds Jingle session for given session identifier.
     *
     * @param sid the identifier of the session which we're looking for.
     *
     * @return Jingle session for given session identifier or <tt>null</tt>
     *         if no such session exists.
     */
    public JingleSession getSession(String sid)
    {
        return sessions.get(sid);
    }

    /**
     * Sends 'session-initiate' to the peer identified by given <tt>address</tt>
     *
     * @param useBundle <tt>true</tt> if invite IQ should include
     *                  {@link GroupPacketExtension}
     * @param address the XMPP address where 'session-initiate' will be sent.
     * @param contents the list of <tt>ContentPacketExtension</tt> describing
     *                 media offer.
     * @param requestHandler <tt>JingleRequestHandler</tt> that will be used
     *                       to process request related to newly created
     *                       JingleSession.
     * @param startMuted if the first element is <tt>true</tt> the participant
     * will start audio muted. if the second element is <tt>true</tt> the
     * participant will start video muted.
     * {@inheritDoc}
     */
    @Override
    public boolean initiateSession(boolean                      useBundle,
                                   Jid                          address,
                                   List<ContentPacketExtension> contents,
                                   JingleRequestHandler         requestHandler,
                                   boolean[]                    startMuted)
        throws OperationFailedException
    {
        logger.info("INVITE PEER: " + address);

        String sid = JingleIQ.generateSID();
        JingleSession session = new JingleSession(sid, address, requestHandler);

        sessions.put(sid, session);

        JingleIQ inviteIQ
            = createInviteIQ(JingleAction.SESSION_INITIATE,
                sid, useBundle, address, contents, startMuted);

        IQ reply = (IQ) getConnection().sendPacketAndGetReply(inviteIQ);

        return wasInviteAccepted(session, reply);
    }

    /**
     * Creates Jingle 'session-initiate' IQ for given parameters.
     *
     * @param sessionId Jingle session ID
     * @param useBundle <tt>true</tt> if bundled transport is being used or
     * <tt>false</tt> otherwise
     * @param address the XMPP address where the IQ will be sent to
     * @param contents the list of Jingle contents which describes the actual
     * offer
     * @param startMuted an array where the first value stands for "start with
     * audio muted" and the seconds one for "start video muted"
     *
     * @return New instance of <tt>JingleIQ</tt> filled up with the details
     * provided as parameters.
     */
    private JingleIQ createInviteIQ(JingleAction                    action,
                                    String                          sessionId,
                                    boolean                         useBundle,
                                    Jid                             address,
                                    List<ContentPacketExtension>    contents,
                                    boolean[]                       startMuted)
    {
        JingleIQ inviteIQ = new JingleIQ(action, sessionId);

        inviteIQ.setTo(address);
        inviteIQ.setFrom(getOurJID());
        inviteIQ.setInitiator(getOurJID());
        inviteIQ.setType(IQ.Type.set);

        for(ContentPacketExtension content : contents)
        {
            inviteIQ.addContent(content);
        }

        if (useBundle)
        {
            GroupPacketExtension group
                = GroupPacketExtension.createBundleGroup(contents);

            inviteIQ.addExtension(group);

            for (ContentPacketExtension content : contents)
            {
                // FIXME: is it mandatory ?
                // http://estos.de/ns/bundle
                content.addChildExtension(new BundlePacketExtension());
            }
        }

        // FIXME Move this to a place where offer's contents are created or
        // convert the array to a list of extra PacketExtensions
        if(startMuted[0] || startMuted[1])
        {
            StartMutedPacketExtension startMutedExt
                = new StartMutedPacketExtension();
            startMutedExt.setAudioMute(startMuted[0]);
            startMutedExt.setVideoMute(startMuted[1]);
            inviteIQ.addExtension(startMutedExt);
        }

        return inviteIQ;
    }

    /**
     * Determines whether a specific {@link JingleSession} has been accepted by
     * the client judging by a specific {@code reply} {@link IQ} (received in
     * reply to an invite IQ sent withing the specified {@code JingleSession}).
     *
     * @param session <tt>JingleSession</tt> instance for which we're evaluating
     * the response value.
     * @param reply <tt>IQ</tt> response to Jingle invite IQ or <tt>null</tt> in
     * case of timeout.
     *
     * @return <tt>true</tt> if the invite IQ to which {@code reply} replies is
     * considered accepted; <tt>false</tt>, otherwise.
     */
    private boolean wasInviteAccepted(JingleSession session, IQ reply)
    {
        if (reply == null)
        {
            // XXX By the time the acknowledgement timeout occurs, we may have
            // received and acted upon the session-accept. We have seen that
            // happen multiple times: the conference is established, the media
            // starts flowing between the participants (i.e. we have acted upon
            // the session-accept), and the conference is suddenly torn down
            // (because the acknowldegment timeout has occured eventually). As a
            // workaround, we will ignore the lack of the acknowledgment if we
            // have already acted upon the session-accept.
            if (session.isAccepted())
            {
                return true;
            }
            else
            {
                logger.error(
                        "Timeout waiting for RESULT response to "
                            + "'session-initiate' request from "
                            + session.getAddress());
                return false;
            }
        }
        else if (IQ.Type.result.equals(reply.getType()))
        {
            return true;
        }
        else
        {
            logger.error(
                    "Failed to send 'session-initiate' to "
                        + session.getAddress() + ", error: "
                        + reply.getError());
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean replaceTransport(boolean                         useBundle,
                                    JingleSession                   session,
                                    List<ContentPacketExtension>    contents,
                                    boolean[]                       startMuted)
        throws OperationFailedException
    {
        Jid address = session.getAddress();

        logger.info("RE-INVITE PEER: " + address);

        if (!sessions.containsValue(session))
        {
            throw new IllegalStateException(
                    "Session does not exist for: " + address);
        }

        // Reset 'accepted' flag on the session
        session.setAccepted(false);

        JingleIQ inviteIQ
            =  createInviteIQ(JingleAction.TRANSPORT_REPLACE,
                    session.getSessionID(), useBundle, address,
                    contents, startMuted);

        IQ reply = getConnection().sendPacketAndGetReply(inviteIQ);

        return wasInviteAccepted(session, reply);
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
            return IQ.createErrorResponse(
                iq, XMPPError.getBuilder(XMPPError.Condition.bad_request));
        }

        if (session == null)
        {
            logger.error(
                "Action: " + action
                    + ", no session found for SID " + iq.getSID());
            return IQ.createErrorResponse(
                iq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        JingleRequestHandler requestHandler = session.getRequestHandler();
        XMPPError error = null;
        switch (action)
        {
        case SESSION_ACCEPT:
            error = requestHandler.onSessionAccept(session, iq.getContentList());
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
     * @param ssrcs the map of media SSRCs that will be included in
     *              the notification.
     * @param ssrcGroupMap the map of media SSRC groups that will be included in
     *                     the notification.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    @Override
    public void sendAddSourceIQ(MediaSourceMap ssrcs,
                                MediaSourceGroupMap ssrcGroupMap,
                                JingleSession        session)
    {
        JingleIQ addSourceIq
            = new JingleIQ(JingleAction.SOURCEADD, session.getSessionID());

        addSourceIq.setFrom(getOurJID());
        addSourceIq.setType(IQ.Type.set);

        for (String media : ssrcs.getMediaTypes())
        {
            ContentPacketExtension content
                = new ContentPacketExtension();

            content.setName(media);

            RtpDescriptionPacketExtension rtpDesc
                = new RtpDescriptionPacketExtension();

            rtpDesc.setMedia(media);

            content.addChildExtension(rtpDesc);

            for (SourcePacketExtension ssrc : ssrcs.getSourcesForMedia(media))
            {
                try
                {
                    rtpDesc.addChildExtension(ssrc.copy());
                }
                catch (Exception e)
                {
                    logger.error("Copy SSRC error", e);
                }
            }

            addSourceIq.addContent(content);
        }

        if (ssrcGroupMap != null)
        {
            for (String media : ssrcGroupMap.getMediaTypes())
            {
                ContentPacketExtension content
                    = addSourceIq.getContentByName(media);
                RtpDescriptionPacketExtension rtpDesc;

                if (content == null)
                {
                    // It means content was not created when adding SSRCs...
                    logger.warn(
                        "No SSRCs to be added when group exists for media: "
                            + media);

                    content = new ContentPacketExtension();
                    content.setName(media);
                    addSourceIq.addContent(content);

                    rtpDesc = new RtpDescriptionPacketExtension();
                    rtpDesc.setMedia(media);
                    content.addChildExtension(rtpDesc);
                }
                else
                {
                    rtpDesc = content.getFirstChildOfType(
                        RtpDescriptionPacketExtension.class);
                }

                for (SourceGroup sourceGroup
                    : ssrcGroupMap.getSourceGroupsForMedia(media))
                {
                    try
                    {
                        rtpDesc.addChildExtension(sourceGroup.getExtensionCopy());
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC GROUP error", e);
                    }
                }
            }
        }

        addSourceIq.setTo(session.getAddress());

        logger.info(
            "Notify add SSRC " + session.getAddress()
                + " SID: " + session.getSessionID() + " "
                + ssrcs + " " + ssrcGroupMap);

        getConnection().sendStanza(addSourceIq);
    }

    /**
     * Sends 'source-remove' notification to the peer of given
     * <tt>JingleSession</tt>.
     *
     * @param ssrcs the map of media SSRCs that will be included in
     *              the notification.
     * @param ssrcGroupMap the map of media SSRC groups that will be included in
     *                     the notification.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    @Override
    public void sendRemoveSourceIQ(MediaSourceMap ssrcs,
                                   MediaSourceGroupMap ssrcGroupMap,
                                   JingleSession        session)
    {
        JingleIQ removeSourceIq = new JingleIQ(JingleAction.SOURCEREMOVE,
                session.getSessionID());

        removeSourceIq.setFrom(getOurJID());
        removeSourceIq.setType(IQ.Type.set);

        for (String media : ssrcs.getMediaTypes())
        {
            ContentPacketExtension content
                = new ContentPacketExtension();

            content.setName(media);

            RtpDescriptionPacketExtension rtpDesc
                = new RtpDescriptionPacketExtension();
            rtpDesc.setMedia(media);

            content.addChildExtension(rtpDesc);

            for (SourcePacketExtension ssrc : ssrcs.getSourcesForMedia(media))
            {
                try
                {
                    rtpDesc.addChildExtension(ssrc.copy());
                }
                catch (Exception e)
                {
                    logger.error("Copy SSRC error", e);
                }
            }

            removeSourceIq.addContent(content);
        }

        if (ssrcGroupMap != null)
        {
            for (String media : ssrcGroupMap.getMediaTypes())
            {
                ContentPacketExtension content
                    = removeSourceIq.getContentByName(media);
                RtpDescriptionPacketExtension rtpDesc;

                if (content == null)
                {
                    // It means content was not created when adding SSRCs...
                    logger.warn(
                        "No SSRCs to be removed when group exists for media: "
                            + media);

                    content = new ContentPacketExtension();
                    content.setName(media);
                    removeSourceIq.addContent(content);

                    rtpDesc = new RtpDescriptionPacketExtension();
                    rtpDesc.setMedia(media);
                    content.addChildExtension(rtpDesc);
                }
                else
                {
                    rtpDesc = content.getFirstChildOfType(
                        RtpDescriptionPacketExtension.class);
                }

                for (SourceGroup sourceGroup
                    : ssrcGroupMap.getSourceGroupsForMedia(media))
                {
                    try
                    {
                        rtpDesc.addChildExtension(sourceGroup.getExtensionCopy());
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC GROUP error", e);
                    }
                }
            }
        }

        removeSourceIq.setTo(session.getAddress());

        logger.info(
            "Notify remove SSRC " + session.getAddress()
                + " SID: " + session.getSessionID() + " "
                + ssrcs + " " + ssrcGroupMap);

        getConnection().sendStanza(removeSourceIq);
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
                terminateSession(session, Reason.GONE, null);
            }
        }
    }

    /**
     * Terminates given Jingle session by sending 'session-terminate' with some
     * {@link Reason} if provided.
     *
     * @param session the <tt>JingleSession</tt> to terminate.
     * @param reason one of {@link Reason} enum that indicates why the session
     *               is being ended or <tt>null</tt> to omit.
     * {@inheritDoc}
     */
    @Override
    public void terminateSession(JingleSession    session,
                                 Reason           reason,
                                 String           message)
    {
        logger.info("Terminate session: " + session.getAddress());

        // we do not send session-terminate as muc addresses are invalid at this
        // point
        // FIXME: but there is also connection address available
        JingleIQ terminate
            = JinglePacketFactory.createSessionTerminate(
                    getOurJID(),
                    session.getAddress(),
                    session.getSessionID(),
                    reason,
                    message);

        getConnection().sendStanza(terminate);

        sessions.remove(session.getSessionID());
    }
}
