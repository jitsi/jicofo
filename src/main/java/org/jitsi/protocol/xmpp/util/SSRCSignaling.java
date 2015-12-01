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
package org.jitsi.protocol.xmpp.util;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;

/**
 * Class gathers utility method related to SSRC signaling.
 *
 * @author Pawel Domas
 */
public class SSRCSignaling
{
    public static String getSSRCOwner(SourcePacketExtension ssrcPe)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        return ssrcInfo != null ? ssrcInfo.getOwner() : null;
    }

    public static String getVideoType(SourcePacketExtension ssrcPe)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        return ssrcInfo != null ? ssrcInfo.getVideoType() : null;
    }

    public static void setSSRCOwner(SourcePacketExtension ssrcPe, String owner)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        if (ssrcInfo == null)
        {
            ssrcInfo = new SSRCInfoPacketExtension();
            ssrcPe.addChildExtension(ssrcInfo);
        }

        ssrcInfo.setOwner(owner);
    }

    public static void setSSRCVideoType(SourcePacketExtension ssrcPe, String videoType)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        if (ssrcInfo == null)
        {
            ssrcInfo = new SSRCInfoPacketExtension();
            ssrcPe.addChildExtension(ssrcInfo);
        }

        ssrcInfo.setVideoType(videoType);
    }
}
