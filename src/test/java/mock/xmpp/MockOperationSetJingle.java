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
package mock.xmpp;

import mock.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Implementation of {@link MockOperationSetJingle} for
 * {@link XmppProtocolProvider}.
 *
 * @author Pawel Domas
 */
public class MockOperationSetJingle
    extends AbstractOperationSetJingle
{
    /**
     * The logger used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(MockOperationSetJingle.class);

    /**
     * Parent {@link XmppProtocolProvider}.
     */
    private final MockProtocolProvider xmppProvider;

    /**
     * Creates new instance of <tt>OperationSetJingleImpl</tt>.
     *
     * @param xmppProvider parent XMPP protocol provider
     */
    public MockOperationSetJingle(MockProtocolProvider xmppProvider)
    {
        Objects.requireNonNull(xmppProvider);
        this.xmppProvider = xmppProvider;
    }

    /**
     * Returns our XMPP address that will be used as 'from' attribute
     * in Jingle QIs.
     */
    protected EntityFullJid getOurJID()
    {
        return getConnection().getUser();
    }

    /**
     * {@inheritDoc}
     */
    protected XmppConnection getConnection()
    {
        return xmppProvider.getXmppConnection();
    }
}
