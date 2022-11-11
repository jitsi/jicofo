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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.jingle.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
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

    public static final JingleStats stats = new JingleStats();

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
        super(JingleIQ.ELEMENT, JingleIQ.NAMESPACE, IQ.Type.set, Mode.sync);
    }

    @Override
    public IQ handleIQRequest(IQ iq)
    {
        JingleIQ jingleIq = (JingleIQ) iq;
        JingleSession session = sessions.get(jingleIq.getSID());
        if (session == null)
        {
            logger.warn("No session found for SID " + jingleIq.getSID());
            return IQ.createErrorResponse(jingleIq, StanzaError.getBuilder(StanzaError.Condition.bad_request).build());
        }

        StanzaError error = session.processIq(jingleIq);
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
     * {@inheritDoc}
     */
    @Override
    public boolean initiateSession(
            Jid to,
            List<ContentPacketExtension> contents,
            List<ExtensionElement> additionalExtensions,
            JingleRequestHandler requestHandler,
            ConferenceSourceMap sources,
            boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException
    {
        JsonMessageExtension jsonSources = null;
        if (encodeSourcesAsJson)
        {
            jsonSources = encodeSourcesAsJson(sources);
        }
        else
        {
            contents = encodeSources(sources, contents);
        }

        JingleIQ inviteIQ = JingleUtilsKt.createSessionInitiate(getOurJID(), to, contents);
        String sid = inviteIQ.getSID();
        JingleSession session = new JingleSession(sid, to, requestHandler);

        inviteIQ.addExtension(GroupPacketExtension.createBundleGroup(inviteIQ.getContentList()));
        additionalExtensions.forEach(inviteIQ::addExtension);
        if (jsonSources != null)
        {
            inviteIQ.addExtension(jsonSources);
        }

        sessions.put(sid, session);
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
    public boolean replaceTransport(
            @NotNull JingleSession session,
            List<ContentPacketExtension> contents,
            List<ExtensionElement> additionalExtensions,
            ConferenceSourceMap sources,
            boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException
    {
        Jid address = session.getAddress();
        if (!sessions.containsValue(session))
        {
            throw new IllegalStateException("Session does not exist for: " + address);
        }
        logger.info("RE-INVITE PEER: " + address);

        JsonMessageExtension jsonSources = null;
        if (encodeSourcesAsJson)
        {
            jsonSources = encodeSourcesAsJson(sources);
        }
        else
        {
            contents = encodeSources(sources, contents);
        }

        JingleIQ jingleIQ = JingleUtilsKt.createTransportReplace(getOurJID(), session, contents);
        jingleIQ.addExtension(GroupPacketExtension.createBundleGroup(jingleIQ.getContentList()));
        additionalExtensions.forEach(jingleIQ::addExtension);
        if (jsonSources != null)
        {
            jingleIQ.addExtension(jsonSources);
        }

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
     * Encodes the sources described in {@code sources} as a {@link JsonMessageExtension} in the compact JSON format
     * (see {@link ConferenceSourceMap#compactJson()}).
     * @return the {@link JsonMessageExtension} encoding {@code sources}.
     */
    private JsonMessageExtension encodeSourcesAsJson(ConferenceSourceMap sources)
    {
        return new JsonMessageExtension("{\"sources\":" + sources.compactJson() + "}");
    }
    /**
     * Encodes the sources described in {@code sources} in the list of Jingle contents. If necessary, new
     * {@link ContentPacketExtension}s are created. Returns the resulting list of {@link ContentPacketExtension} which
     * contains the encoded sources.
     *
     * @param sources the sources to encode.
     * @param contents list of existing {@link ContentPacketExtension} to which to add sources if possible.
     * @return the resulting list of {@link ContentPacketExtension}, which consisnts of {@code contents} plus any new
     * {@link ContentPacketExtension}s that were created.
     */
    private List<ContentPacketExtension> encodeSources(
            ConferenceSourceMap sources,
            List<ContentPacketExtension> contents)
    {
        ContentPacketExtension audioContent
                = contents.stream().filter(c -> c.getName().equals("audio")).findFirst().orElse(null);
        ContentPacketExtension videoContent
                = contents.stream().filter(c -> c.getName().equals("video")).findFirst().orElse(null);

        List<ContentPacketExtension> ret = new ArrayList<>();
        if (audioContent != null)
        {
            ret.add(audioContent);
        }
        if (videoContent != null)
        {
            ret.add(videoContent);
        }

        List<SourcePacketExtension> audioSourceExtensions = sources.createSourcePacketExtensions(MediaType.AUDIO);
        List<SourceGroupPacketExtension> audioSsrcGroupExtensions
                = sources.createSourceGroupPacketExtensions(MediaType.AUDIO);
        List<SourcePacketExtension> videoSourceExtensions = sources.createSourcePacketExtensions(MediaType.VIDEO);
        List<SourceGroupPacketExtension> videoSsrcGroupExtensions
                = sources.createSourceGroupPacketExtensions(MediaType.VIDEO);

        if (!audioSourceExtensions.isEmpty() || !audioSsrcGroupExtensions.isEmpty())
        {
            if (audioContent == null)
            {
                audioContent = new ContentPacketExtension();
                audioContent.setName("audio");
                ret.add(audioContent);
            }

            RtpDescriptionPacketExtension audioDescription
                    = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension.class);
            if (audioDescription == null)
            {
                audioDescription = new RtpDescriptionPacketExtension();
                audioDescription.setMedia("audio");
                audioContent.addChildExtension(audioDescription);
            }

            for (SourcePacketExtension extension : audioSourceExtensions)
            {
                audioDescription.addChildExtension(extension);
            }
            for (SourceGroupPacketExtension extension : audioSsrcGroupExtensions)
            {
                audioDescription.addChildExtension(extension);
            }
        }

        if (!videoSourceExtensions.isEmpty() || !videoSsrcGroupExtensions.isEmpty())
        {
            if (videoContent == null)
            {
                videoContent = new ContentPacketExtension();
                videoContent.setName("video");
                ret.add(videoContent);
            }

            RtpDescriptionPacketExtension videoDescription
                    = videoContent.getFirstChildOfType(RtpDescriptionPacketExtension.class);
            if (videoDescription == null)
            {
                videoDescription = new RtpDescriptionPacketExtension();
                videoDescription.setMedia("video");
                videoContent.addChildExtension(videoDescription);
            }

            for (SourcePacketExtension extension : videoSourceExtensions)
            {
                videoDescription.addChildExtension(extension);
            }
            for (SourceGroupPacketExtension extension : videoSsrcGroupExtensions)
            {
                videoDescription.addChildExtension(extension);
            }
        }

        return ret;
    }

    private JingleIQ createAddSourceIq(ConferenceSourceMap sources, JingleSession session, boolean encodeSourcesAsJson)
    {
        JingleIQ addSourceIq = new JingleIQ(JingleAction.SOURCEADD, session.getSessionID());
        addSourceIq.setFrom(getOurJID());
        addSourceIq.setType(IQ.Type.set);
        addSourceIq.setTo(session.getAddress());
        if (encodeSourcesAsJson)
        {
            addSourceIq.addExtension(encodeSourcesAsJson(sources));
        }
        else
        {
            sources.toJingle().forEach(addSourceIq::addContent);
        }

        logger.debug("Sending source-add to " + session.getAddress()
                + ", SID=" + session.getSessionID() + ", sources=" + sources);
        return addSourceIq;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendAddSourceIQ(ConferenceSourceMap sources, JingleSession session, boolean encodeSourcesAsJson)
    {
        JingleIQ addSourceIq = createAddSourceIq(sources, session, encodeSourcesAsJson);
        UtilKt.tryToSendStanza(getConnection(), addSourceIq);
        stats.stanzaSent(JingleAction.SOURCEADD);
    }

    /**
     * Sends 'source-add' proprietary notification. Wait for response and return status.
     *
     * @param sources the sources to be included in the source-add message.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     * @param encodeSourcesAsJson whether to encode {@code sources} as JSON or standard Jingle.
     * @return {@code true} if the source-add completed successfully.
     */
    public boolean sendAddSourceIQAndGetResult(ConferenceSourceMap sources, JingleSession session,
        boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException
    {
        JingleIQ addSourceIq = createAddSourceIq(sources, session, encodeSourcesAsJson);
        IQ reply = UtilKt.sendIqAndGetResponse(getConnection(), addSourceIq);
        stats.stanzaSent(JingleAction.SOURCEADD);

        if (reply == null)
            return false;
        if (IQ.Type.result.equals(reply.getType()))
            return true;

        logger.error("Failed to do 'source-add' to " + session.getAddress() + ": " + reply.toXML());
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendRemoveSourceIQ(
            ConferenceSourceMap sourcesToRemove,
            JingleSession session,
            boolean encodeSourcesAsJson)
    {
        JingleIQ removeSourceIq = new JingleIQ(JingleAction.SOURCEREMOVE, session.getSessionID());

        removeSourceIq.setFrom(getOurJID());
        removeSourceIq.setType(IQ.Type.set);
        removeSourceIq.setTo(session.getAddress());

        if (encodeSourcesAsJson)
        {
            removeSourceIq.addExtension(encodeSourcesAsJson(sourcesToRemove));
        }
        else
        {
            sourcesToRemove.toJingle().forEach(removeSourceIq::addContent);
        }

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
