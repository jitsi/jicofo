/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo;

import org.jitsi.utils.logging2.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Class encapsulates configuration properties for Jitsi Meet conference that
 * are attached to create conference request
 * {@link org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq}. Options are
 * configured in 'config.js' file of Jitsi Meet Java Script application.
 *
 * @author Pawel Domas
 */
public class JitsiMeetConfig
{
    /**
     * The logger.
     */
    private final static Logger logger = new LoggerImpl(JitsiMeetConfig.class.getName());

    /**
     * The name of the "minBitrate" property.
     */
    public static final String PNAME_MIN_BITRATE = "minBitrate";

    /**
     * The name of the start muted property for audio.
     */
    public static final String PNAME_START_AUDIO_MUTED = "startAudioMuted";

    /**
     * The name of the start muted property for video.
     */
    public static final String PNAME_START_VIDEO_MUTED = "startVideoMuted";

    /**
     * The name of the "startBitrate" property.
     */
    public static final String PNAME_START_BITRATE = "startBitrate";

    /**
     * The name of the "stereo" property.
     */
    public static final String PNAME_STEREO = "stereo";

    /**
     * The name of the "maxaveragebitrate" property.
     */
    public static final String PNAME_OPUS_MAX_AVG_BITRATE = "opusMaxAverageBitrate";

    /**
     * The default value of the "startBitrate" property.
     */
    public static final int DEFAULT_START_BITRATE = 800;

    private final Map<String, String> properties;

    /**
     * Creates new <tt>JitsiMeetConfig</tt> from given properties map.
     * @param properties a string to string map that contains name to value
     *                   mapping of configuration properties.
     */
    public JitsiMeetConfig(Map<String, String> properties)
    {
        this.properties = properties;
    }

    private Boolean getBoolean(String name)
    {
        String stringValue = properties.get(name);
        Boolean boolValue = null;

        if (isNotBlank(stringValue))
        {
            //try
            //{
            boolValue = Boolean.parseBoolean(stringValue);
            //}
            //catch (NumberFormatException ex)
            //{
            //    logger.error(
            //        "Error parsing: " + name + ", v: " + stringValue, ex);
            //}
        }
        return boolValue;
    }

    private Integer getInt(String name)
    {
        String stringValue = properties.get(name);
        Integer intValue = null;

        if (isNotBlank(stringValue))
        {
            try
            {
                intValue = Integer.parseInt(stringValue);
            }
            catch (NumberFormatException ex)
            {
                logger.error("Error parsing: " + name + ", v: " + stringValue);
            }
        }
        return intValue;
    }

    /**
     * Returns the value of the start muted audio property.
     * @return the value of the start muted audio property.
     */
    public Integer getStartAudioMuted()
    {
        return getInt(PNAME_START_AUDIO_MUTED);
    }

    /**
     * Returns the value of the start muted video property.
     * @return the value of the start muted video property.
     */
    public Integer getStartVideoMuted()
    {
        return getInt(PNAME_START_VIDEO_MUTED);
    }

    /**
     * @return the "min bitrate" which should be included in offers.
     */
    public int getMinBitrate()
    {
        Integer minBitrate = getInt(PNAME_MIN_BITRATE);
        return minBitrate == null ? -1 : minBitrate;
    }

    /**
     * @return the "start bitrate" which should be included in offers.
     */
    public int getStartBitrate()
    {
        Integer startBitrate = getInt(PNAME_START_BITRATE);
        return startBitrate == null ? DEFAULT_START_BITRATE : startBitrate;
    }

    /**
     * @return {@code true} iff stereo is enabled in this configuration.
     */
    public boolean stereoEnabled()
    {
        Boolean stereo = getBoolean(PNAME_STEREO);
        return stereo != null && stereo;
    }

    /**
     * @return the "maxaveragebitrate" which should be included in offers or -1 if not specified.
     */
    public int getOpusMaxAverageBitrate()
    {
        Integer maxAvgBitrate = getInt(PNAME_OPUS_MAX_AVG_BITRATE);
        return maxAvgBitrate == null ? -1 : maxAvgBitrate;
    }
}
