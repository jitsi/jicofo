/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017-Present 8x8, Inc.
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
package org.jitsi.jicofo.recording.jibri;

import org.jitsi.jicofo.util.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * This operation is basically just an IQ handler for {@link JibriIq}s.
 * However, all conferences register here so that they can get a hold of
 * the incoming Jibri IQs and process them.
 */
public class OperationSetJibri
    extends AbstractIqRequestHandler
    implements RegistrationListener
{
    private final List<CommonJibriStuff> jibris = Collections.synchronizedList(new LinkedList<>());

    private final XmppProvider xmppProvider;

    /**
     * Creates a new instance of this class.
     *
     * @param xmppProvider the XMPP to which this instance is bound.
     */
    public OperationSetJibri(XmppProvider xmppProvider)
    {
        super(JibriIq.ELEMENT_NAME, JibriIq.NAMESPACE, IQ.Type.set, Mode.async);
        this.xmppProvider = xmppProvider;
        xmppProvider.addRegistrationListener(this);
    }

    /**
     * Register a Jibri instance for IQ processing.
     *
     * @param jibri The Jibri instance that is interested in {@link JibriIq}s.
     */
    public void addJibri(CommonJibriStuff jibri)
    {
        jibris.add(jibri);
    }

    /**
     * Removes a Jibri handler from receiving IQs.
     *
     * @param jibri the Jibri handler to remove.
     */
    public void removeJibri(CommonJibriStuff jibri)
    {
        jibris.remove(jibri);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQ handleIQRequest(IQ iq)
    {
        CommonJibriStuff theJibri = null;

        synchronized (jibris)
        {
            for (CommonJibriStuff jibri : jibris)
            {
                if (jibri.accept((JibriIq) iq))
                {
                    theJibri = jibri;
                    break;
                }
            }
        }

        if (theJibri != null)
        {
            return theJibri.handleIQRequest((JibriIq) iq);
        }

        return ErrorResponse.create(
                iq, XMPPError.Condition.item_not_found, null);
    }

    @Override
    public void registrationChanged(boolean registered)
    {
        // Do initializations which require valid connection
        if (registered)
        {
            XMPPConnection xmppConnection = xmppProvider.getXmppConnection();
            if (xmppConnection == null)
            {
                throw new IllegalStateException("XMPPConnection is null while registered.");
            }
            xmppConnection.registerIQRequestHandler(this);
        }
    }
}
