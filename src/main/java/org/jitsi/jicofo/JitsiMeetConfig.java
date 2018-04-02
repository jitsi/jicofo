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

import net.java.sip.communicator.util.Logger;

import org.jitsi.util.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 * Class encapsulates configuration properties for Jitsi Meet conference that
 * are attached to create conference request
 * {@link org.jitsi.impl.protocol.xmpp.extensions.ConferenceIq}. Options are
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
    public static final String AUDIO_PACKET_DELAY = "audioPacketDelay";

    /**
     * The name of channel last N configuration property. Should be non-negative
     * number. Pass <tt>-1</tt> to disable last N functionality.
     */
    public static final String CHANNEL_LAST_N_PNAME = "channelLastN";

    /**
     * The name of the property that enables the {@link LipSyncHack}.
     */
    public static final String ENABLE_LIPSYNC = "enableLipSync";

    /**
     * The name of the property that determines the min partcipants to start
     * the call.
     */
    public static final String MIN_PARTICIPANTS_PNAME = "minParticipants";

    /**
     * The name of the property which enables the inclusion of the REMB RTCP
     * in the offer.
     */
    public static final String ENABLE_REMB_PNAME = "enableRemb";

    /**
     * The name of the property which enables the inclusion of the TCC RTP
     * header extension in the offer.
     */
    public static final String ENABLE_TCC_PNAME = "enableTcc";

    /**
     * The name of the property that specifies JID of the bridge which should be
     * used instead of any bridges elected by <tt>BridgeSelector</tt>.
     * The property is meant to be used in a test that aims to run a conference
     * on specific bridge instance.
     * That property is per conference specific.
     */
    public static final String ENFORCED_BRIDGE = "enforcedBridge";

    /*
     * The name of the open sctp configuration property. Pass 'true' to
     * enable or 'false' to disable.
     */
    public static final String OPEN_SCTP_PNAME = "openSctp";

    /**
     * The name of the configuration property used to configure Jigasi(SIP
     * gateway) instance.
     */
    //public static final String SIP_GATEWAY_PNAME = "call_control";

    /**
     * The name of the start muted property for audio.
     */
    public static final String START_AUDIO_MUTED = "startAudioMuted";

    /**
     * The name of the start muted property for video.
     */
    public static final String START_VIDEO_MUTED = "startVideoMuted";

    /**
     * The name of the "disableRtx" property.
     */
    public static final String DISABLE_RTX_PNAME = "disableRtx";

    /**
     * The name of the "minBitrate" property.
     */
    public static final String MIN_BITRATE_PNAME = "minBitrate";

    /**
     * The name of the "startBitrate" property.
     */
    public static final String START_BITRATE_PNAME = "startBitrate";

    /**
     * The name of the "stereo" property.
     */
    public static final String STEREO_PNAME = "stereo";

    /**
     * The name of the "useRoomAsSharedDocumentName" config property.
     */
    public static final String USE_ROOM_AS_SHARED_DOC_NAME
            = "useRoomAsSharedDocumentName";

    /**
     * The default value of the "startBitrate" property.
     */
    public static final int START_BITRATE_DEFAULT = 800;

    /**
     * The default value for the "minParticipants" property.
     */
    private static final int MIN_PARTICIPANTS_DEFAULT = 2;

    /**
     * Disable REMBs by default.
     */
    private static final boolean ENABLE_REMB_DEFAULT = false;

    /**
     * Enable TCC by default.
     */
    private static final boolean ENABLE_TCC_DEFAULT = true;

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
            String enforcedBridge = properties.get(ENFORCED_BRIDGE);
            if (StringUtils.isNullOrEmpty(enforcedBridge))
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
        return getInt(CHANNEL_LAST_N_PNAME);
    }

    /**
     * Return a <tt>Boolean</tt> value of the {@link #ENABLE_LIPSYNC} property
     * (can be <tt>null</tt>).
     */
    public Boolean isLipSyncEnabled()
    {
        return getBoolean(ENABLE_LIPSYNC);
    }

    /**
     * Returns an <tt>Integer</tt> value of the {@link #AUDIO_PACKET_DELAY}
     * config property(can be <tt>null</tt>).
     */
    public Integer getAudioPacketDelay()
    {
        return getInt(AUDIO_PACKET_DELAY);
    }

    /**
     * @return {@code true} iff RTX is enabled in this {@link JitsiMeetConfig}.
     */
    public boolean isRtxEnabled()
    {
        String disableRtxStr = properties.get(DISABLE_RTX_PNAME);
        return StringUtils.isNullOrEmpty(disableRtxStr)
            || !Boolean.parseBoolean(disableRtxStr);
    }

    /**
     * Gets a boolean that indicates whether or not to enable the REMB RTP
     * header extension in created offers.
     */
    public boolean isRembEnabled()
    {
        Boolean enableRemb = getBoolean(ENABLE_REMB_PNAME);
        return enableRemb == null ? ENABLE_REMB_DEFAULT : enableRemb;
    }

    /**
     * Gets a boolean that indicates whether or not to enable the TCC RTP header
     * extension in created offers.
     */
    public boolean isTccEnabled()
    {
        Boolean enableTcc = getBoolean(ENABLE_TCC_PNAME);
        return enableTcc == null ? ENABLE_TCC_DEFAULT : enableTcc;
    }

    /**
     * Gets the minimum number of participants that need to be present in the
     * call before we start it.
     */
    public int getMinParticipants()
    {
        Integer minParticipants = getInt(MIN_PARTICIPANTS_PNAME);
        return minParticipants != null
            ? minParticipants : MIN_PARTICIPANTS_DEFAULT;
    }

    /**
     * Returns the value of the open sctp configuration property or
     * <tt>null</tt> if it has not been specified.
     */
    public Boolean openSctp()
    {
        return getBoolean(OPEN_SCTP_PNAME);
    }

    private Boolean getBoolean(String name)
    {
        String stringValue = properties.get(name);
        Boolean boolValue = null;

        if (!StringUtils.isNullOrEmpty(stringValue))
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

        if (!StringUtils.isNullOrEmpty(stringValue))
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
        return getInt(START_AUDIO_MUTED);
    }

    /**
     * Returns the value of the start muted video property.
     * @return the value of the start muted video property.
     */
    public Integer getStartVideoMuted()
    {
        return getInt(START_VIDEO_MUTED);
    }

    /**
     * @return the "min bitrate" which should be included in offers.
     */
    public int getMinBitrate()
    {
        Integer minBitrate = getInt(MIN_BITRATE_PNAME);
        return minBitrate == null ? -1 : minBitrate;
    }

    /**
     * @return the "start bitrate" which should be included in offers.
     */
    public int getStartBitrate()
    {
        Integer startBitrate = getInt(START_BITRATE_PNAME);
        return startBitrate == null ? START_BITRATE_DEFAULT : startBitrate;
    }

    /**
     * @return {@code true} iff stereo is enabled in this configuration.
     */
    public boolean stereoEnabled()
    {
        Boolean stereo = getBoolean(STEREO_PNAME);
        return stereo != null && stereo;
    }

    /**
     * Return a <tt>boolean</tt> value of the
     * {@link #USE_ROOM_AS_SHARED_DOC_NAME} property. Indicates if the room name
     * should be used as a shared document name.
     */
    public boolean useRoomAsSharedDocName()
    {
        Boolean useRoom = getBoolean(USE_ROOM_AS_SHARED_DOC_NAME);
        return (useRoom != null) && useRoom;
    }
}
