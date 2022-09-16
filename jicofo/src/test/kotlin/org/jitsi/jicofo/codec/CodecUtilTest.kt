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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.config.withNewConfig
import java.net.URI

class CodecUtilTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        context("Video") {
            context("VP8") {
                context("Default config") {
                    val vp8 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "VP8" }
                    vp8 shouldNotBe null
                    vp8!!.clockrate shouldBe 90000
                    vp8.id shouldBe Config.config.vp8.pt()
                    vp8.rtcpFeedbackTypeList.any { it.feedbackType == "nack" } shouldBe true
                    vp8.rtcpFeedbackTypeList.any { it.feedbackType == "transport-cc" } shouldBe true
                    vp8.rtcpFeedbackTypeList.any { it.feedbackType == "goog-remb" } shouldBe false

                    val vp8Rtx = CodecUtil.createVideoPayloadTypeExtensions().find {
                        it.id == Config.config.vp8.rtxPt()
                    }
                    vp8Rtx shouldNotBe null
                    vp8Rtx!!.id shouldBe Config.config.vp8.rtxPt()
                    vp8Rtx.name shouldBe "rtx"
                    vp8Rtx.parameters.any {
                        it.name == "apt" && it.value == Config.config.vp8.pt().toString()
                    } shouldBe true
                }
                withNewConfig("jicofo.codec.video.vp8.enabled=false") {
                    val vp8 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "VP8" }
                    vp8 shouldBe null
                }
                withNewConfig("jicofo.codec.video.vp8.pt=123") {
                    val vp8 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "VP8" }
                    vp8!!.id shouldBe 123
                }
            }
            context("VP9") {
                context("Default config") {
                    val vp9 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "VP9" }
                    vp9 shouldNotBe null
                    vp9!!.clockrate shouldBe 90000
                    vp9.id shouldBe Config.config.vp9.pt()
                    vp9.rtcpFeedbackTypeList.any { it.feedbackType == "nack" } shouldBe true
                    vp9.rtcpFeedbackTypeList.any { it.feedbackType == "transport-cc" } shouldBe true
                    vp9.rtcpFeedbackTypeList.any { it.feedbackType == "goog-remb" } shouldBe false

                    val vp9Rtx = CodecUtil.createVideoPayloadTypeExtensions().find {
                        it.id == Config.config.vp9.rtxPt()
                    }
                    vp9Rtx shouldNotBe null
                    vp9Rtx!!.id shouldBe Config.config.vp9.rtxPt()
                    vp9Rtx.name shouldBe "rtx"
                    vp9Rtx.parameters.any {
                        it.name == "apt" && it.value == Config.config.vp9.pt().toString()
                    } shouldBe true
                }
                withNewConfig("jicofo.codec.video.vp9.enabled=false") {
                    val vp9 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "VP9" }
                    vp9 shouldBe null
                }
                withNewConfig("jicofo.codec.video.vp9.pt=123") {
                    val vp9 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "VP9" }
                    vp9!!.id shouldBe 123
                }
            }
            context("H264") {
                context("Default config") {
                    val h264 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "H264" }
                    h264 shouldNotBe null
                    h264!!.clockrate shouldBe 90000
                    h264.id shouldBe Config.config.h264.pt()
                    h264.rtcpFeedbackTypeList.any { it.feedbackType == "nack" } shouldBe true
                    h264.rtcpFeedbackTypeList.any { it.feedbackType == "transport-cc" } shouldBe true
                    h264.rtcpFeedbackTypeList.any { it.feedbackType == "goog-remb" } shouldBe false

                    val h264Rtx = CodecUtil.createVideoPayloadTypeExtensions().find {
                        it.id == Config.config.h264.rtxPt()
                    }
                    h264Rtx shouldNotBe null
                    h264Rtx!!.id shouldBe Config.config.h264.rtxPt()
                    h264Rtx.name shouldBe "rtx"
                    h264Rtx.parameters.any {
                        it.name == "apt" && it.value == Config.config.h264.pt().toString()
                    } shouldBe true
                }
                withNewConfig("jicofo.codec.video.h264.enabled=false") {
                    val h264 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "H264" }
                    h264 shouldBe null
                }
                withNewConfig("jicofo.codec.video.h264.pt=123") {
                    val h264 = CodecUtil.createVideoPayloadTypeExtensions().find { it.name == "H264" }
                    h264!!.id shouldBe 123
                }
            }
        }
        context("Audio") {
            context("Opus") {
                val opus = CodecUtil.createAudioPayloadTypeExtensions().find { it.name == "opus" }
                opus shouldNotBe null
                opus!!.id shouldBe Config.config.opus.pt()
                opus.clockrate shouldBe 48000
                opus.channels shouldBe 2
            }
            context("RED") {
                CodecUtil.createAudioPayloadTypeExtensions().any { it.name == "red" } shouldBe false

                withNewConfig("jicofo.codec.audio.opus.red.enabled=true") {
                    val red = CodecUtil.createAudioPayloadTypeExtensions().find { it.name == "red" }
                    red shouldNotBe null
                    val opusPt = Config.config.opus.pt()
                    red!!.parameters.any { it.value == "$opusPt/$opusPt" } shouldBe true
                }
            }
        }
        context("RTP Header Extensions") {
            context("SSRC Audio Level") {
                val alUri = URI.create("urn:ietf:params:rtp-hdrext:ssrc-audio-level")

                CodecUtil.createAudioRtpHdrExtExtensions().any { it.uri == alUri } shouldBe true
                CodecUtil.createVideoRtpHdrExtExtensions().any { it.uri == alUri } shouldBe false
            }
            context("MID") {
                val midUri = URI.create("urn:ietf:params:rtp-hdrext:sdes:mid")

                CodecUtil.createAudioRtpHdrExtExtensions().any { it.uri == midUri } shouldBe false
                CodecUtil.createVideoRtpHdrExtExtensions().any { it.uri == midUri } shouldBe false
                withNewConfig("jicofo.codec.rtp-extensions.mid.enabled=true") {
                    CodecUtil.createAudioRtpHdrExtExtensions().any { it.uri == midUri } shouldBe true
                    CodecUtil.createVideoRtpHdrExtExtensions().any { it.uri == midUri } shouldBe true
                }
            }
            context("TCC") {
                val tccUri = URI.create("http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01")

                CodecUtil.createAudioRtpHdrExtExtensions().any { it.uri == tccUri } shouldBe true
                CodecUtil.createVideoRtpHdrExtExtensions().any { it.uri == tccUri } shouldBe true
                CodecUtil.createAudioRtpHdrExtExtensions(OfferOptions(tcc = false)).any {
                    it.uri == tccUri
                } shouldBe false
                CodecUtil.createVideoRtpHdrExtExtensions(OfferOptions(tcc = false)).any {
                    it.uri == tccUri
                } shouldBe false

                withNewConfig("jicofo.codec.rtp-extensions.tcc.enabled=false") {
                    CodecUtil.createAudioRtpHdrExtExtensions().any { it.uri == tccUri } shouldBe false
                    CodecUtil.createVideoRtpHdrExtExtensions().any { it.uri == tccUri } shouldBe false
                }
            }
        }
    }
}
