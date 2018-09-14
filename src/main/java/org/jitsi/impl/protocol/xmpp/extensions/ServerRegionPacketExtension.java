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

/**
 * A packet extension which contains the server's region.
 *
 * @author Boris Grozev
 */
public class ServerRegionPacketExtension
    extends AbstractPacketExtension
{
    /**
     * The name of the {@code server-region} element.
     */
    public static final String ELEMENT_NAME = "server-region";

    /**
     * The namespace for the {@code server-region} element.
     */
    public static final String NAMESPACE = ConferenceIq.NAMESPACE;

    /**
     * The name of the "region" attribute.
     */
    public static final String REGION_ATTR_NAME = "region";

    /**
     * Creates an {@link AbstractPacketExtension} instance for the specified
     * <tt>namespace</tt> and <tt>elementName</tt>.
     *
     * @param namespace the XML namespace for this element.
     * @param elementName the name of the element
     */
    protected ServerRegionPacketExtension(String namespace,
                                          String elementName)
    {
        super(namespace, elementName);
    }

    public ServerRegionPacketExtension(String region)
    {
        this(NAMESPACE, ELEMENT_NAME);

        setRegion(region);
    }

    /**
     * @return the region.
     */
    public String getRegion()
    {
        return getAttributeAsString(REGION_ATTR_NAME);
    }

    /**
     * Sets the region.
     * @param region the value to set.
     */
    public void setRegion(String region)
    {
        setAttribute(REGION_ATTR_NAME, region);
    }
}
