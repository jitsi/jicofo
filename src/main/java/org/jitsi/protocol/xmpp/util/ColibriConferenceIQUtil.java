/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017 Atlassian Pty Ltd
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

import org.jitsi.xmpp.extensions.colibri.*;

/**
 * Utility operations for {@link ColibriConferenceIQUtil}.
 *
 * @author Pawel Domas
 */
public class ColibriConferenceIQUtil
{
    /**
     * Obtains the channel count for given media type.
     * @param channelInfo <tt>ColibriConferenceIQ</tt> from which
     *        the information will be extracted.
     * @param type name of the channel's media type (e.g. "audio" or "video").
     * @return number of channels for the given media type
     */
    public static int getChannelCount(ColibriConferenceIQ channelInfo,
                                      String              type)
    {
        ColibriConferenceIQ.Content mediaContent = channelInfo.getContent(type);

        return mediaContent != null ? mediaContent.getChannelCount() : 0;
    }
}
