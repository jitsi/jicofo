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
import net.java.sip.communicator.util.*;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Class provides template implementation of {@link OperationSetJingle}.
 *
 * @author Pawel Domas
 */
public abstract class AbstractOperationSetJingle
    implements OperationSetJingle
{
    /**
     * The logger.
     */
    private static final Logger logger
        = Logger.getLogger(AbstractOperationSetJingle.class);

    /**
     * The list of active Jingle session.
     */
    protected Map<String, JingleSession> sessions
        = new HashMap<String, JingleSession>();

    /**
     * Implementing classes should return our JID here.
     */
    protected abstract String getOurJID();

    /**
     * Returns {@link XmppConnection} implementation.
     */
    protected abstract XmppConnection getConnection();

    /**
     * Finds Jingle session for given session identifier.
     *
     * @param sid the identifier of the session for which we're looking for.
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
     */
    @Override
    public boolean initiateSession(boolean useBundle,
                                String address,
                                List<ContentPacketExtension> contents,
                                JingleRequestHandler requestHandler,
                                boolean[] startMuted)
    {
        logger.info("INVITE PEER: " + address);

        String sid = JingleIQ.generateSID();

        JingleSession session = new JingleSession(sid, address, requestHandler);

        sessions.put(sid, session);

        JingleIQ inviteIQ
            = JinglePacketFactory.createSessionInitiate(
                    getOurJID(),
                    address,
                    sid,
                    contents);

        if (useBundle)
        {
            GroupPacketExtension group
                = GroupPacketExtension.createBundleGroup(contents);

            inviteIQ.addExtension(group);

            for (ContentPacketExtension content : contents)
            {
                // FIXME: is it mandatory ?
                // http://estos.de/ns/bundle
                content.addChildExtension(
                    new BundlePacketExtension());
            }
        }

        if(startMuted[0] || startMuted[1])
        {
            StartMutedPacketExtension startMutedExt
                = new StartMutedPacketExtension();
            startMutedExt.setAudioMute(startMuted[0]);
            startMutedExt.setVideoMute(startMuted[1]);
            inviteIQ.addExtension(startMutedExt);
        }

        IQ reply = (IQ) getConnection().sendPacketAndGetReply(inviteIQ);
        if (reply != null && IQ.Type.RESULT.equals(reply.getType()))
        {
            return true;
        }
        else
        {
            if (reply == null)
            {
                logger.error(
                    "Timeout waiting for session-accept from " + address);
            }
            else
            {
                logger.error(
                    "Failed to send session-initiate to " + address
                        + ", error: " + reply.getError());
            }
            return false;
        }
    }

    /**
     * The logic for processing received JingleIQs.
     *
     * @param iq the <tt>JingleIQ</tt> to process.
     */
    protected void processJingleIQ(JingleIQ iq)
    {
        JingleSession session = getSession(iq.getSID());
        JingleAction action = iq.getAction();

        if (action == null)
        {
            // bad-request
            IQ badRequest = IQ.createErrorResponse(
                iq, new XMPPError(XMPPError.Condition.bad_request));

            getConnection().sendPacket(badRequest);

            return;
        }
        // Ack all "set" requests.
        if(iq.getType() == IQ.Type.SET)
        {
            IQ ack = IQ.createResultIQ(iq);

            getConnection().sendPacket(ack);
        }

        if (session == null)
        {
            logger.error(
                "Action: " + action
                    + ", no session found for SID " + iq.getSID());
            return;
        }

        JingleRequestHandler requestHandler = session.getRequestHandler();

        if (JingleAction.SESSION_ACCEPT.equals(action))
        {
            logger.info(session.getAddress() + " real jid: " + iq.getFrom());
            requestHandler.onSessionAccept(
                session, iq.getContentList());
        }
        else if (JingleAction.TRANSPORT_INFO.equals(action))
        {
            requestHandler.onTransportInfo(
                session, iq.getContentList());
        }
        else if (JingleAction.ADDSOURCE.equals(action)
            || JingleAction.SOURCEADD.equals(action))
        {
            requestHandler.onAddSource(session, iq.getContentList());
        }
        else if (JingleAction.REMOVESOURCE.equals(action)
            || JingleAction.SOURCEREMOVE.equals(action))
        {
            requestHandler.onRemoveSource(session, iq.getContentList());
        }
        else
        {
            logger.warn("unsupported action " + action);
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
    public void sendAddSourceIQ(MediaSSRCMap ssrcs,
                                MediaSSRCGroupMap ssrcGroupMap,
                                JingleSession session)
    {
        JingleIQ addSourceIq = new JingleIQ();

        addSourceIq.setAction(JingleAction.SOURCEADD);
        addSourceIq.setFrom(getOurJID());
        addSourceIq.setType(IQ.Type.SET);

        for (String media : ssrcs.getMediaTypes())
        {
            ContentPacketExtension content
                = new ContentPacketExtension();

            content.setName(media);

            RtpDescriptionPacketExtension rtpDesc
                = new RtpDescriptionPacketExtension();

            rtpDesc.setMedia(media);

            content.addChildExtension(rtpDesc);

            for (SourcePacketExtension ssrc : ssrcs.getSSRCsForMedia(media))
            {
                try
                {
                    rtpDesc.addChildExtension(
                        ssrc.copy());
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

                for (SSRCGroup ssrcGroup
                    : ssrcGroupMap.getSSRCGroupsForMedia(media))
                {
                    try
                    {
                        rtpDesc.addChildExtension(ssrcGroup.getExtensionCopy());
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC GROUP error", e);
                    }
                }
            }
        }

        String peerSid = session.getSessionID();

        addSourceIq.setTo(session.getAddress());
        addSourceIq.setSID(peerSid);

        logger.info("Notify add SSRC" + session.getAddress()
                        + " SID: " + peerSid);

        getConnection().sendPacket(addSourceIq);
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
    public void sendRemoveSourceIQ(MediaSSRCMap ssrcs,
                                   MediaSSRCGroupMap ssrcGroupMap,
                                   JingleSession session)
    {
        JingleIQ removeSourceIq = new JingleIQ();

        removeSourceIq.setAction(JingleAction.SOURCEREMOVE);
        removeSourceIq.setFrom(getOurJID());
        removeSourceIq.setType(IQ.Type.SET);

        for (String media : ssrcs.getMediaTypes())
        {
            ContentPacketExtension content
                = new ContentPacketExtension();

            content.setName(media);

            RtpDescriptionPacketExtension rtpDesc
                = new RtpDescriptionPacketExtension();
            rtpDesc.setMedia(media);

            content.addChildExtension(rtpDesc);

            for (SourcePacketExtension ssrc : ssrcs.getSSRCsForMedia(media))
            {
                try
                {
                    rtpDesc.addChildExtension(
                        ssrc.copy());
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

                for (SSRCGroup ssrcGroup
                    : ssrcGroupMap.getSSRCGroupsForMedia(media))
                {
                    try
                    {
                        rtpDesc.addChildExtension(ssrcGroup.getExtensionCopy());
                    }
                    catch (Exception e)
                    {
                        logger.error("Copy SSRC GROUP error", e);
                    }
                }
            }
        }

        String peerSid = session.getSessionID();

        removeSourceIq.setTo(session.getAddress());
        removeSourceIq.setSID(peerSid);

        logger.info("Notify remove SSRC " + session.getAddress()
                        + " SID: " + peerSid);

        XmppConnection connection = getConnection();

        connection.sendPacket(removeSourceIq);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminateHandlersSessions(JingleRequestHandler requestHandler)
    {
        List<JingleSession> sessions
            = new ArrayList<JingleSession>(this.sessions.values());
        for (JingleSession session : sessions)
        {
            if (session.getRequestHandler() == requestHandler)
            {
                terminateSession(session, Reason.GONE);
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
     */
    @Override
    public void terminateSession(JingleSession session, Reason reason)
    {
        logger.info("Terminate session: " + session.getAddress());

        /*
        we do not send session-terminate as muc addresses are invalid at this point
        FIXME: but there is also connection address available */
        JingleIQ terminate
            = JinglePacketFactory.createSessionTerminate(
                    getOurJID(),
                    session.getAddress(),
                    session.getSessionID(),
                    reason, null);

        getConnection().sendPacket(terminate);

        sessions.remove(session.getSessionID());
    }
}
