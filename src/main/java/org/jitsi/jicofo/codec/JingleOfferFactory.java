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
package org.jitsi.jicofo.codec;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;

import java.net.*;

import static org.jitsi.jicofo.codec.Config.config;

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
     * Creates a {@link ContentPacketExtension} for the audio media type that
     * will be included in initial conference offer.
     *
     * @param useIce pass <tt>false</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public ContentPacketExtension createAudioContent(
        boolean useIce,
        boolean useDtls,
        @NotNull JitsiMeetConfig config)
    {
        ContentPacketExtension content = createContentPacketExtension("audio", useIce, useDtls);

        addAudioToContent(content, config);

        return content;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the data media type that
     * will be included in initial conference offer.
     *
     * @param useIce pass <tt>false</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public ContentPacketExtension createDataContent(boolean useIce, boolean useDtls)
    {
        ContentPacketExtension content = createContentPacketExtension("data", useIce, useDtls);

        addDataToContent(content);

        return content;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the video media type that
     * will be included in initial conference offer.
     *
     * @param useIce pass <tt>false</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     * @param useRtx whether RTX should be included in the offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public ContentPacketExtension createVideoContent(
            boolean useIce,
            boolean useDtls,
            boolean useRtx,
            @NotNull JitsiMeetConfig config)
    {
        ContentPacketExtension videoContentPe = createContentPacketExtension("video", useIce, useDtls);

        addVideoToContent(videoContentPe, useRtx, config);

        return videoContentPe;
    }

    /**
     * Creates <tt>ContentPacketExtension</tt> initialized with type of
     * the media and basic transport information based on given parameters.
     * The creator attribute is set to "initiator" and "senders" to "both".
     *
     * @param name the Jingle name for the content
     * @param useIce <tt>true</tt> if ICE transport should be enabled
     * @param useDtls <tt>true</tt> if DTLS should be used on top of ICE
     * transport (will have effect only if <tt>useIce</tt></tt> is
     * <tt>true</tt>)
     *
     * @return new, parametrized instance of <tt>ContentPacketExtension</tt>.
     */
    private static ContentPacketExtension createContentPacketExtension(
            String name, boolean useIce, boolean useDtls)
    {
        ContentPacketExtension content
            = new ContentPacketExtension(ContentPacketExtension.CreatorEnum.initiator, name);

        content.setSenders(ContentPacketExtension.SendersEnum.both);

        if (useIce)
        {
            IceUdpTransportPacketExtension iceUdpTransportPacketExtension = new IceUdpTransportPacketExtension();

            if (useDtls)
            {
                iceUdpTransportPacketExtension.addChildExtension(new DtlsFingerprintPacketExtension());
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
    private void addVideoToContent(
            ContentPacketExtension content,
            boolean useRtx,
            JitsiMeetConfig jitsiMeetConfig)
    {
        RtpDescriptionPacketExtension rtpDesc = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("video");

        if (config.tof.enabled())
        {
            // a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
            RTPHdrExtPacketExtension toOffset = new RTPHdrExtPacketExtension();
            toOffset.setID(String.valueOf(config.tof.id()));
            toOffset.setURI(URI.create(RTPExtension.TOF_URN));
            rtpDesc.addExtmap(toOffset);
        }

        if (config.absSendTime.enabled())
        {
            // a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
            RTPHdrExtPacketExtension absSendTime = new RTPHdrExtPacketExtension();
            absSendTime.setID(String.valueOf(config.absSendTime.id()));
            absSendTime.setURI(URI.create(RTPExtension.ABS_SEND_TIME_URN));
            rtpDesc.addExtmap(absSendTime);
        }

        if (config.framemarking.enabled())
        {
            // a=extmap:XXX urn:ietf:params:rtp-hdrext:framemarking
            RTPHdrExtPacketExtension framemarking = new RTPHdrExtPacketExtension();
            framemarking.setID(String.valueOf(config.framemarking.id()));
            framemarking.setURI(URI.create(RTPExtension.FRAME_MARKING_URN));
            rtpDesc.addExtmap(framemarking);
        }

        if (config.videoContentType.enabled())
        {
            // http://www.webrtc.org/experiments/rtp-hdrext/video-content-type
            RTPHdrExtPacketExtension videoContentType = new RTPHdrExtPacketExtension();
            videoContentType.setID(String.valueOf(config.videoContentType.id()));
            videoContentType.setURI(URI.create(RTPExtension.VIDEO_CONTENT_TYPE_URN));
            rtpDesc.addExtmap(videoContentType);
        }

        if (config.rid.enabled())
        {
            RTPHdrExtPacketExtension rtpStreamId = new RTPHdrExtPacketExtension();
            rtpStreamId.setID(String.valueOf(config.rid.enabled()));
            rtpStreamId.setURI(URI.create(RTPExtension.RTP_STREAM_ID_URN));
            rtpDesc.addExtmap(rtpStreamId);
        }

        if (config.vp8.enabled())
        {
            // a=rtpmap:XXX VP8/90000
            PayloadTypePacketExtension vp8 = addPayloadTypeExtension(rtpDesc, config.vp8.pt(), Constants.VP8, 90000);

            addExtensionsToVideoPayloadType(vp8, jitsiMeetConfig);
        }

        if (config.h264.enabled())
        {
            // a=rtpmap:XXX H264/90000
            PayloadTypePacketExtension h264 = addPayloadTypeExtension(
                    // XXX(gp): older Chrome versions (users have reported 53/55/61)
                    // fail to enable h264, if the encoding name is in lower case.
                    rtpDesc, config.h264.pt(), "H264", 90000);

            addExtensionsToVideoPayloadType(h264, jitsiMeetConfig);
            addParameterExtension(
                h264,
                "profile-level-id",
                "42e01f;level-asymmetry-allowed=1;packetization-mode=1;");
        }

        if (config.vp9.enabled())
        {
            // a=rtpmap:XXX VP9/90000
            PayloadTypePacketExtension vp9 = addPayloadTypeExtension(rtpDesc, config.vp9.pt(), Constants.VP9, 90000);

            addExtensionsToVideoPayloadType(vp9, jitsiMeetConfig);
        }


        if (config.tcc.enabled() && jitsiMeetConfig.isTccEnabled())
        {
            // a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
            RTPHdrExtPacketExtension tcc = new RTPHdrExtPacketExtension();
            tcc.setID(String.valueOf(config.tcc.id()));
            tcc.setURI(URI.create(RTPExtension.TRANSPORT_CC_URN));
            rtpDesc.addExtmap(tcc);
        }


        if (useRtx)
        {
            if (config.vp8.rtxEnabled())
            {
                // a=rtpmap:96 rtx/90000
                PayloadTypePacketExtension rtx
                        = addPayloadTypeExtension(rtpDesc, config.vp8.rtxPt(), Constants.RTX, 90000);

                // a=fmtp:96 apt=100
                addParameterExtension(rtx, "apt", String.valueOf(config.vp8.pt()));

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

            if (config.vp9.rtxEnabled())
            {
                // a=rtpmap:97 rtx/90000
                PayloadTypePacketExtension rtxVP9
                        = addPayloadTypeExtension(rtpDesc, config.vp9.rtxPt(), Constants.RTX, 90000);

                // a=fmtp:97 apt=101
                addParameterExtension(rtxVP9, "apt", String.valueOf(config.vp9.pt()));
            }

            if (config.h264.rtxEnabled())
            {
                // a=rtpmap:99 rtx/90000
                PayloadTypePacketExtension rtxH264
                        = addPayloadTypeExtension(rtpDesc, config.h264.rtxPt(), Constants.RTX, 90000);

                // a=fmtp:99 apt=107
                addParameterExtension(rtxH264, "apt", String.valueOf(config.h264.pt()));
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
        String name,
        String value)
    {
        ParameterPacketExtension parameterPacketExtension = new ParameterPacketExtension();
        parameterPacketExtension.setName(name);
        parameterPacketExtension.setValue(value);
        ptExt.addParameter(parameterPacketExtension);

        return parameterPacketExtension;
    }

    private static void addExtensionsToVideoPayloadType(
            PayloadTypePacketExtension pt,
            JitsiMeetConfig jitsiMeetConfig)
    {
        // a=rtcp-fb:XXX ccm fir
        pt.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"));

        // a=rtcp-fb:XXX nack
        pt.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null));

        // a=rtcp-fb:XXX nack pli
        pt.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"));


        int minBitrate = jitsiMeetConfig.getMinBitrate();
        if (minBitrate != -1)
        {
            addParameterExtension(pt, "x-google-min-bitrate", String.valueOf(minBitrate));
        }

        int startBitrate = jitsiMeetConfig.getStartBitrate();
        if (startBitrate != -1)
        {
            addParameterExtension(pt, "x-google-start-bitrate", String.valueOf(startBitrate));
        }

        if (jitsiMeetConfig.isRembEnabled())
        {
            // a=rtcp-fb:XXX goog-remb
            pt.addRtcpFeedbackType(createRtcpFbPacketExtension("goog-remb", null));
        }

        if (config.tcc.enabled() && jitsiMeetConfig.isTccEnabled())
        {
            // a=rtcp-fb:XXX transport-cc
            pt.addRtcpFeedbackType(createRtcpFbPacketExtension("transport-cc", null));
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
            RtpDescriptionPacketExtension rtpDesc,
            int id,
            String name,
            int clockRate)
    {
        PayloadTypePacketExtension payloadTypePacketExtension = new PayloadTypePacketExtension();
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
     * @param maxAvgBitrate The {@code maxaveragebitrate} to be set for opus.
     * @param enableRemb Whether to enable REMB.
     * @param enableTcc Whether to enable transport-cc.
     */
    private static void addAudioToContent(
            ContentPacketExtension content,
            JitsiMeetConfig jitsiMeetConfig)
    {
        RtpDescriptionPacketExtension rtpDesc = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("audio");

        if (config.audioLevel.enabled())
        {
            RTPHdrExtPacketExtension ssrcAudioLevel = new RTPHdrExtPacketExtension();
            ssrcAudioLevel.setID(String.valueOf(config.audioLevel.id()));
            ssrcAudioLevel.setURI(URI.create(RTPExtension.SSRC_AUDIO_LEVEL_URN));
            rtpDesc.addExtmap(ssrcAudioLevel);
        }

        if (config.opus.enabled())
        {
            // Though RED has a payload type of its own and can be used to encode multiple other payload types, we need
            // it to be advertised with the same clock rate as opus, so it's defined here.
            // Add the RED payload type before Opus, so that it is the selected codec.
            if (config.opus.red.enabled())
            {
                PayloadTypePacketExtension red = addPayloadTypeExtension(rtpDesc, config.opus.red.pt(), "red", 48000);
                red.setChannels(2);
            }

            // a=rtpmap:111 opus/48000/2
            PayloadTypePacketExtension opus = addPayloadTypeExtension(rtpDesc, config.opus.pt(), Constants.OPUS, 48000);
            // Opus is always signaled with 2 channels, regardless of 'stereo'
            opus.setChannels(2);

            // fmtp:111 minptime=10
            addParameterExtension(opus, "minptime", String.valueOf(config.opus.minptime()));

            if (jitsiMeetConfig.stereoEnabled())
            {
                // fmtp: 111 stereo=1
                addParameterExtension(opus, "stereo", "1");
            }

            int opusMaxAverageBitrate = jitsiMeetConfig.getOpusMaxAverageBitrate();
            if (opusMaxAverageBitrate != -1)
            {
                addParameterExtension(
                        opus,
                        "maxaveragebitrate",
                        String.valueOf(opusMaxAverageBitrate));
            }

            if (config.opus.useInbandFec())
            {
                // fmtp:111 useinbandfec=1
                addParameterExtension(opus, "useinbandfec", "1");
            }

            if (config.tcc.enabled() && jitsiMeetConfig.isTccEnabled())
            {
                // a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
                RTPHdrExtPacketExtension tcc = new RTPHdrExtPacketExtension();
                tcc.setID(String.valueOf(config.tcc.id()));
                tcc.setURI(URI.create(RTPExtension.TRANSPORT_CC_URN));
                rtpDesc.addExtmap(tcc);

                // a=rtcp-fb:111 transport-cc
                opus.addRtcpFeedbackType(createRtcpFbPacketExtension("transport-cc", null));
            }
        }

        if (config.isac16.enabled())
        {
            // a=rtpmap:103 ISAC/16000
            addPayloadTypeExtension(rtpDesc, config.isac16.pt(), "ISAC", 16000);
        }

        if (config.isac32.enabled())
        {
            // a=rtpmap:104 ISAC/32000
            addPayloadTypeExtension(rtpDesc, config.isac32.pt(), "ISAC", 32000);
        }

        if (config.telephoneEvent.enabled())
        {
            // rtpmap:126 telephone-event/8000
            addPayloadTypeExtension(rtpDesc, config.telephoneEvent.pt(), Constants.TELEPHONE_EVENT, 8000);
        }

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

        RtpDescriptionPacketExtension rdpe = new RtpDescriptionPacketExtension();
        rdpe.setMedia("application");

        content.addChildExtension(rdpe);
    }
}
