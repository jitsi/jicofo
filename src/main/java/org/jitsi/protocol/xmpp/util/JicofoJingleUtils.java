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
package org.jitsi.protocol.xmpp.util;

import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

/**
 * Jicofo specific utilities for Jingle.
 *
 * @author Boris Grozev
 */
public class JicofoJingleUtils
{
    /**
     * Adds a group packet extension to a {@link JingleIQ}, and a
     * {@link BundlePacketExtension} to each of its contents. I.e. adds
     * everything that we deem necessary to enable {@code bundle} in an offer.
     * It is unclear how much of this is actually necessary for
     * {@code jitsi-meet}.
     *
     * @param jingleIQ the IQ to add extensions to.
     */
    public static void addBundleExtensions(JingleIQ jingleIQ)
    {
        GroupPacketExtension group
            = GroupPacketExtension.createBundleGroup(jingleIQ.getContentList());

        jingleIQ.addExtension(group);

        for (ContentPacketExtension content : jingleIQ.getContentList())
        {
            // FIXME: is it mandatory ?
            // http://estos.de/ns/bundle
            content.addChildExtension(new BundlePacketExtension());
        }
    }

    /**
     * Adds a {@link StartMutedPacketExtension} to a specific {@link JingleIQ}.
     * @param jingleIQ the {@link JingleIQ} to add extensions to.
     * @param audioMute the value to set for the {@code audio} attribute.
     * @param videoMute the value to set for the {@code video} attribute.
     */
    public static void addStartMutedExtension(
        JingleIQ jingleIQ, boolean audioMute, boolean videoMute)
    {
        StartMutedPacketExtension startMutedExt
            = new StartMutedPacketExtension();
        startMutedExt.setAudioMute(audioMute);
        startMutedExt.setVideoMute(videoMute);
        jingleIQ.addExtension(startMutedExt);
    }
}
