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
package org.jitsi.jicofo.codec;

import org.jitsi.jicofo.*;
import org.jitsi.xmpp.extensions.jingle.*;

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
    public static final JingleOfferFactory INSTANCE = new JingleOfferFactory();

    public List<ContentPacketExtension> createOffer(OfferOptions options)
    {
        List<ContentPacketExtension> contents = new ArrayList<>();
        if (options.getAudio())
        {
            contents.add(createAudioContent(options));
        }
        if (options.getVideo())
        {
            contents.add(createVideoContent(options));
        }
        if (JicofoConfig.config.enableSctp() && options.getSctp())
        {
            contents.add(createDataContent(options));
        }

        return contents;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the audio media type that
     * will be included in initial conference offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    private ContentPacketExtension createAudioContent(OfferOptions options)
    {
        ContentPacketExtension content = createContentPacketExtension("audio");
        addAudioToContent(content, options);

        return content;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the data media type that
     * will be included in initial conference offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    private ContentPacketExtension createDataContent(OfferOptions options)
    {
        ContentPacketExtension content = createContentPacketExtension("data");
        addDataToContent(content);

        return content;
    }

    /**
     * Creates a {@link ContentPacketExtension} for the video media type that
     * will be included in initial conference offer.
     *
     * @return <tt>ContentPacketExtension</tt> for given media type that will be
     *         used in initial conference offer.
     */
    private ContentPacketExtension createVideoContent(OfferOptions options)
    {
        ContentPacketExtension videoContentPe = createContentPacketExtension("video");
        addVideoToContent(videoContentPe, options);

        return videoContentPe;
    }

    /**
     * Creates <tt>ContentPacketExtension</tt> initialized with type of
     * the media and basic transport information based on given parameters.
     * The creator attribute is set to "initiator" and "senders" to "both".
     *
     * @param name the Jingle name for the content
     *
     * @return new, parametrized instance of <tt>ContentPacketExtension</tt>.
     */
    private static ContentPacketExtension createContentPacketExtension(String name)
    {
        ContentPacketExtension content
            = new ContentPacketExtension(ContentPacketExtension.CreatorEnum.initiator, name);

        content.setSenders(ContentPacketExtension.SendersEnum.both);

        IceUdpTransportPacketExtension iceUdpTransportPacketExtension = new IceUdpTransportPacketExtension();
        iceUdpTransportPacketExtension.addChildExtension(new DtlsFingerprintPacketExtension());

        content.addChildExtension(iceUdpTransportPacketExtension);

        return content;
    }

    /**
     * Adds the video-related extensions for an offer to a
     * {@link ContentPacketExtension}.
     * @param content the {@link ContentPacketExtension} to add extensions to.
     */
    private void addVideoToContent(ContentPacketExtension content, OfferOptions options)
    {
        RtpDescriptionPacketExtension rtpDesc = new RtpDescriptionPacketExtension();

        rtpDesc.setMedia("video");

        CodecUtil.Companion.createVideoPayloadTypeExtensions(options).forEach(rtpDesc::addPayloadType);
        CodecUtil.Companion.createVideoRtpHdrExtExtensions(options).forEach(rtpDesc::addExtmap);
        if (Config.config.getExtmapAllowMixed())
        {
            rtpDesc.setExtmapAllowMixed(new ExtmapAllowMixedPacketExtension());
        }

        content.addChildExtension(rtpDesc);
    }

    /**
     * Adds the audio-related extensions for an offer to a
     * {@link ContentPacketExtension}.
     * @param content the {@link ContentPacketExtension} to add extensions to.
     */
    private static void addAudioToContent(ContentPacketExtension content, OfferOptions options)
    {
        RtpDescriptionPacketExtension rtpDesc = new RtpDescriptionPacketExtension();
        rtpDesc.setMedia("audio");

        CodecUtil.Companion.createAudioRtpHdrExtExtensions(options).forEach(rtpDesc::addExtmap);
        CodecUtil.Companion.createAudioPayloadTypeExtensions(options).forEach(rtpDesc::addPayloadType);
        if (Config.config.getExtmapAllowMixed())
        {
            rtpDesc.setExtmapAllowMixed(new ExtmapAllowMixedPacketExtension());
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
