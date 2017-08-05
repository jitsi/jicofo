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
package mock.util;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.concurrent.*;

public class UtilityJingleOpSet
    extends AbstractOperationSetJingle
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(UtilityJingleOpSet.class);

    private final Jid jid;
    private final XmppConnection connection;

    private final BlockingQueue<JingleIQ> sessionInvites
            = new LinkedBlockingQueue<>();

    public UtilityJingleOpSet(Jid ourJid, XmppConnection connection)
    {
        this.jid = ourJid;
        this.connection = connection;
    }

    public void init()
    {
        connection.addPacketHandler(new StanzaListener()
        {
            @Override
            public void processStanza(Stanza packet)
                    throws InterruptedException
            {
                JingleIQ jingleIQ = (JingleIQ) packet;

                String sid = jingleIQ.getSID();

                if (sessions.containsKey(sid))
                {
                    logger.error(
                        "Received session-initiate for existing session: " + sid);
                    return;
                }

                sessionInvites.put(jingleIQ);
            }
        },
        new StanzaFilter()
        {
            @Override
            public boolean accept(Stanza packet)
            {
                if (!getOurJID().equals(packet.getTo()))
                    return false;

                if (!(packet instanceof JingleIQ))
                    return false;

                JingleIQ jingleIQ = (JingleIQ) packet;

                return
                    JingleAction.SESSION_INITIATE.equals(jingleIQ.getAction());
            }
        });
    }

    @Override
    protected Jid getOurJID()
    {
        return jid;
    }

    @Override
    protected XmppConnection getConnection()
    {
        return connection;
    }

    public JingleIQ acceptSession(
        long timeout, final JingleRequestHandler requestHandler)
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
        JingleSession session
            = new JingleSession(sid, invite.getFrom(), requestHandler);

        sessions.put(sid, session);
        return invite;
    }
}
