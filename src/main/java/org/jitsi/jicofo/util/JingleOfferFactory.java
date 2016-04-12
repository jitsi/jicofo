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
 */
public class JingleOfferFactory
{
    private JingleOfferFactory(){ }

    /**
     * Creates <tt>ContentPacketExtension</tt> for given media type that will be
     * included in initial conference offer.
     *
     * @param mediaType the media type for which new offer content will
     * be created.
     * @param disableIce pass <tt>true</tt> if RAW transport instead of ICE
     * should be indicated in the offer.
     * @param useDtls whether to add a DTLS element under the transport
     * elements in the offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    public static ContentPacketExtension createContentForMedia(
            MediaType mediaType, boolean disableIce,
            boolean useDtls, boolean useRtx, boolean useFec)
    {
        ContentPacketExtension content
            = new ContentPacketExtension(
                    ContentPacketExtension.CreatorEnum.initiator,
                    mediaType.name().toLowerCase());

        content.setSenders(ContentPacketExtension.SendersEnum.both);

        // FIXME: re-use Format and EncodingConfiguration
        // to construct the offer
        if (mediaType == MediaType.AUDIO)
        {
            addAudioToContent(content);
        }
        else if (mediaType == MediaType.VIDEO)
        {
            addVideoToContent(content, useRtx, useFec);
        }
        else if (mediaType == MediaType.DATA)
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
        else
        {
            throw new IllegalArgumentException("mediaType");
        }

        // DTLS-SRTP
        //setDtlsEncryptionOnContent(mediaType, content, null);

        if (!disableIce)
        {
            IceUdpTransportPacketExtension iceUdpTransportPacketExtension
                    = new IceUdpTransportPacketExtension();
            if (useDtls)
                iceUdpTransportPacketExtension
                        .addChildExtension(new DtlsFingerprintPacketExtension());

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
                                          boolean useRtx, boolean useFec)
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

        if (useRtx)
        {
            // a=rtpmap:96 rtx/90000
            PayloadTypePacketExtension rtx
                = addPayloadTypeExtension(rtpDesc, 96, Constants.RTX, 90000);

            // a=fmtp:96 apt=100
            ParameterPacketExtension rtxApt
                = new ParameterPacketExtension();
            rtxApt.setName("apt");
            rtxApt.setValue(String.valueOf(vp8pt));
            rtx.addParameter(rtxApt);

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

        if (useFec)
        {
            // a=rtpmap:116 red/90000
            addPayloadTypeExtension(rtpDesc, 116, Constants.RED, 90000);

            // a=rtpmap:117 ulpfec/90000
            addPayloadTypeExtension(rtpDesc, 117, Constants.ULPFEC, 90000);
        }

        content.addChildExtension(rtpDesc);
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
    private static void addAudioToContent(ContentPacketExtension content)
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
        ParameterPacketExtension opusMinptime
            = new ParameterPacketExtension();
        opusMinptime.setName("minptime");
        opusMinptime.setValue("10");
        opus.addParameter(opusMinptime);
        ParameterPacketExtension opusFec
                = new ParameterPacketExtension();
        opusFec.setName("useinbandfec");
        opusFec.setValue("1");
        opus.addParameter(opusFec);

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
}
