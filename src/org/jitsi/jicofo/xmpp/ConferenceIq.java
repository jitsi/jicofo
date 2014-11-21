/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.xmpp;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * FIXME: move to Jitsi ?
 *
 * The IQ used by Jitsi Meet conference participant to contact the focus and ask
 * it to handle the conference in some multi user chat room.
 *
 * @author Pawel Domas
 */
public class ConferenceIq
    extends IQ
{
    /**
     * Focus namespace.
     */
    public final static String NAMESPACE = "http://jitsi.org/protocol/focus";

    /**
     * XML element name for the <tt>ConferenceIq</tt>.
     */
    public static final String ELEMENT_NAME = "conference";

    /**
     * The name of the attribute that stores the name of multi user chat room
     * that is hosting Jitsi Meet conference.
     */
    public static final String ROOM_ATTR_NAME = "room";

    /**
     * The name of the attribute that indicates if the focus has already joined
     * the room(otherwise users might decide not to join yet).
     */
    public static final String READY_ATTR_NAME = "ready";

    /**
     * MUC room name hosting Jitsi Meet conference.
     */
    private String room;

    /**
     * Indicates if the focus is already in the MUC room and conference is ready
     * to be joined.
     */
    private Boolean ready;

    /**
     * Prints attributes in XML format to given <tt>StringBuilder</tt>.
     * @param out the <tt>StringBuilder</tt> instance used to construct XML
     *            representation of this element.
     */
    void printAttributes(StringBuilder out)
    {
        out.append(ROOM_ATTR_NAME)
            .append("=")
            .append("'").append(room).append("' ");

        if (ready != null)
        {
            out.append(READY_ATTR_NAME)
                .append("=")
                .append("'").append(ready).append("' ");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getChildElementXML()
    {
        StringBuilder xml = new StringBuilder();

        xml.append('<').append(ELEMENT_NAME);
        xml.append(" xmlns='").append(NAMESPACE).append("' ");

        printAttributes(xml);

        Collection<PacketExtension> extensions =  getExtensions();
        if (extensions.size() > 0)
        {
            xml.append(">");
            for (PacketExtension extension : extensions)
            {
                xml.append(extension.toXML());
            }
            xml.append("</").append(ELEMENT_NAME).append(">");
        }
        else
        {
            xml.append("/>");
        }

        return xml.toString();
    }

    /**
     * Returns the value of {@link #ready} attribute.
     */
    public Boolean isReady()
    {
        return ready;
    }

    /**
     * Sets the value of {@link #ready} attribute of this <tt>ConferenceIq</tt>.
     * @param ready the value to be set as {@link #ready} attribute value.
     */
    public void setReady(Boolean ready)
    {
        this.ready = ready;
    }

    /**
     * Returns the value of {@link #room} attribute of this
     * <tt>ConferenceIq</tt>.
     */
    public String getRoom()
    {
        return room;
    }

    /**
     * Sets the {@link #room} attribute of this <tt>ConferenceIq</tt>.
     * @param room the value to be set as {@link #room} attribute value.
     */
    public void setRoom(String room)
    {
        this.room = room;
    }
}
