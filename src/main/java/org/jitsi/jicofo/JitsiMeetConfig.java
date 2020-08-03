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
package org.jitsi.jicofo;

import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

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
    private final static Logger logger
        = Logger.getLogger(JitsiMeetConfig.class);

    /**
     * The name of the property which specifies the packet delay for the audio
     * channels used in the conference.
     *
     * *NOTE* It is meant to be used for automated testing of
     * the {@link LipSyncHack} only !
     */
    public static final String PNAME_AUDIO_PACKET_DELAY = "audioPacketDelay";

    /**
     * The name of channel last N configuration property. Should be non-negative
     * number. Pass <tt>-1</tt> to disable last N functionality.
     */
    public static final String PNAME_CHANNEL_LAST_N = "channelLastN";

    /**
     * The name of the "disableRtx" property.
     */
    public static final String PNAME_DISABLE_RTX = "disableRtx";

    /**
     * The name of the property that enables the {@link LipSyncHack}.
     */
    public static final String PNAME_ENABLE_LIPSYNC = "enableLipSync";

    /**
     * The name of the property which enables the inclusion of the REMB RTCP
     * in the offer.
     */
    public static final String PNAME_ENABLE_REMB = "enableRemb";

    /**
     * The name of the property which enables the inclusion of the TCC RTP
     * header extension in the offer.
     */
    public static final String PNAME_ENABLE_TCC = "enableTcc";

    /**
     * The name of the property that specifies JID of the bridge which should be
     * used instead of any bridges elected by <tt>BridgeSelector</tt>.
     * The property is meant to be used in a test that aims to run a conference
     * on specific bridge instance.
     * That property is per conference specific.
     */
    public static final String PNAME_ENFORCED_BRIDGE = "enforcedBridge";

    /**
     * The name of the "minBitrate" property.
     */
    public static final String PNAME_MIN_BITRATE = "minBitrate";

    /**
     * The name of the property that determines the min partcipants to start
     * the call.
     */
    public static final String PNAME_MIN_PARTICIPANTS = "minParticipants";

    /*
     * The name of the open sctp configuration property. Pass 'true' to
     * enable or 'false' to disable.
     */
    public static final String PNAME_OPEN_SCTP = "openSctp";

    /**
     * The name of the configuration property used to configure Jigasi(SIP
     * gateway) instance.
     */
    //public static final String PNAME_SIP_GATEWAY = "call_control";

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
     * The name of the "octo" property.
     */
    public static final String PNAME_OCTO = "octo";

    /**
     * The name of the "useRoomAsSharedDocumentName" config property.
     */
    public static final String PNAME_USE_ROOM_AS_SHARED_DOC_NAME
            = "useRoomAsSharedDocumentName";

    /**
     * Disable REMBs by default.
     */
    private static final boolean DEFAULT_ENABLE_REMB = false;

    /**
     * Enable TCC by default.
     */
    private static final boolean DEFAULT_ENABLE_TCC = true;

    /**
     * The default value for the "minParticipants" property.
     */
    private static final int DEFAULT_MIN_PARTICIPANTS = 2;

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

    /**
     * Returns pre-configured JVB address of the bridge that must be used in a
     * conference instead of any other bridges that would come from
     * <tt>BridgeSelector</tt>. <tt>null</tt> if not specified.
     * That property is per conference specific.
     */
    public Jid getEnforcedVideobridge()
    {
        try
        {
            String enforcedBridge = properties.get(PNAME_ENFORCED_BRIDGE);
            if (isBlank(enforcedBridge))
            {
                return null;
            }

            return JidCreate.from(enforcedBridge);
        }
        catch (XmppStringprepException e)
        {
            logger.error("Invalid JID for enforced videobridge", e);
            return null;
        }
    }

    /**
     * Returns pre-configured XMPP address of SIP gateway or <tt>null</tt> if
     * no info was passed in the config.
     */
    /*public String getPreConfiguredSipGateway()
    {
        return properties.get(SIP_GATEWAY_PNAME);
    }*/

    /**
     * Returns an integer value of channel last N property or <tt>null</tt>
     * if it has not been specified.
     */
    public Integer getChannelLastN()
    {
        return getInt(PNAME_CHANNEL_LAST_N);
    }

    /**
     * Return a <tt>Boolean</tt> value of the {@link #ENABLE_LIPSYNC} property
     * (can be <tt>null</tt>).
     */
    public Boolean isLipSyncEnabled()
    {
        return getBoolean(PNAME_ENABLE_LIPSYNC);
    }

    /**
     * Returns an <tt>Integer</tt> value of the {@link #AUDIO_PACKET_DELAY}
     * config property(can be <tt>null</tt>).
     */
    public Integer getAudioPacketDelay()
    {
        return getInt(PNAME_AUDIO_PACKET_DELAY);
    }

    /**
     * @return {@code true} iff RTX is enabled in this {@link JitsiMeetConfig}.
     */
    public boolean isRtxEnabled()
    {
        String disableRtxStr = properties.get(PNAME_DISABLE_RTX);
        return isBlank(disableRtxStr) || !Boolean.parseBoolean(disableRtxStr);
    }

    /**
     * Gets a boolean that indicates whether or not to enable the REMB RTP
     * header extension in created offers.
     */
    public boolean isRembEnabled()
    {
        Boolean enableRemb = getBoolean(PNAME_ENABLE_REMB);
        return enableRemb == null ? DEFAULT_ENABLE_REMB : enableRemb;
    }

    /**
     * Gets a boolean that indicates whether or not to enable the TCC RTP header
     * extension in created offers.
     */
    public boolean isTccEnabled()
    {
        Boolean enableTcc = getBoolean(PNAME_ENABLE_TCC);
        return enableTcc == null ? DEFAULT_ENABLE_TCC : enableTcc;
    }

    /**
     * Gets the minimum number of participants that need to be present in the
     * call before we start it.
     */
    public int getMinParticipants()
    {
        Integer minParticipants = getInt(PNAME_MIN_PARTICIPANTS);
        return minParticipants != null
            ? minParticipants : DEFAULT_MIN_PARTICIPANTS;
    }

    /**
     * Returns the value of the open sctp configuration property or
     * <tt>null</tt> if it has not been specified.
     */
    public boolean openSctp()
    {
        Boolean openSctp = getBoolean(PNAME_OPEN_SCTP);
        return openSctp == null || openSctp;
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

    public boolean isOctoEnabled()
    {
        Boolean octo = getBoolean(PNAME_OCTO);
        return octo != null && octo;

    }

    /**
     * Return a <tt>boolean</tt> value of the
     * {@link #USE_ROOM_AS_SHARED_DOC_NAME} property. Indicates if the room name
     * should be used as a shared document name.
     */
    public boolean useRoomAsSharedDocName()
    {
        Boolean useRoom = getBoolean(PNAME_USE_ROOM_AS_SHARED_DOC_NAME);
        return (useRoom != null) && useRoom;
    }
}
