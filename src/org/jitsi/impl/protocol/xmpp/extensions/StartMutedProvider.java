/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.protocol.xmpp.extensions;

import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.xmlpull.v1.*;

/**
 * The parser of {@link StartMutedPacketExtension}
 * @author Hristo Terezov
 *
 */
public class StartMutedProvider
    implements PacketExtensionProvider
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(MeetExtensionsHandler.class);

    /**
     * Registers this extension provider into given <tt>ProviderManager</tt>.
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
