/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.protocol.xmpp.extensions;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;

/**
 * Packet extension used to indicate application specific error
 * 'session-invalid' which means that the session ID passed in {@link
 * ConferenceIq#SESSION_ID_ATTR_NAME} is not valid.
 *
 * @author Pawel Domas
 */
public class SessionInvalidPacketExtension
    extends AbstractPacketExtension
{
    /**
     * XML namespace of this packet extension.
     */
    public static final String NAMESPACE = ConferenceIq.NAMESPACE;

    /**
     * XML element name of this packet extension.
     */
    public static final String ELEMENT_NAME = "session-invalid";

    /**
     * Creates new instance of <tt>SessionInvalidPacketExtension</tt>
     */
    public SessionInvalidPacketExtension()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }
}
