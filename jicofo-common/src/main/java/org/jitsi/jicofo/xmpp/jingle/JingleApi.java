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
import org.jitsi.jicofo.util.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.Jid;

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

    public void removeSession(@NotNull JingleSession session)
    {
        sessions.remove(session.getSessionID());
    }
}
