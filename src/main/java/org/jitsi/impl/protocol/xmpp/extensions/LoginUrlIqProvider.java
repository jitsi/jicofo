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


import org.jitsi.util.*;
import org.jivesoftware.smack.provider.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.xmlpull.v1.*;

/**
 * Provider handles parsing of {@link ConferenceIq} and {@link LoginUrlIq}
 * stanzas and converting objects back to their XML representation.
 *
 * @author Pawel Domas
 */
public class LoginUrlIqProvider
    extends IQProvider<LoginUrlIq>
{
    /**
     * Creates new instance of <tt>ConferenceIqProvider</tt>.
     */
    public LoginUrlIqProvider()
    {
        // <auth-url>
        ProviderManager.addIQProvider(
            LoginUrlIq.ELEMENT_NAME, LoginUrlIq.NAMESPACE, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LoginUrlIq parse(XmlPullParser parser, int initialDepth)
        throws Exception
    {
        String namespace = parser.getNamespace();

        // Check the namespace
        if (!ConferenceIq.NAMESPACE.equals(namespace))
        {
            return null;
        }

        String rootElement = parser.getName();

        LoginUrlIq authUrlIQ;
        if (LoginUrlIq.ELEMENT_NAME.equals(rootElement))
        {
            authUrlIQ = new LoginUrlIq();

            String url = parser.getAttributeValue(
                    "", LoginUrlIq.URL_ATTRIBUTE_NAME);
            if (!StringUtils.isNullOrEmpty(url))
            {
                authUrlIQ.setUrl(url);
            }
            String room = parser.getAttributeValue(
                    "", LoginUrlIq.ROOM_NAME_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(room))
            {
                EntityBareJid roomJid = JidCreate.entityBareFrom(room);
                authUrlIQ.setRoom(roomJid);
            }
            String popup = parser.getAttributeValue(
                    "", LoginUrlIq.POPUP_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(popup))
            {
                Boolean popupBool = Boolean.parseBoolean(popup);
                authUrlIQ.setPopup(popupBool);
            }
            String machineUID = parser.getAttributeValue(
                    "", LoginUrlIq.MACHINE_UID_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(machineUID))
            {
                authUrlIQ.setMachineUID(machineUID);
            }
        }
        else
        {
            return null;
        }

        return authUrlIQ;
    }
}
