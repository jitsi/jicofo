/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.protocol.xmpp.extensions;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;

/**
 * A packet extension used to advertise the name for shared Etherpad document
 * in Jitsi Meet conference.
 *
 * @author Pawel Domas
 */
public class EtherpadPacketExt
    extends AbstractPacketExtension
{
    /**
     * XML namespace of this packets extension.
     */
    public static final String NAMESPACE = "http://jitsi.org/jitmeet/etherpad";

    /**
     * XML element name of this packets extension.
     */
    public static final String ELEMENT_NAME = "etherpad";

    /**
     * Creates new instance of <tt>EtherpadPacketExt</tt>.
     */
    public EtherpadPacketExt()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Sets the <tt>name</tt> of Etherpad document to be shared in Jitsi Meet
     * conference.
     *
     * @param name the name of the document to set.
     */
    public void setDocumentName(String name)
    {
        setText(name);
    }

    /**
     * Returns the name of shared Etherpad document.
     */
    public String getDocumentName()
    {
        return getText();
    }

    /**
     * Return new Etherpad packet extension instance with given document
     * <tt>name</tt>.
     *
     * @param name the name of shared Etherpad document.
     */
    public static EtherpadPacketExt forDocumentName(String name)
    {
        EtherpadPacketExt ext = new EtherpadPacketExt();

        ext.setDocumentName(name);

        return ext;
    }
}
