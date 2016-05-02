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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;

import java.net.*;

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
    private JingleOfferFactory(){ }

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
    public static ContentPacketExtension createAudioContent(
        boolean disableIce, boolean useDtls, boolean stereo)
    {
        ContentPacketExtension content
            = createContentPacketExtension(
                    MediaType.AUDIO, disableIce, useDtls);

        addAudioToContent(content, stereo);

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
    public static ContentPacketExtension createDataContent(
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
    public static ContentPacketExtension createVideoContent(
            boolean disableIce, boolean useDtls, boolean useRtx,
            int minBitrate, int startBitrate)
    {
        ContentPacketExtension videoContentPe
            = createContentPacketExtension(
                    MediaType.VIDEO, disableIce, useDtls);

        addVideoToContent(videoContentPe, useRtx, minBitrate, startBitrate);

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
    private static void addVideoToContent(ContentPacketExtension content,
                                          boolean useRtx,
                                          int minBitrate,
                                          int startBitrate)
    {
        RtpDescriptionPacketExtension rtpDesc
            = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("video");

        // This is currently disabled, because we don't support it in the
        // bridge (and currently clients seem to not use it when
        // abs-send-time is available).
        // a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
        //RTPHdrExtPacketExtension toOffset
        //    = new RTPHdrExtPacketExtension();
        //toOffset.setID("2");
        //toOffset.setURI(
        //    URI.create("urn:ietf:params:rtp-hdrext:toffset"));
        //rtpDesc.addExtmap(toOffset);

        // a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
        RTPHdrExtPacketExtension absSendTime
            = new RTPHdrExtPacketExtension();
        absSendTime.setID("3");
        absSendTime.setURI(URI.create(RTPExtension.ABS_SEND_TIME_URN));
        rtpDesc.addExtmap(absSendTime);

        // a=rtpmap:100 VP8/90000
        int vp8pt = 100;
        PayloadTypePacketExtension vp8
            = addPayloadTypeExtension(rtpDesc, vp8pt, Constants.VP8, 90000);

        // a=rtcp-fb:100 ccm fir
        vp8.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"));

        // a=rtcp-fb:100 nack
        vp8.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null));

        // a=rtcp-fb:100 nack pli
        vp8.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"));

        // a=rtcp-fb:100 goog-remb
        vp8.addRtcpFeedbackType(createRtcpFbPacketExtension("goog-remb", null));

        if (minBitrate != -1)
        {
            addParameterExtension(
                vp8, "x-google-min-bitrate", String.valueOf(minBitrate));
        }

        if (startBitrate != -1)
        {
            addParameterExtension(
                vp8, "x-google-start-bitrate", String.valueOf(startBitrate));
        }

        if (useRtx)
        {
            // a=rtpmap:96 rtx/90000
            PayloadTypePacketExtension rtx
                = addPayloadTypeExtension(rtpDesc, 96, Constants.RTX, 90000);

            // a=fmtp:96 apt=100
            addParameterExtension(rtx, "apt", String.valueOf(vp8pt));

            // Chrome doesn't have these when it creates an offer, but they were
            // observed in a hangouts conference. Not sure whether they have any
            // effect.
            // a=rtcp-fb:96 ccm fir
            rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"));

            // a=rtcp-fb:96 nack
            rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null));

            // a=rtcp-fb:96 nack pli
            rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"));

            // a=rtcp-fb:96 goog-remb
            rtx.addRtcpFeedbackType(
                createRtcpFbPacketExtension("goog-remb", null));
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
     */
    private static void addAudioToContent(ContentPacketExtension content,
                                          boolean stereo)
    {
        RtpDescriptionPacketExtension rtpDesc
            = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("audio");

        RTPHdrExtPacketExtension ssrcAudioLevel
            = new RTPHdrExtPacketExtension();
        ssrcAudioLevel.setID("1");
        ssrcAudioLevel.setURI(URI.create(RTPExtension.SSRC_AUDIO_LEVEL_URN));
        rtpDesc.addExtmap(ssrcAudioLevel);
        RTPHdrExtPacketExtension absSendTime
                = new RTPHdrExtPacketExtension();
        absSendTime.setID("3");
        absSendTime.setURI(URI.create(RTPExtension.ABS_SEND_TIME_URN));
        rtpDesc.addExtmap(absSendTime);

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
}
