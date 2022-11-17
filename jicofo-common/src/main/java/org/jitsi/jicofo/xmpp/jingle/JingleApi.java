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
package org.jitsi.jicofo.xmpp.jingle;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.Jid;

import java.util.*;

/**
 * @author Pawel Domas
 */
public class JingleApi
    extends AbstractIqRequestHandler
{
    private static final Logger logger = new LoggerImpl(JingleApi.class.getName());

    /**
     * The list of active Jingle sessions.
     */
    protected final WeakValueMap<String, JingleSession> sessions = new WeakValueMap<>();

    @NotNull
    private final AbstractXMPPConnection xmppConnection;

    public JingleApi(@NotNull AbstractXMPPConnection xmppConnection)
    {
        super(JingleIQ.ELEMENT, JingleIQ.NAMESPACE, IQ.Type.set, Mode.sync);
        this.xmppConnection = xmppConnection;
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
    public Jid getOurJID()
    {
        return getConnection().getUser();
    }

    @NotNull
    public AbstractXMPPConnection getConnection()
    {
        return xmppConnection;
    }

    public void registerSession(JingleSession session)
    {
        String sid = session.getSessionID();
        JingleSession existingSession = sessions.get(sid);
        if (existingSession != null)
        {
            logger.warn("Replacing existing session with SID " + sid);
        }
        sessions.put(sid, session);
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
    public static List<ContentPacketExtension> encodeSources(
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

    public void removeSession(@NotNull JingleSession session)
    {
        sessions.remove(session.getSessionID());
    }
}
