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

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;

import org.xmlpull.v1.*;

/**
 * The parser of {@link MuteIq}.
 *
 * @author Pawel Domas
 */
public class MuteIqProvider
    implements IQProvider
{
    /**
     * Registers this IQ provider into given <tt>ProviderManager</tt>.
     * @param providerManager the <tt>ProviderManager</tt> to which this
     *                        instance wil be bound to.
     */
    public void registerMuteIqProvider(ProviderManager providerManager)
    {
        providerManager.addIQProvider(
            MuteIq.ELEMENT_NAME,
            MuteIq.NAMESPACE,
            this);
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
        if (!MuteIq.NAMESPACE.equals(namespace))
        {
            return null;
        }

        String rootElement = parser.getName();

        MuteIq iq;

        if (MuteIq.ELEMENT_NAME.equals(rootElement))
        {
            iq = new MuteIq();

            String jid
                = parser.getAttributeValue("", MuteIq.JID_ATTR_NAME);

            iq.setJid(jid);
        }
        else
        {
            return null;
        }

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
                    break;
                }

                case XmlPullParser.TEXT:
                {
                    Boolean mute = Boolean.parseBoolean(parser.getText());
                    iq.setMute(mute);
                    break;
                }
            }
        }

        return iq;
    }
}
