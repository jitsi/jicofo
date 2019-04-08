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

import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

public class XmppPeer
    implements IQRequestHandler
{
    private final XmppConnection connection;

    private final List<IQ> iqs = new ArrayList<>();

    public XmppPeer(String jid)
    {
        this(jidCreate(jid), new MockXmppConnection(jidCreate(jid)));
    }

    private static Jid jidCreate(String jid)
    {
        try
        {
            return JidCreate.from(jid);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }
    }

    public XmppPeer(Jid jid, XmppConnection connection)
    {
        this.connection = connection;
    }

    public XmppConnection getConnection()
    {
        return connection;
    }

    public void start()
    {
        this.connection.registerIQRequestHandler(this);
    }

    public void stop()
    {
        this.connection.unregisterIQRequestHandler(this);
    }

    public int getIqCount()
    {
        synchronized (iqs)
        {
            return iqs.size();
        }
    }

    public IQ getIq(int idx)
    {
        synchronized (iqs)
        {
            return iqs.get(idx);
        }
    }

    @Override
    public IQ handleIQRequest(IQ iqRequest)
    {
        synchronized (iqs)
        {
            iqs.add(iqRequest);
        }

        return IQ.createErrorResponse(
                iqRequest,
                XMPPError.Condition.feature_not_implemented);
    }

    @Override
    public Mode getMode()
    {
        return Mode.sync;
    }

    @Override
    public IQ.Type getType()
    {
        return IQ.Type.get;
    }

    @Override
    public String getElement()
    {
        return JingleIQ.ELEMENT_NAME;
    }

    @Override
    public String getNamespace()
    {
        return JingleIQ.NAMESPACE;
    }
}
