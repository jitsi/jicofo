/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 Atlassian Pty Ltd
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
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * A {@link org.xmpp.packet.PacketExtension} that represents a list of
 * {@link ConferenceProperty}s to be included in the focus MUC presence. The
 * idea is to use it for stuff like recording status, etherpad URL, and other
 * conference related information.
 *
 * @author George Politis
 */
public class ConferenceProperties
    extends AbstractPacketExtension
{
    /**
     * The XML namespace of this element.
     */
    public final static String NAMESPACE = ConferenceIq.NAMESPACE;

    /**
     * The XML name of the conference-properties element.
     */
    public final static String ELEMENT_NAME = "conference-properties";

    /**
     * Ctor.
     */
    public ConferenceProperties()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Associates the specified value with the specified key in this properties
     * list.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     */
    public void put(String key, String value)
    {
        List<? extends ExtensionElement> children = this.getChildExtensions();
        for (ExtensionElement child : children)
        {
            if (!(child instanceof AbstractPacketExtension))
            {
                continue;
            }

            AbstractPacketExtension child2 = (AbstractPacketExtension) child;
            if (key.equalsIgnoreCase(child2.getAttributeAsString("key")))
            {
                // updating the existing extension element.
                child2.setAttribute("value", value);
                return;
            }
        }

        // adding a new extension element.
        addChildExtension(new ConferenceProperty(key, value));
    }

    /**
     * A {@link org.xmpp.packet.PacketExtension} that represents a key-value pair
     * to be included in the focus MUC presence.
     *
     * @author George Politis
     */
    class ConferenceProperty
        extends AbstractPacketExtension
    {
        /**
         * The XML name of the conference property element.
         */
        public final static String ELEMENT_NAME = "property";

        /**
         * Ctor.
         *
         * @param key key with which the specified value is to be associated
         * @param value value to be associated with the specified key
         */
        private ConferenceProperty(String key, String value)
        {
            super(NAMESPACE, ELEMENT_NAME);

            setAttribute("key", key);
            setAttribute("value", value);
        }
    }
}
