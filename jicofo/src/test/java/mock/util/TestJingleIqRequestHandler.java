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
package mock.util;

import mock.*;
import mock.xmpp.*;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.xmpp.jingle.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jivesoftware.smack.packet.*;

import java.util.concurrent.*;

public class TestJingleIqRequestHandler
    extends JingleIqRequestHandler
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger = new LoggerImpl(TestJingleIqRequestHandler.class.getName());

    private final BlockingQueue<JingleIQ> sessionInvites = new LinkedBlockingQueue<>();
    public MockParticipant mockParticipant;
    @NotNull private final MockXmppConnection connection;

    public TestJingleIqRequestHandler(MockXmppConnection connection)
    {
        super();
        this.connection = connection;
    }

    @Override
    public IQ handleIQRequest(IQ iqRequest)
    {
        JingleIQ jingleIQ = (JingleIQ) iqRequest;

        switch (jingleIQ.getAction())
        {
            case SESSION_INITIATE:
                try
                {
                    String sid = jingleIQ.getSID();
                    if (sessions.containsKey(sid))
                    {
                        logger.error("Received session-initiate for existing session: " + sid);
                        return IQ.createErrorResponse(
                                jingleIQ,
                                StanzaError.getBuilder(StanzaError.Condition.conflict).build());
                    }
                    sessionInvites.put(jingleIQ);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
                break;
            case SOURCEADD:
            case ADDSOURCE:
            case SOURCEREMOVE:
            case REMOVESOURCE:
                if (mockParticipant != null)
                {
                    mockParticipant.processStanza(iqRequest);
                }
                break;
            default:
                logger.warn("Unhandled Jingle action: " + jingleIQ.getAction());
                break;
        }

        return IQ.createResultIQ(jingleIQ);
    }

    public JingleIQ acceptSession(long timeout)
        throws InterruptedException
    {
        JingleIQ invite;
        long remainingWait = timeout;
        long waitStart = System.currentTimeMillis();
        do
        {
            invite = sessionInvites.poll(remainingWait, TimeUnit.MILLISECONDS);
            remainingWait = timeout - (System.currentTimeMillis() - waitStart);
        }
        while (invite == null && remainingWait > 0);

        if (invite == null)
        {
            return null;
        }

        String sid = invite.getSID();
        JingleSession session = new JingleSession(
                sid, invite.getFrom(), this, connection, new JingleRequestHandler() { }, false);

        sessions.put(sid, session);
        return invite;
    }

    public JingleSession getSession(String sid)
    {
        return sessions.get(sid);
    }
}
