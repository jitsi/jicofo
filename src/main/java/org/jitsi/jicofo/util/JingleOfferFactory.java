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
package org.jitsi.jicofo.util;

import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.utils.*;

import java.net.*;
import java.util.*;

/**
 * Contains factory methods for creating Jingle offer sent in 'session-invite'
 * by Jitsi Meet conference focus.
 *
 * @author Pawel Domas
 * @author George Politis
 * @author Boris Grozev
 * @author Lyubomir Marinov
 */
public class JingleOfferFactory
{
    /**
     * The property name of the VP8 payload type to include in the Jingle
     * session-invite.
     */
    public static final String VP8_PT_PNAME = "org.jitsi.jicofo.VP8_PT";

    /**
     * The name of the property which enables VP8.
     */
    public static final String ENABLE_VP8_PNAME
            = "org.jitsi.jicofo.ENABLE_VP8";

    /**
     * The property name of the VP8 RTX payload type to include in the Jingle
     * session-invite.
     */
    public static final String VP8_RTX_PT_PNAME = "org.jitsi.jicofo.VP8_RTX_PT";

    /**
     * The property name of the VP9 payload type to include in the Jingle
     * session-invite.
     */
    public static final String VP9_PT_PNAME = "org.jitsi.jicofo.VP9_PT";

    /**
     * The name of the property which enables VP9.
     */
    public static final String ENABLE_VP9_PNAME
            = "org.jitsi.jicofo.ENABLE_VP9";

    /**
     * The property name of the VP9 RTX payload type to include in the Jingle
     * session-invite.
     */
    public static final String VP9_RTX_PT_PNAME
        = "org.jitsi.jicofo.VP9_RTX_PT";

    /**
     * The property name of the H264 payload type to include in the Jingle
     * session-invite.
     */
    public static final String H264_PT_PNAME = "org.jitsi.jicofo.H264_PT";

    /**
     * The name of the property which enables H264.
     */
    public static final String ENABLE_H264_PNAME
            = "org.jitsi.jicofo.ENABLE_H264";

    /**
     * The property name of the H264 RTX payload type to include in the Jingle
     * session-invite.
     */
    public static final String H264_RTX_PT_PNAME
        = "org.jitsi.jicofo.H264_RTX_PT";

    /**
     * The name of the property which enables the inclusion of the
     * framemarking RTP header extension in the offer.
     */
    public static final String ENABLE_FRAMEMARKING_PNAME
        = "org.jitsi.jicofo.ENABLE_FRAMEMARKING";

    /**
     * The name of the property which enables the inclusion of the AST RTP
     * header extension in the offer.
     */
    public static final String ENABLE_AST_PNAME = "org.jitsi.jicofo.ENABLE_AST";

    /**
     * The name of the property which enables the inclusion of the TOF RTP
     * header extension in the offer.
     */
    public static final String ENABLE_TOF_PNAME = "org.jitsi.jicofo.ENABLE_TOF";

    /**
     * The name of the property which enables the inclusion of the video content
     * type RTP header extension.
     */
    public static final String ENABLE_VIDEO_CONTENT_TYPE_PNAME
        = "org.jitsi.jicofo.ENABLE_VIDEO_CONTENT_TYPE";

    /**
     * The name of the property which enables the inclusion of the RID RTP
     * header extension.
     */
    public static final String ENABLE_RID_PNAME = "org.jitsi.jicofo.ENABLE_RID";

    /**
     * The ID of the transport-cc header extension.
     */
    private static final String TRANSPORT_CC_ID = "5";

    /**
     * The VP8 payload type to include in the Jingle session-invite.
     */
    private final int VP8_PT;

    /**
     * Whether VP8 is enabled.
     */
    private final boolean ENABLE_VP8;

    /**
     * The VP8 RTX payload type to include in the Jingle session-invite.
     */
    private final int VP8_RTX_PT;

    /**
     * The VP9 payload type to include in the Jingle session-invite.
     */
    private final int VP9_PT;

    /**
     * Whether VP9 is enabled.
     */
    private final boolean ENABLE_VP9;

    /**
     * The VP9 RTX payload type to include in the Jingle session-invite.
     */
    private final int VP9_RTX_PT;

    /**
     * The H264 payload type to include in the Jingle session-invite.
     */
    private final int H264_PT;

    /**
     * Whether H264 is enabled.
     */
    private final boolean ENABLE_H264;

    /**
     * The H264 RTX payload type to include in the Jingle session-invite.
     */
    private final int H264_RTX_PT;

    /**
     * Whether to enable the framemarking RTP header extension in created
     * offers.
     */
    private final boolean enableFrameMarking;

    /**
     * Whether to enable the AST RTP header extension in created offers.
     */
    private final boolean enableAst;

    /**
     * Whether to enable the TOF RTP header extension in created offers.
     */
    private final boolean enableTof;

    /**
     * Whether to enable the video content type header extension in created
     * offers.
     */
    private final boolean enableVideoContentType;

    /**
     * Whether to enable the RID header extension in created offers.
     */
    private final boolean enableRid;

    /**
     * Ctor.
     *
     * @param cfg the {@link ConfigurationService} to pull config options from.
     */
    public JingleOfferFactory(ConfigurationService cfg)
    {
        VP8_PT = cfg != null ? cfg.getInt(VP8_PT_PNAME, 100) : 100;
        ENABLE_VP8 = cfg != null ? cfg.getBoolean(ENABLE_VP8_PNAME, true) : true;
        VP8_RTX_PT = cfg != null ? cfg.getInt(VP8_RTX_PT_PNAME, 96) : 96;
        VP9_PT = cfg != null ? cfg.getInt(VP9_PT_PNAME, 101) : 101;
        ENABLE_VP9 = cfg != null ? cfg.getBoolean(ENABLE_VP9_PNAME, true) : true;
        VP9_RTX_PT = cfg != null ? cfg.getInt(VP9_RTX_PT_PNAME, 97) : 97;
        H264_PT = cfg != null ? cfg.getInt(H264_PT_PNAME, 107) : 107;
        ENABLE_H264 = cfg != null ? cfg.getBoolean(ENABLE_H264_PNAME, true) : true;
        H264_RTX_PT = cfg != null ? cfg.getInt(H264_RTX_PT_PNAME, 99) : 99;
        enableFrameMarking
            = cfg != null && cfg.getBoolean(ENABLE_FRAMEMARKING_PNAME, false);

        enableAst = cfg != null && cfg.getBoolean(ENABLE_AST_PNAME, true);

        // TOF is currently disabled, because we don't support it in the bridge
        // (and currently clients seem to not use it when abs-send-time is
        // available).
        enableTof = cfg != null && cfg.getBoolean(ENABLE_TOF_PNAME, false);

        enableVideoContentType = cfg != null
            && cfg.getBoolean(ENABLE_VIDEO_CONTENT_TYPE_PNAME, false);

        enableRid = cfg != null && cfg.getBoolean(ENABLE_RID_PNAME, false);
    }

    /**
     * Creates a {@link ContentPacketExtension} for the audio media type that
     * will be included in initial conference offer.
     *
     * @param disableIce pass <tt>true</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public ContentPacketExtension createAudioContent(
        boolean disableIce, boolean useDtls, boolean stereo,
        boolean enableRemb, boolean enableTcc)
    {
        ContentPacketExtension content
            = createContentPacketExtension(
                    MediaType.AUDIO, disableIce, useDtls);

        addAudioToContent(content, stereo, enableRemb, enableTcc);

        return content;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the data media type that
     * will be included in initial conference offer.
     *
     * @param disableIce pass <tt>true</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public ContentPacketExtension createDataContent(
        boolean disableIce, boolean useDtls)
    {
        ContentPacketExtension content
            = createContentPacketExtension(
                    MediaType.DATA, disableIce, useDtls);

        addDataToContent(content);

        return content;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the video media type that
     * will be included in initial conference offer.
     *
     * @param disableIce pass <tt>true</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     * @param useRtx whether RTX should be included in the offer.
     * @param minBitrate the value to set to the "x-google-min-bitrate" fmtp
     * line for video, or -1 to not add such a line.
     * @param startBitrate the value to set to the "x-google-start-bitrate" fmtp
     * line for video, or -1 to not add such a line.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public ContentPacketExtension createVideoContent(
            boolean disableIce, boolean useDtls, boolean useRtx,
            boolean enableRemb, boolean enableTcc,
            int minBitrate, int startBitrate)
    {
        ContentPacketExtension videoContentPe
            = createContentPacketExtension(
                    MediaType.VIDEO, disableIce, useDtls);

        addVideoToContent(videoContentPe, useRtx, enableRemb, enableTcc,
                minBitrate, startBitrate);

        return videoContentPe;
    }

    /**
     * Creates <tt>ContentPacketExtension</tt> initialized with type of
     * the media and basic transport information based on given parameters.
     * The creator attribute is set to "initiator" and "senders" to "both".
     *
     * @param mediaType the <tt>MediaType</tt> for the content
     * @param disableIce <tt>true</tt> if ICE transport should be disabled
     * @param useDtls <tt>true</tt> if DTLS should be used on top of ICE
     * transport(will have effect only if <tt>disableIce</tt></tt> is
     * <tt>false</tt>)
     *
     * @return new, parametrized instance of <tt>ContentPacketExtension</tt>.
     */
    private static ContentPacketExtension createContentPacketExtension(
            MediaType mediaType, boolean disableIce, boolean useDtls)
    {
        ContentPacketExtension content
            = new ContentPacketExtension(
                    ContentPacketExtension.CreatorEnum.initiator,
                    mediaType.name().toLowerCase());

        content.setSenders(ContentPacketExtension.SendersEnum.both);

        if (!disableIce)
        {
            IceUdpTransportPacketExtension iceUdpTransportPacketExtension
                = new IceUdpTransportPacketExtension();

            if (useDtls)
            {
                iceUdpTransportPacketExtension
                    .addChildExtension(new DtlsFingerprintPacketExtension());
            }

            content.addChildExtension(iceUdpTransportPacketExtension);
        }
        else
        {
            content.addChildExtension(new RawUdpTransportPacketExtension());
        }

        return content;
    }

    /**
     * Adds the audio-related extensions for an offer to a
     * {@link ContentPacketExtension}.
     * @param content the {@link ContentPacketExtension} to add extensions to.
     */
    private void addVideoToContent(ContentPacketExtension content,
                                          boolean useRtx,
                                          boolean enableRemb,
                                          boolean enableTcc,
                                          int minBitrate,
                                          int startBitrate)
    {
        RtpDescriptionPacketExtension rtpDesc
            = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("video");

        if (enableTof)
        {
            // a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
            RTPHdrExtPacketExtension toOffset = new RTPHdrExtPacketExtension();
            toOffset.setID("2");
            toOffset.setURI(URI.create(RTPExtension.TOF_URN));
            rtpDesc.addExtmap(toOffset);
        }

        if (enableAst)
        {
            // a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
            RTPHdrExtPacketExtension absSendTime
                = new RTPHdrExtPacketExtension();
            absSendTime.setID("3");
            absSendTime.setURI(URI.create(RTPExtension.ABS_SEND_TIME_URN));
            rtpDesc.addExtmap(absSendTime);
        }

        if (enableFrameMarking)
        {
            // a=extmap:9 urn:ietf:params:rtp-hdrext:framemarking
            RTPHdrExtPacketExtension framemarking
                = new RTPHdrExtPacketExtension();
            framemarking.setID("9");
            framemarking.setURI(URI.create(RTPExtension.FRAME_MARKING_URN));
            rtpDesc.addExtmap(framemarking);
        }

        if (enableVideoContentType)
        {
            // http://www.webrtc.org/experiments/rtp-hdrext/video-content-type
            RTPHdrExtPacketExtension videoContentType
                = new RTPHdrExtPacketExtension();
            videoContentType.setID("7");
            videoContentType.setURI(URI.create(RTPExtension.VIDEO_CONTENT_TYPE_URN));
            rtpDesc.addExtmap(videoContentType);
        }

        if (enableRid)
        {
            RTPHdrExtPacketExtension rtpStreamId = new RTPHdrExtPacketExtension();
            rtpStreamId.setID("4");
            rtpStreamId.setURI(URI.create(RTPExtension.RTP_STREAM_ID_URN));
            rtpDesc.addExtmap(rtpStreamId);
        }

        if (ENABLE_VP8 && VP8_PT > 0)
        {
            // a=rtpmap:XXX VP8/90000
            PayloadTypePacketExtension vp8
                    = addPayloadTypeExtension(rtpDesc, VP8_PT, Constants.VP8, 90000);

            addExtensionsToVideoPayloadType(
                    vp8, minBitrate, startBitrate, enableRemb, enableTcc);
        }

        if (ENABLE_H264 && H264_PT > 0)
        {
            // a=rtpmap:XXX H264/90000
            PayloadTypePacketExtension h264 = addPayloadTypeExtension(
                    // XXX(gp): older Chrome versions (users have reported 53/55/61)
                    // fail to enable h264, if the encoding name is in lower case.
                    rtpDesc, H264_PT, Constants.H264.toUpperCase(), 90000);

            addExtensionsToVideoPayloadType(
                    h264, minBitrate, startBitrate, enableRemb, enableTcc);
            addParameterExtension(
                h264,
                "profile-level-id",
                "42e01f;level-asymmetry-allowed=1;packetization-mode=1;");
        }

        if (ENABLE_VP9 && VP9_PT > 0)
        {
            // a=rtpmap:XXX VP9/90000
            PayloadTypePacketExtension vp9
                    = addPayloadTypeExtension(rtpDesc, VP9_PT, Constants.VP9, 90000);

            addExtensionsToVideoPayloadType(
                    vp9, minBitrate, startBitrate, enableRemb, enableTcc);
        }


        if (enableTcc)
        {
            // a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
            RTPHdrExtPacketExtension tcc = new RTPHdrExtPacketExtension();
            tcc.setID(TRANSPORT_CC_ID);
            tcc.setURI(URI.create(RTPExtension.TRANSPORT_CC_URN));
            rtpDesc.addExtmap(tcc);
        }


        if (useRtx)
        {
            if (ENABLE_VP8 && VP8_RTX_PT > 0 && VP8_PT > 0)
            {
                // a=rtpmap:96 rtx/90000
                PayloadTypePacketExtension rtx = addPayloadTypeExtension(
                        rtpDesc, VP8_RTX_PT, Constants.RTX, 90000);

                // a=fmtp:96 apt=100
                addParameterExtension(rtx, "apt", String.valueOf(VP8_PT));

                // Chrome doesn't have these when it creates an offer, but they were
                // observed in a hangouts conference. Not sure whether they have any
                // effect.
                // a=rtcp-fb:96 ccm fir
                rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"));

                // a=rtcp-fb:96 nack
                rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null));

                // a=rtcp-fb:96 nack pli
                rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"));
            }

            if (ENABLE_VP9 && VP9_RTX_PT > 0 && VP9_PT > 0)
            {
                // a=rtpmap:97 rtx/90000
                PayloadTypePacketExtension rtxVP9 = addPayloadTypeExtension(
                        rtpDesc, VP9_RTX_PT, Constants.RTX, 90000);

                // a=fmtp:97 apt=101
                addParameterExtension(rtxVP9, "apt", String.valueOf(VP9_PT));
            }

            if (ENABLE_H264 && H264_RTX_PT > 0 && H264_PT > 0)
            {
                // a=rtpmap:99 rtx/90000
                PayloadTypePacketExtension rtxH264 = addPayloadTypeExtension(
                        rtpDesc, H264_RTX_PT, Constants.RTX, 90000);

                // a=fmtp:99 apt=107
                addParameterExtension(rtxH264, "apt", String.valueOf(H264_PT));
            }
        }

        // a=rtpmap:116 red/90000
        //addPayloadTypeExtension(rtpDesc, 116, Constants.RED, 90000);

        // a=rtpmap:117 ulpfec/90000
        //addPayloadTypeExtension(rtpDesc, 117, Constants.ULPFEC, 90000);

        content.addChildExtension(rtpDesc);
    }

    /**
     * Adds a {@link ParameterPacketExtension} with a given name and value
     * to a given {@link PayloadTypePacketExtension}.
     * @param ptExt the extension to add to.
     * @param name the name of the parameter to add.
     * @param value the value of the parameter to add.
     * @return the added extension.
     */
    private static ParameterPacketExtension addParameterExtension(
        PayloadTypePacketExtension ptExt,
                                              String name, String value)
    {
        ParameterPacketExtension parameterPacketExtension
            = new ParameterPacketExtension();
        parameterPacketExtension.setName(name);
        parameterPacketExtension.setValue(value);
        ptExt.addParameter(parameterPacketExtension);

        return parameterPacketExtension;
    }

    private static void addExtensionsToVideoPayloadType(
            PayloadTypePacketExtension pt,
            int minBitrate,
            int startBitrate,
            boolean enableRemb,
            boolean enableTcc)
    {
        // a=rtcp-fb:XXX ccm fir
        pt.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"));

        // a=rtcp-fb:XXX nack
        pt.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null));

        // a=rtcp-fb:XXX nack pli
        pt.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"));


        if (minBitrate != -1)
        {
            addParameterExtension(
                    pt, "x-google-min-bitrate", String.valueOf(minBitrate));
        }

        if (startBitrate != -1)
        {
            addParameterExtension(
                    pt, "x-google-start-bitrate", String.valueOf(startBitrate));
        }

        if (enableRemb)
        {
            // a=rtcp-fb:XXX goog-remb
            pt.addRtcpFeedbackType(
                    createRtcpFbPacketExtension("goog-remb", null));
        }

        if (enableTcc)
        {
            // a=rtcp-fb:XXX transport-cc
            pt.addRtcpFeedbackType(
                    createRtcpFbPacketExtension("transport-cc", null));
        }

    }

    /**
     * Creates an {@link RtcpFbPacketExtension} with the given type and subtype.
     * @return the created extension.
     */
    private static RtcpFbPacketExtension createRtcpFbPacketExtension(
        String type, String subtype)
    {
        RtcpFbPacketExtension rtcpFb = new RtcpFbPacketExtension();
        if (type != null)
        {
            rtcpFb.setFeedbackType(type);
        }
        if (subtype != null)
        {
            rtcpFb.setFeedbackSubtype(subtype);
        }

        return rtcpFb;
    }

    /**
     * Adds a {@link PayloadTypePacketExtension} to a
     * {@link RtpDescriptionPacketExtension}.
     * @param rtpDesc the {@link RtpDescriptionPacketExtension} to add to.
     * @param id the ID of the {@link PayloadTypePacketExtension}.
     * @param name the name of the {@link PayloadTypePacketExtension}.
     * @param clockRate the clock rate of the {@link PayloadTypePacketExtension}.
     * @return the added {@link PayloadTypePacketExtension}.
     */
    private static PayloadTypePacketExtension addPayloadTypeExtension(
        RtpDescriptionPacketExtension rtpDesc, int id, String name,
        int clockRate)
    {
        PayloadTypePacketExtension payloadTypePacketExtension
            = new PayloadTypePacketExtension();
        payloadTypePacketExtension.setId(id);
        payloadTypePacketExtension.setName(name);
        payloadTypePacketExtension.setClockrate(clockRate);

        rtpDesc.addPayloadType(payloadTypePacketExtension);
        return payloadTypePacketExtension;
    }

    /**
     * Adds the video-related extensions for an offer to a
     * {@link ContentPacketExtension}.
     * @param content the {@link ContentPacketExtension} to add extensions to.
     * @param stereo Whether to enable stereo for opus.
     * @param enableRemb Whether to enable REMB.
     * @param enableTcc Whether to enable transport-cc.
     */
    private static void addAudioToContent(ContentPacketExtension content,
                                          boolean stereo, boolean enableRemb,
                                          boolean enableTcc)
    {
        RtpDescriptionPacketExtension rtpDesc
            = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("audio");

        RTPHdrExtPacketExtension ssrcAudioLevel
            = new RTPHdrExtPacketExtension();
        ssrcAudioLevel.setID("1");
        ssrcAudioLevel.setURI(URI.create(RTPExtension.SSRC_AUDIO_LEVEL_URN));
        rtpDesc.addExtmap(ssrcAudioLevel);

        // a=rtpmap:111 opus/48000/2
        PayloadTypePacketExtension opus
            = addPayloadTypeExtension(rtpDesc, 111, Constants.OPUS, 48000);
        opus.setChannels(2);

        // fmtp:111 minptime=10
        addParameterExtension(opus, "minptime", "10");

        if (stereo)
        {
            // fmtp: 111 stereo=1
            addParameterExtension(opus, "stereo", "1");
        }

        // fmtp:111 useinbandfec=1
        addParameterExtension(opus, "useinbandfec", "1");

        if (enableTcc)
        {
            // a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
            RTPHdrExtPacketExtension tcc = new RTPHdrExtPacketExtension();
            tcc.setID(TRANSPORT_CC_ID);
            tcc.setURI(URI.create(RTPExtension.TRANSPORT_CC_URN));
            rtpDesc.addExtmap(tcc);

            // a=rtcp-fb:111 transport-cc
            opus.addRtcpFeedbackType(
                createRtcpFbPacketExtension("transport-cc", null));
        }

        // a=rtpmap:103 ISAC/16000
        addPayloadTypeExtension(rtpDesc, 103, "ISAC", 16000);

        // a=rtpmap:104 ISAC/32000
        addPayloadTypeExtension(rtpDesc, 104, "ISAC", 32000);

        // rtpmap:126 telephone-event/8000
        addPayloadTypeExtension(rtpDesc, 126, Constants.TELEPHONE_EVENT, 8000);

        // a=maxptime:60
        rtpDesc.setAttribute("maxptime", "60");
        content.addChildExtension(rtpDesc);
    }

    /**
     * Adds the data-related extensions for an offer to a
     * {@link ContentPacketExtension}.
     * @param content the {@link ContentPacketExtension} to add extensions to.
     */
    private static void addDataToContent(ContentPacketExtension content)
    {
        //SctpMapExtension sctpMap = new SctpMapExtension();
        //sctpMap.setPort(5000);
        //sctpMap.setProtocol(SctpMapExtension.Protocol.WEBRTC_CHANNEL);
        //sctpMap.setStreams(1024);
        //content.addChildExtension(sctpMap);

        RtpDescriptionPacketExtension rdpe
            = new RtpDescriptionPacketExtension();
        rdpe.setMedia("application");

        content.addChildExtension(rdpe);
    }

    /**
     * Check if given offer contains video contents.
     * @param contents the list of <tt>ContentPacketExtension</tt> describing
     *        Jingle offer.
     * @return <tt>true</tt> if given offer has video content.
     */
    public static boolean containsVideoContent(
        List<ContentPacketExtension> contents)
    {
        for (ContentPacketExtension content : contents)
        {
            if (content.getName().equalsIgnoreCase("video"))
            {
                return true;
            }
        }
        return false;
    }
}
