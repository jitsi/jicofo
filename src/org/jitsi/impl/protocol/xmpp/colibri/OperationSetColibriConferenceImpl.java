/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
