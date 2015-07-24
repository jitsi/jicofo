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
package org.jitsi.impl.protocol.xmpp.colibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;

import org.jivesoftware.smack.provider.*;

/**
 * Default implementation of {@link OperationSetColibriConference} that uses
 * Smack for handling XMPP connection. Handles conference state, allocates and
 * expires channels.
 *
 * @author Pawel Domas
 */
public class OperationSetColibriConferenceImpl
    implements OperationSetColibriConference
{
    private final static Logger logger = Logger.getLogger
            (OperationSetColibriConferenceImpl.class);

    private XmppConnection connection;

    /**
     * Initializes this operation set.
     *
     * @param connection Smack XMPP connection impl that will be used to send
     *                   and receive XMPP packets.
     */
    public void initialize(XmppConnection connection)
    {
        this.connection = connection;

        // FIXME: Register Colibri
        ProviderManager.getInstance().addIQProvider(
            ColibriConferenceIQ.ELEMENT_NAME,
            ColibriConferenceIQ.NAMESPACE,
            new ColibriIQProvider());

        // FIXME: register Jingle
        ProviderManager.getInstance().addIQProvider(
            JingleIQ.ELEMENT_NAME,
            JingleIQ.NAMESPACE,
            new JingleIQProvider());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ColibriConference createNewConference()
    {
        ColibriConference conf = new ColibriConferenceImpl(connection);
        logger.info("Conference created: " + conf);
        return conf;
    }
}
