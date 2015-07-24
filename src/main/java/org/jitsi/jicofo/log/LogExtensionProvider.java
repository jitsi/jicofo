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
package org.jitsi.jicofo.log;

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.xmlpull.v1.XmlPullParser;

/**
 * Implements a <tt>PacketExtensionProvider</tt> for XEP-0337 log extensions.
 *
 * @author Boris Grozev
 */
public class LogExtensionProvider
    implements PacketExtensionProvider
{
    /**
     * {@inheritDoc}
     */
    @Override
    public PacketExtension parseExtension(XmlPullParser parser)
            throws Exception
    {
        LogPacketExtension result = new LogPacketExtension();

        for (int i = 0; i < parser.getAttributeCount(); i++)
        {
            result.setAttribute(
                    parser.getAttributeName(i),
                    parser.getAttributeValue(i));
        }

        boolean done = false;
        int eventType;
        String elementName;

        while (!done)
        {
            eventType = parser.next();
            elementName = parser.getName();

            if (eventType == XmlPullParser.START_TAG)
            {
                if (LogPacketExtension.MESSAGE_ELEM_NAME.equals(elementName))
                {
                    result.setMessage(parser.nextText());
                }
                else if(LogPacketExtension.TAG_ELEM_NAME.equals(elementName))
                {
                    String nameAttr = null;
                    String valueAttr = null;

                    for (int i = 0; i < parser.getAttributeCount(); i++)
                    {
                        String attrName = parser.getAttributeName(i);
                        if ("name".equals(attrName))
                            nameAttr = parser.getAttributeValue(i);
                        else if ("value".equals(attrName))
                            valueAttr = parser.getAttributeValue(i);
                    }

                    if (nameAttr != null && valueAttr != null)
                    {
                        result.addTag(nameAttr, valueAttr);
                    }
                }
            }
            else if (eventType == XmlPullParser.END_TAG)
            {
                if (LogPacketExtension.LOG_ELEM_NAME.equals(elementName))
                    done = true;
            }
        }
        return result;

    }
}

