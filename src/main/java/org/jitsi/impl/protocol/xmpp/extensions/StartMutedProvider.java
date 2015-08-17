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
 * The parser of {@link StartMutedPacketExtension}
 *
 * @author Hristo Terezov
 */
public class StartMutedProvider
    implements PacketExtensionProvider
{
    /**
     * Registers this extension provider into given <tt>ProviderManager</tt>.
     *
     * @param providerManager the <tt>ProviderManager</tt> to which this
     *                        instance will be bound to.
     */
    public void registerStartMutedProvider(ProviderManager providerManager)
    {
        providerManager.addExtensionProvider(
            StartMutedPacketExtension.ELEMENT_NAME,
            StartMutedPacketExtension.NAMESPACE, this);
    }


    @Override
    public PacketExtension parseExtension(XmlPullParser parser) throws Exception
    {
        StartMutedPacketExtension packetExtension
            = new StartMutedPacketExtension();

        //now parse the sub elements
        boolean done = false;
        String elementName;
        while (!done)
        {
            switch (parser.getEventType())
            {
            case XmlPullParser.START_TAG:
            {
                elementName = parser.getName();
                if (StartMutedPacketExtension.ELEMENT_NAME.equals(
                    elementName))
                {
                    boolean audioMute = Boolean.parseBoolean(
                        parser.getAttributeValue("",
                            StartMutedPacketExtension.AUDIO_ATTRIBUTE_NAME));
                    boolean videoMute = Boolean.parseBoolean(
                        parser.getAttributeValue("",
                            StartMutedPacketExtension.VIDEO_ATTRIBUTE_NAME));

                    packetExtension.setAudioMute(audioMute);
                    packetExtension.setVideoMute(videoMute);
                }
                parser.next();
                break;
            }
            case XmlPullParser.END_TAG:
            {
                elementName = parser.getName();
                if (StartMutedPacketExtension.ELEMENT_NAME.equals(
                    elementName))
                {
                    done = true;
                }
                break;
            }
            default:
                parser.next();
            }
        }
        return packetExtension;
    }
}
