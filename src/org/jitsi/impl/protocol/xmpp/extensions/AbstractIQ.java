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

import org.jitsi.util.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Template class for XMPP IQ implementations.
 *
 * @author Pawel Domas
 */
public abstract class AbstractIQ
    extends IQ
{
    /**
     * XML namespace of this IQ.
     */
    private final String namespace;

    /**
     * XML element name of this IQ.
     */
    private final String elementName;

    /**
     * Creates new instance initialized with given namespace and element name.
     * @param namespace XML namespace of new IQ instance.
     * @param elementName XML element name of new IQ instance.
     */
    public AbstractIQ(String namespace, String elementName)
    {
        this.namespace = namespace;
        this.elementName = elementName;
    }

    /**
     * Override this method to have {@link #printChildren(StringBuilder)}
     * method called when XML output is being created.
     *
     * @return <tt>true</tt> if this IQ instance has child elements which
     * will result in {@link #printChildren(StringBuilder)} method being
     * called when XML output is being constructed.
     */
    protected boolean hasChildren()
    {
        return getExtensions().size() > 0;
    }

    /**
     * Method prints child elements into given output <tt>StringBuilder</tt>.
     * Called during XML output construction if  {@link #hasChildren()}
     * returns <tt>true</tt>.
     *
     * @param out <tt>StringBuilder</tt> that will be used to print out children
     *            XML representation.
     */
    protected void printChildren(StringBuilder out)
    {
        Collection<PacketExtension> children = getExtensions();
        for (PacketExtension ext : children)
        {
            out.append(ext.toXML());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getChildElementXML()
    {
        StringBuilder xml = new StringBuilder();

        xml.append("<").append(elementName);
        xml.append(" xmlns='").append(namespace).append("' ");

        printAttributes(xml);

        if (!hasChildren())
        {
            xml.append("/>");
        }
        else
        {
            xml.append(">");

            printChildren(xml);

            xml.append("</").append(elementName).append(">");
        }
        return xml.toString();
    }

    /**
     * Utility method for printing out <tt>String</tt> attributes of IQ
     * XML element. Attribute will be printed only if it is neither
     * <tt>null</tt> nor empty.
     *
     * @param out output <tt>StringBuilder</tt>
     * @param name IQ attribute name
     * @param value attribute <tt>String</tt> value
     */
    protected void printStrAttr(StringBuilder out, String name, String value)
    {
        if (!StringUtils.isNullOrEmpty(value))
        {
            out.append(name).append("='").append(value).append("' ");
        }
    }

    /**
     * Utility method for printing out <tt>Object</tt> attributes of IQ
     * XML element. Attribute will be printed only if <tt>Object</tt> value is
     * not <tt>null</tt>.
     *
     * @param out output <tt>StringBuilder</tt>
     * @param name IQ attribute name
     * @param value attribute <tt>Object</tt> value which will be converted
     *              to <tt>String</tt>.
     */
    protected void printObjAttr(StringBuilder out, String name, Object value)
    {
        if (value != null)
        {
            out.append(name).append("='").append(value).append("' ");
        }
    }

    /**
     * Prints attributes in XML format to given <tt>StringBuilder</tt>.
     * Method called when XML representation of this IQ is being constructed.
     *
     * @param out the <tt>StringBuilder</tt> instance used to construct XML
     *            representation of this element.
     */
    protected abstract void printAttributes(StringBuilder out);

    /**
     * //FIXME: share with {@link AbstractPacketExtension}
     *
     * Returns this packet's direct child extensions that match the
     * specified <tt>type</tt>.
     *
     * @param <T> the specific <tt>PacketExtension</tt> type of child extensions
     * to be returned
     *
     * @param type the <tt>Class</tt> of the extension we are looking for.
     *
     * @return a (possibly empty) list containing all of this packet's direct
     * child extensions that match the specified <tt>type</tt>
     */
    public <T extends PacketExtension> List<T> getChildExtensionsOfType(
            Class<T> type)
    {
        Collection<? extends PacketExtension> childExtensions = getExtensions();
        List<T> result = new ArrayList<T>();

        if (childExtensions == null)
            return result;

        for(PacketExtension extension : childExtensions)
        {
            if(type.isInstance(extension))
            {
                @SuppressWarnings("unchecked")
                T extensionAsType = (T) extension;

                result.add(extensionAsType);
            }
        }

        return result;
    }
}
