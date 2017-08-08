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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.EntityFullJid;

/**
 * Implementation of {@link OperationSetJingleImpl} for
 * {@link XmppProtocolProvider}.
 *
 * @author Pawel Domas
 */
public class OperationSetJingleImpl
    extends AbstractOperationSetJingle
{
    /**
     * The logger used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(OperationSetJingleImpl.class);

    private final XmppConnection connection;

    /**
     * Creates new instance of <tt>OperationSetJingleImpl</tt>.
     *
     * @param connection connection where the IQ handler is registered
     */
    public OperationSetJingleImpl(XmppConnection connection)
    {
        this.connection = connection;
    }

    /**
     * Returns our XMPP address that will be used as 'from' attribute
     * in Jingle QIs.
     */
    protected EntityFullJid getOurJID()
    {
        return connection.getUser();
    }

    /**
     * {@inheritDoc}
     */
    protected XmppConnection getConnection()
    {
        return connection;
    }
}
