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
package org.jitsi.impl.protocol.xmpp.extensions;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;

import java.util.*;

/**
 * FIXME: move to Jitsi or shared lib eventually
 *
 * The IQ used by Jitsi Meet conference participant to contact the focus and ask
 * it to handle the conference in some multi user chat room.
 *
 * @author Pawel Domas
 */
public class ConferenceIq
    extends AbstractIQ
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
     * The name of the attribute that tells to the user what is
     * the jid of the focus user.
     */
    public static final String FOCUS_JID_ATTR_NAME = "focusjid";

    /**
     * The attribute that holds a string identifier of authentication session.
     */
    public static final String SESSION_ID_ATTR_NAME = "session-id";

    /**
     * The name of the attribute that holds machine unique identifier used to
     * distinguish session for the same user on different machines.
     */
    public static final String MACHINE_UID_ATTR_NAME = "machine-uid";

    /**
     * The name of the attribute that carries user's authenticated identity name
     */
    public static final String IDENTITY_ATTR_NAME = "identity";

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
     * The JID of authenticated focus user.
     */
    private String focusJid;

    /**
     * Client's authentication session ID.
     */
    private String sessionId;

    /**
     * Machine unique identifier.
     */
    private String machineUID;

    /**
     * User's authenticated identity name(login name).
     */
    private String identity;

    /**
     * Creates new instance of <tt>ConferenceIq</tt>.
     */
    public ConferenceIq()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Prints attributes in XML format to given <tt>StringBuilder</tt>.
     * @param out the <tt>StringBuilder</tt> instance used to construct XML
     *            representation of this element.
     */
    @Override
    protected void printAttributes(StringBuilder out)
    {
        printStrAttr(out, ROOM_ATTR_NAME, room);
        printObjAttr(out, READY_ATTR_NAME, ready);
        printStrAttr(out, FOCUS_JID_ATTR_NAME, focusJid);
        printStrAttr(out, SESSION_ID_ATTR_NAME, sessionId);
        printStrAttr(out, MACHINE_UID_ATTR_NAME, machineUID);
        printStrAttr(out, IDENTITY_ATTR_NAME, identity);
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

    /**
     * Returns the value of {@link #FOCUS_JID_ATTR_NAME} held by this IQ.
     */
    public String getFocusJid()
    {
        return focusJid;
    }

    /**
     * Sets the value for the focus JID attribute.
     * @param focusJid a string with the JID of focus user('username@domain').
     */
    public void setFocusJid(String focusJid)
    {
        this.focusJid = focusJid;
    }

    /**
     * Adds property packet extension to this IQ.
     * @param property the instance <tt>Property</tt> to be added to this IQ.
     */
    public void addProperty(Property property)
    {
        addExtension(property);
    }

    /**
     * Returns the list of properties contained in this IQ.
     * @return list of <tt>Property</tt> contained in this IQ.
     */
    public List<Property> getProperties()
    {
        return getChildExtensionsOfType(Property.class);
    }

    /**
     * Converts list of properties contained in this IQ into the name to value
     * mapping.
     * @return the map of property names to values as strings.
     */
    public Map<String, String> getPropertiesMap()
    {
        List<Property> properties = getProperties();
        Map<String, String> propertiesMap= new HashMap<String, String>();

        for (Property property : properties)
        {
            propertiesMap.put(property.getName(), property.getValue());
        }
        return propertiesMap;
    }

    /**
     * Returns the value of {@link ConferenceIq#SESSION_ID_ATTR_NAME}
     * attribute which corresponds to the ID of client authentication
     * session. <tt>null</tt> if not specified.
     */
    public String getSessionId()
    {
        return sessionId;
    }

    /**
     * Sets the value of {@link ConferenceIq#SESSION_ID_ATTR_NAME} attribute.
     * @param sessionId the ID of client's authentication session.
     */
    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    /**
     * Returns the value of {@link #MACHINE_UID_ATTR_NAME} carried by this IQ
     * instance(if any).
     */
    public String getMachineUID()
    {
        return machineUID;
    }

    /**
     * Sets new value for {@link #MACHINE_UID_ATTR_NAME} attribute.
     * @param machineUID machine unique identifier to set.
     */
    public void setMachineUID(String machineUID)
    {
        this.machineUID = machineUID;
    }

    /**
     * Returns the value of {@link #IDENTITY_ATTR_NAME} stored in this IQ.
     */
    public String getIdentity()
    {
        return identity;
    }

    /**
     * Sets new value for {@link #IDENTITY_ATTR_NAME} attribute.
     * @param identity the user's authenticated identity name to set.
     */
    public void setIdentity(String identity)
    {
        this.identity = identity;
    }

    /**
     * Packet extension for configuration properties.
     */
    public static class Property extends AbstractPacketExtension
    {
        /**
         * The name of property XML element.
         */
        public static final String ELEMENT_NAME = "property";

        /**
         * The name of 'name' property attribute.
         */
        public static final String NAME_ATTR_NAME = "name";

        /**
         * The name of 'value' property attribute.
         */
        public static final String VALUE_ATTR_NAME = "value";

        /**
         * Creates new empty <tt>Property</tt> instance.
         */
        public Property()
        {
            super(null, ELEMENT_NAME);
        }

        /**
         * Creates new <tt>Property</tt> instance initialized with given
         * <tt>name</tt> and <tt>value</tt> values.
         *
         * @param name a string that will be the name of new property.
         * @param value a string value for new property.
         */
        public Property(String name, String value)
        {
            this();

            setName(name);
            setValue(value);
        }

        /**
         * Sets the name of this property.
         * @param name a string that will be the name of this property.
         */
        public void setName(String name)
        {
            setAttribute(NAME_ATTR_NAME, name);
        }

        /**
         * Returns the name of this property.
         */
        public String getName()
        {
            return getAttributeAsString(NAME_ATTR_NAME);
        }

        /**
         * Sets the value of this property.
         * @param value a string value for new property.
         */
        public void setValue(String value)
        {
            setAttribute(VALUE_ATTR_NAME, value);
        }

        /**
         * Returns the value of this property.
         */
        public String getValue()
        {
            return getAttributeAsString(VALUE_ATTR_NAME);
        }
    }
}
