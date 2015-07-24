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

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;

import org.xmlpull.v1.*;

/**
 * Provider handles parsing of {@link ConferenceIq} and {@link LoginUrlIQ}
 * stanzas and converting objects back to their XML representation.
 *
 * @author Pawel Domas
 */
public class ConferenceIqProvider
    implements IQProvider
{

    /**
     * Creates new instance of <tt>ConferenceIqProvider</tt>.
     */
    public ConferenceIqProvider()
    {
        ProviderManager providerManager = ProviderManager.getInstance();

        // <conference>
        providerManager.addIQProvider(
            ConferenceIq.ELEMENT_NAME, ConferenceIq.NAMESPACE, this);

        // <auth-url>
        providerManager.addIQProvider(
            LoginUrlIQ.ELEMENT_NAME, LoginUrlIQ.NAMESPACE, this);

        //<logout>
        providerManager.addIQProvider(
            LogoutIq.ELEMENT_NAME, LogoutIq.NAMESPACE, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQ parseIQ(XmlPullParser parser)
        throws Exception
    {
        String namespace = parser.getNamespace();

        // Check the namespace
        if (!ConferenceIq.NAMESPACE.equals(namespace))
        {
            return null;
        }

        String rootElement = parser.getName();

        ConferenceIq iq = null;
        LoginUrlIQ authUrlIQ = null;
        LogoutIq logoutIq = null;

        if (ConferenceIq.ELEMENT_NAME.equals(rootElement))
        {
            iq = new ConferenceIq();
            String room
                = parser.getAttributeValue("", ConferenceIq.ROOM_ATTR_NAME);

            iq.setRoom(room);

            String ready
                = parser.getAttributeValue("", ConferenceIq.READY_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(ready))
            {
                iq.setReady(Boolean.valueOf(ready));
            }
            String focusJid
                = parser.getAttributeValue(
                        "", ConferenceIq.FOCUS_JID_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(focusJid))
            {
                iq.setFocusJid(focusJid);
            }
            String sessionId
                = parser.getAttributeValue(
                        "", ConferenceIq.SESSION_ID_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(sessionId))
            {
                iq.setSessionId(sessionId);
            }
            String machineUID = parser.getAttributeValue(
                    "", ConferenceIq.MACHINE_UID_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(machineUID))
            {
                iq.setMachineUID(machineUID);
            }
            String identity = parser.getAttributeValue(
                    "", ConferenceIq.IDENTITY_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(identity))
            {
                iq.setIdentity(identity);
            }
        }
        else if (LoginUrlIQ.ELEMENT_NAME.equals(rootElement))
        {
            authUrlIQ = new LoginUrlIQ();

            String url = parser.getAttributeValue(
                    "", LoginUrlIQ.URL_ATTRIBUTE_NAME);
            if (!StringUtils.isNullOrEmpty(url))
            {
                authUrlIQ.setUrl(url);
            }
            String room = parser.getAttributeValue(
                    "", LoginUrlIQ.ROOM_NAME_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(room))
            {
                authUrlIQ.setRoom(room);
            }
            String popup = parser.getAttributeValue(
                    "", LoginUrlIQ.POPUP_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(popup))
            {
                Boolean popupBool = Boolean.parseBoolean(popup);
                authUrlIQ.setPopup(popupBool);
            }
            String machineUID = parser.getAttributeValue(
                    "", LoginUrlIQ.MACHINE_UID_ATTR_NAME);
            if (!StringUtils.isNullOrEmpty(machineUID))
            {
                authUrlIQ.setMachineUID(machineUID);
            }
        }
        else if (LogoutIq.ELEMENT_NAME.endsWith(rootElement))
        {
            logoutIq = new LogoutIq();

            String sessionId = parser.getAttributeValue(
                    "", LogoutIq.SESSION_ID_ATTR);

            if (!StringUtils.isNullOrEmpty(sessionId))
            {
                logoutIq.setSessionId(sessionId);
            }

            String logoutUrl = parser.getAttributeValue(
                    "", LogoutIq.LOGOUT_URL_ATTR);

            if (!StringUtils.isNullOrEmpty(logoutUrl))
            {
                logoutIq.setLogoutUrl(logoutUrl);
            }
        }
        else
        {
            return null;
        }

        ConferenceIq.Property property = null;

        boolean done = false;

        while (!done)
        {
            switch (parser.next())
            {
                case XmlPullParser.END_TAG:
                {
                    String name = parser.getName();

                    if (rootElement.equals(name))
                    {
                        done = true;
                    }
                    else if (ConferenceIq.Property.ELEMENT_NAME.equals(name))
                    {
                        if (iq != null && property != null)
                        {
                            iq.addProperty(property);
                            property = null;
                        }
                    }
                    break;
                }

                case XmlPullParser.START_TAG:
                {
                    String name = parser.getName();

                    if (ConferenceIq.Property.ELEMENT_NAME.equals(name))
                    {
                        property = new ConferenceIq.Property();

                        // Name
                        String propName
                            = parser.getAttributeValue(
                                    "",
                                    ConferenceIq.Property.NAME_ATTR_NAME);
                        if (!StringUtils.isNullOrEmpty(propName))
                        {
                            property.setName(propName);
                        }

                        // Value
                        String propValue
                            = parser.getAttributeValue(
                                    "",
                                    ConferenceIq.Property.VALUE_ATTR_NAME);
                        if (!StringUtils.isNullOrEmpty(propValue))
                        {
                            property.setValue(propValue);
                        }
                    }
                }
            }
        }

        if (iq != null)
        {
            return iq;
        }
        else if (authUrlIQ != null)
        {
            return authUrlIQ;
        }
        else
        {
            return logoutIq;
        }
    }
}
