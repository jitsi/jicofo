/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock.util;

import org.jitsi.protocol.xmpp.*;

/**
 *
 */
public class UtilityJingleOpSet
    extends AbstractOperationSetJingle
{
    private final String jid;
    private final XmppConnection connection;

    public UtilityJingleOpSet(String ourJid, XmppConnection connection)
    {
        this.jid = ourJid;
        this.connection = connection;
    }

    public void addSession(JingleSession session)
    {
        sessions.put(session.getSessionID(), session);
    }

    @Override
    protected String getOurJID()
    {
        return jid;
    }

    @Override
    protected XmppConnection getConnection()
    {
        return connection;
    }
}
