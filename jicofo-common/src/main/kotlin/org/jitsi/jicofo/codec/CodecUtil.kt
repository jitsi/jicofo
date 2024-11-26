/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022-Present 8x8, Inc.
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

package org.jitsi.jicofo.codec

import org.jitsi.jicofo.codec.Config.Companion.config
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension
import java.net.URI

class CodecUtil {
    companion object {
        fun createVideoPayloadTypeExtensions(
            options: OfferOptions = OfferOptions()
        ): Collection<PayloadTypePacketExtension> = buildList {
            if (config.av1.enabled()) {
                // a:rtpmap:XXX AV1/90000
                val av1 = createPayloadTypeExtension(config.av1.pt(), "AV1", 90000)
                av1.addVideoExtensions(options, config.av1)
                add(av1)
            }

            if (config.vp8.enabled()) {
                // a=rtpmap:XXX VP8/90000
                val vp8 = createPayloadTypeExtension(config.vp8.pt(), "VP8", 90000)
                vp8.addVideoExtensions(options, config.vp8)
                add(vp8)
            }

            if (config.h264.enabled()) {
                // a=rtpmap:XXX H264/90000
                // XXX(gp): older Chrome versions (users have reported 53/55/61)
                // fail to enable h264, if the encoding name is in lower case.
                val h264 = createPayloadTypeExtension(config.h264.pt(), "H264", 90000)
                h264.addVideoExtensions(options, config.h264)
                h264.addParameterExtension("profile-level-id", "42e01f;level-asymmetry-allowed=1;packetization-mode=1;")

                add(h264)
            }

            if (config.vp9.enabled()) {
                // a=rtpmap:XXX VP9/90000
                val vp9 = createPayloadTypeExtension(config.vp9.pt(), "VP9", 90000)
                vp9.addVideoExtensions(options, config.vp9)
                add(vp9)
            }

            if (options.rtx) {
                if (config.av1.rtxEnabled()) {
                    // a=rtpmap:XXX rtx/90000
                    val rtx = createPayloadTypeExtension(config.av1.rtxPt(), "rtx", 90000)

                    // a=fmtp:XXX apt=YYY (XXX = av1.rtxPt(), YYY = config.av1.pt())
                    rtx.addParameterExtension("apt", config.av1.pt().toString())

                    // a=rtcp-fb:XXX ccm fir
                    rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"))

                    // a=rtcp-fb:XXX nack
                    rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null))

                    // a=rtcp-fb:XXX nack pli
                    rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"))

                    add(rtx)
                }
                if (config.vp8.rtxEnabled()) {
                    // a=rtpmap:96 rtx/90000
                    val rtx = createPayloadTypeExtension(config.vp8.rtxPt(), "rtx", 90000)

                    // a=fmtp:96 apt=100
                    rtx.addParameterExtension("apt", config.vp8.pt().toString())

                    // Chrome doesn't have these when it creates an offer, but they were observed in a hangouts
                    // conference. Not sure whether they have any effect.
                    // a=rtcp-fb:96 ccm fir
                    rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"))

                    // a=rtcp-fb:96 nack
                    rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null))

                    // a=rtcp-fb:96 nack pli
                    rtx.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"))

                    add(rtx)
                }
                if (config.vp9.rtxEnabled()) {
                    // a=rtpmap:97 rtx/90000
                    val rtxVP9 = createPayloadTypeExtension(config.vp9.rtxPt(), "rtx", 90000)

                    // a=fmtp:97 apt=101
                    rtxVP9.addParameterExtension("apt", config.vp9.pt().toString())

                    // Chrome doesn't have these when it creates an offer, but they were observed in a hangouts
                    // conference. Not sure whether they have any effect.
                    // a=rtcp-fb:96 ccm fir
                    rtxVP9.addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"))

                    // a=rtcp-fb:96 nack
                    rtxVP9.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null))

                    // a=rtcp-fb:96 nack pli
                    rtxVP9.addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"))

                    add(rtxVP9)
                }
                if (config.h264.rtxEnabled()) {
                    // a=rtpmap:99 rtx/90000
                    val rtxH264 = createPayloadTypeExtension(config.h264.rtxPt(), "rtx", 90000)

                    // a=fmtp:99 apt=107
                    rtxH264.addParameterExtension("apt", config.h264.pt().toString())

                    add(rtxH264)
                }
            }
        }

        fun createAudioPayloadTypeExtensions(
            options: OfferOptions = OfferOptions()
        ): Collection<PayloadTypePacketExtension> = buildList {
            if (config.opus.enabled()) {
                // Though RED has a payload type of its own and can be used to encode multiple other payload types, we need
                // it to be advertised with the same clock rate as opus, so it's defined here.
                // Add the RED payload type before Opus, so that it is the selected codec.
                if (config.opus.red.enabled() && options.opusRed) {
                    val red = createPayloadTypeExtension(config.opus.red.pt(), "red", 48000)
                    red.channels = 2

                    // RFC 2198 fmtp line
                    // Indicates Opus payload type (111) as primary/secondary encoding for RED (112)
                    // fmtp:112 111/111
                    red.addParameterExtension(null, config.opus.pt().toString() + "/" + config.opus.pt())

                    add(red)
                }

                // a=rtpmap:111 opus/48000/2
                val opus = createPayloadTypeExtension(config.opus.pt(), "opus", 48000)
                add(opus)
                // Opus is always signaled with 2 channels, regardless of 'stereo'
                opus.channels = 2

                // fmtp:111 minptime=10
                opus.addParameterExtension("minptime", config.opus.minptime().toString())
                // Avoid double FEC if RED is offered already.
                if (config.opus.useInbandFec() && !config.opus.red.enabled()) {
                    // fmtp:111 useinbandfec=1
                    opus.addParameterExtension("useinbandfec", "1")
                }
                if (config.tcc.enabled() && options.tcc) {
                    // a=rtcp-fb:111 transport-cc
                    opus.addRtcpFeedbackType(createRtcpFbPacketExtension("transport-cc", null))
                }
            }
            if (config.telephoneEvent.enabled()) {
                // rtpmap:126 telephone-event/8000
                add(createPayloadTypeExtension(config.telephoneEvent.pt(), "telephone-event", 8000))
            }
        }

        /**
         * Create [RTPHdrExtPacketExtension]s for audio based on jicofo's configuration and the given `options`.
         */
        fun createAudioRtpHdrExtExtensions(
            options: OfferOptions = OfferOptions()
        ): Collection<RTPHdrExtPacketExtension> = buildList {
            if (config.audioLevel.enabled()) {
                val ssrcAudioLevel = RTPHdrExtPacketExtension()
                ssrcAudioLevel.id = config.audioLevel.id().toString()
                ssrcAudioLevel.uri = URI.create("urn:ietf:params:rtp-hdrext:ssrc-audio-level")
                add(ssrcAudioLevel)
            }
            if (config.mid.enabled()) {
                // a=extmap:10 rn:ietf:params:rtp-hdrext:sdes:mid
                val mid = RTPHdrExtPacketExtension()
                mid.id = config.mid.id().toString()
                mid.uri = URI.create("urn:ietf:params:rtp-hdrext:sdes:mid")
                add(mid)
            }
            if (config.tcc.enabled() && options.tcc) {
                // a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
                val tcc = RTPHdrExtPacketExtension()
                tcc.id = config.tcc.id().toString()
                tcc.uri = URI.create("http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01")
                add(tcc)
            }
        }
        fun createVideoRtpHdrExtExtensions(
            options: OfferOptions = OfferOptions()
        ): Collection<RTPHdrExtPacketExtension> = buildList {
            if (config.videoLayersAllocation.enabled) {
                // a=extmap:12 http://www.webrtc.org/experiments/rtp-hdrext/video-layers-allocation00
                add(
                    RTPHdrExtPacketExtension().apply {
                        id = config.videoLayersAllocation.id.toString()
                        uri = URI.create("http://www.webrtc.org/experiments/rtp-hdrext/video-layers-allocation00")
                    }
                )
            }
            if (config.av1DependencyDescriptor.enabled()) {
                // https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension
                val dependencyDescriptorExt = RTPHdrExtPacketExtension()
                dependencyDescriptorExt.id = config.av1DependencyDescriptor.id().toString()
                dependencyDescriptorExt.uri = URI.create(
                    "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension"
                )
                add(dependencyDescriptorExt)
            }

            if (config.tof.enabled()) {
                // a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
                val toOffset = RTPHdrExtPacketExtension()
                toOffset.id = config.tof.id().toString()
                toOffset.uri = URI.create("urn:ietf:params:rtp-hdrext:toffset")
                add(toOffset)
            }

            if (config.mid.enabled()) {
                // a=extmap:10 rn:ietf:params:rtp-hdrext:sdes:mid
                val mid = RTPHdrExtPacketExtension()
                mid.id = config.mid.id().toString()
                mid.uri = URI.create("urn:ietf:params:rtp-hdrext:sdes:mid")
                add(mid)
            }

            if (config.absSendTime.enabled()) {
                // a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
                val absSendTime = RTPHdrExtPacketExtension()
                absSendTime.id = config.absSendTime.id().toString()
                absSendTime.uri = URI.create("http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time")
                add(absSendTime)
            }

            if (config.framemarking.enabled()) {
                // a=extmap:XXX urn:ietf:params:rtp-hdrext:framemarking
                val framemarking = RTPHdrExtPacketExtension()
                framemarking.id = config.framemarking.id().toString()
                framemarking.uri = URI.create("http://tools.ietf.org/html/draft-ietf-avtext-framemarking-07")
                add(framemarking)
            }

            if (config.videoContentType.enabled()) {
                // http://www.webrtc.org/experiments/rtp-hdrext/video-content-type
                val videoContentType = RTPHdrExtPacketExtension()
                videoContentType.id = config.videoContentType.id().toString()
                videoContentType.uri = URI.create("http://www.webrtc.org/experiments/rtp-hdrext/video-content-type")
                add(videoContentType)
            }

            if (config.rid.enabled()) {
                val rtpStreamId = RTPHdrExtPacketExtension()
                rtpStreamId.id = config.rid.id.toString()
                rtpStreamId.uri = URI.create("urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id")
                add(rtpStreamId)
            }

            if (config.tcc.enabled() && options.tcc) {
                // a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
                val tcc = RTPHdrExtPacketExtension()
                tcc.id = config.tcc.id().toString()
                tcc.uri = URI.create("http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01")
                add(tcc)
            }
        }

        private fun createPayloadTypeExtension(id: Int, name: String, clockRate: Int) =
            PayloadTypePacketExtension().apply {
                setId(id)
                this.name = name
                clockrate = clockRate
            }

        fun PayloadTypePacketExtension.addVideoExtensions(options: OfferOptions, codecConfig: CodecConfig) {
            // a=rtcp-fb:XXX ccm fir
            addRtcpFeedbackType(createRtcpFbPacketExtension("ccm", "fir"))

            // a=rtcp-fb:XXX nack
            addRtcpFeedbackType(createRtcpFbPacketExtension("nack", null))

            // a=rtcp-fb:XXX nack pli
            addRtcpFeedbackType(createRtcpFbPacketExtension("nack", "pli"))
            if (codecConfig.enableRemb && options.remb) {
                // a=rtcp-fb:XXX goog-remb
                addRtcpFeedbackType(createRtcpFbPacketExtension("goog-remb", null))
            }
            if (config.tcc.enabled() && options.tcc) {
                // a=rtcp-fb:XXX transport-cc
                addRtcpFeedbackType(createRtcpFbPacketExtension("transport-cc", null))
            }
        }
    }
}

/**
 * Adds a [ParameterPacketExtension] with a given name and value to this [PayloadTypePacketExtension].
 * @return the added extension.
 */
private fun PayloadTypePacketExtension.addParameterExtension(name: String?, value: String) =
    ParameterPacketExtension().apply {
        this.name = name
        this.value = value
        addParameter(this)
    }

private fun createRtcpFbPacketExtension(type: String, subtype: String?) = RtcpFbPacketExtension().apply {
    feedbackType = type
    subtype?.let { this.feedbackSubtype = subtype }
}
