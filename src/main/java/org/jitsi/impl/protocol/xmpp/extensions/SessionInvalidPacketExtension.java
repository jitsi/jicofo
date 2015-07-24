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
