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
 * Packet extension used to indicate whether the peer should start muted or not.
 *
 * @author Hristo Terezov
 */
public class StartMutedPacketExtension
    extends AbstractPacketExtension
{

    /**
     * Name space of start muted packet extension.
     */
    public static final String NAMESPACE = "http://jitsi.org/jitmeet/start-muted";

    /**
     * XML element name of this packet extension.
     */
    public static final String ELEMENT_NAME = "startmuted";

    /**
     * Attribute name for audio muted.
     */
    public static final String AUDIO_ATTRIBUTE_NAME = "audio";

    /**
     * Attribute name for video muted.
     */
    public static final String VIDEO_ATTRIBUTE_NAME = "video";

    /**
     * Constructs new instance of <tt>StartMutedPacketExtension</tt>
     */
    public StartMutedPacketExtension()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Sets audio muted attribute.
     * @param audioMute the value to be set
     */
    public void setAudioMute(boolean audioMute)
    {
        setAttribute(AUDIO_ATTRIBUTE_NAME, audioMute);
    }

    /**
     * Sets video muted attribute.
     * @param videoMute the value to be set.
     */
    public void setVideoMute(boolean videoMute)
    {
        setAttribute(VIDEO_ATTRIBUTE_NAME, videoMute);
    }

    /**
     * Returns the audio muted attribute.
     * @return the audio muted attribute.
     */
    public boolean getAudioMuted()
    {
        return (Boolean)getAttribute(AUDIO_ATTRIBUTE_NAME);
    }

    /**
     * Returns the video muted attribute.
     * @return the video muted attribute.
     */
    public boolean getVideoMuted()
    {
        return (Boolean)getAttribute(VIDEO_ATTRIBUTE_NAME);
    }

}
