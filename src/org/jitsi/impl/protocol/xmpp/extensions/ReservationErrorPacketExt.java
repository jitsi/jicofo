/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.protocol.xmpp.extensions;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;

/**
 * Custom packets extension indicates error returned by reservation system.
 *
 * @author Pawel Domas
 */
public class ReservationErrorPacketExt
    extends AbstractPacketExtension
{
    /**
     * XML namespace of this packets extension.
     */
    public static final String NAMESPACE = ConferenceIq.NAMESPACE;

    /**
     * XML element name of this packets extension.
     */
    public static final String ELEMENT_NAME = "reservation-error";

    /**
     * The name of XML attribute that holds error code returned by
     * the reservation system.
     */
    public static final String ERROR_CODE_ATTR_NAME = "error-code";

    /**
     * Creates new instance of <tt>ReservationErrorPacketExt</tt>.
     */
    public ReservationErrorPacketExt()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Sets new value for error code attribute.
     * @param code error code value to set or <tt>-1</tt> to remove
     *             the attribute.
     */
    public void setErrorCode(int code)
    {
        if (code == -1)
        {
            setAttribute(ERROR_CODE_ATTR_NAME, null);
        }
        else
        {
            setAttribute(ERROR_CODE_ATTR_NAME, code);
        }
    }

    /**
     * Returns error code attribute value or <tt>-1</tt> if is unspecified.
     */
    public int getErrorCode()
    {
        return getAttributeAsInt(ERROR_CODE_ATTR_NAME, -1);
    }
}
