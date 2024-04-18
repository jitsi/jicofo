/*
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.jicofo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withLegacyConfig
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.codec.Config.Companion.config

class CodecConfigTest : ShouldSpec() {
    init {
        context("Default configuration") {
            config.av1.enabled() shouldBe true
            config.av1.pt() shouldBe 41
            config.av1.rtxEnabled() shouldBe true
            config.av1.rtxPt() shouldBe 42

            config.vp8.enabled() shouldBe true
            config.vp8.pt() shouldBe 100
            config.vp8.rtxEnabled() shouldBe true
            config.vp8.rtxPt() shouldBe 96

            config.vp9.enabled() shouldBe true
            config.vp9.pt() shouldBe 101
            config.vp9.rtxEnabled() shouldBe true
            config.vp9.rtxPt() shouldBe 97

            config.h264.enabled() shouldBe true
            config.h264.pt() shouldBe 107
            config.h264.rtxEnabled() shouldBe true
            config.h264.rtxPt() shouldBe 99

            config.opus.enabled() shouldBe true
            config.opus.pt() shouldBe 111
            config.opus.minptime() shouldBe 10
            config.opus.useInbandFec() shouldBe true
            config.opus.red.enabled() shouldBe false
            shouldThrow<Throwable> { config.opus.red.pt() }

            config.framemarking.enabled shouldBe false
            config.framemarking.id shouldBe 9

            config.absSendTime.enabled shouldBe true
            config.absSendTime.id shouldBe 3

            config.tof.enabled shouldBe false
            config.tof.id shouldBe 2

            config.videoContentType.enabled shouldBe false
            config.videoContentType.id shouldBe 7

            config.rid.enabled shouldBe false
            config.rid.id shouldBe 4

            config.tcc.enabled shouldBe true
            config.tcc.id shouldBe 5

            config.audioLevel.enabled shouldBe true
            config.audioLevel.id shouldBe 1

            config.mid.enabled shouldBe false
            config.mid.id shouldBe 10

            config.av1DependencyDescriptor.enabled shouldBe true
            config.av1DependencyDescriptor.id shouldBe 11
        }
        context("Legacy config") {
            context("Disabling a codec") {
                withLegacyConfig("org.jitsi.jicofo.ENABLE_VP8=false") {
                    config.vp8.enabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.pt() }
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
                withLegacyConfig("org.jitsi.jicofo.VP8_PT=-1") {
                    config.vp8.enabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.pt() }
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
                withLegacyConfig("org.jitsi.jicofo.VP8_RTX_PT=-1") {
                    config.vp8.enabled() shouldBe true
                    config.vp8.pt() shouldBe 100
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
                withLegacyConfig(
                    """
                    org.jitsi.jicofo.VP8_PT=-1
                    org.jitsi.jicofo.VP8_RTX_PT=111
                    """.trimIndent()
                ) {
                    config.vp8.enabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.pt() }
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
            }
            context("Changing the PT and RTX PT") {
                withLegacyConfig("org.jitsi.jicofo.VP8_PT=111") {
                    config.vp8.enabled() shouldBe true
                    config.vp8.pt() shouldBe 111
                }
                withLegacyConfig("org.jitsi.jicofo.VP8_RTX_PT=112") {
                    config.vp8.enabled() shouldBe true
                    config.vp8.pt() shouldBe 100
                    config.vp8.rtxEnabled() shouldBe true
                    config.vp8.rtxPt() shouldBe 112
                }
            }
            context("Disabling/enabling extensions") {
                withLegacyConfig("org.jitsi.jicofo.ENABLE_FRAMEMARKING=false") {
                    config.framemarking.enabled shouldBe false
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_FRAMEMARKING=true") {
                    config.framemarking.enabled shouldBe true
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_AST=false") {
                    config.absSendTime.enabled shouldBe false
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_AST=true") {
                    config.absSendTime.enabled shouldBe true
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_RID=false") {
                    config.rid.enabled shouldBe false
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_RID=true") {
                    config.rid.enabled shouldBe true
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_TOF=false") {
                    config.tof.enabled shouldBe false
                }
                withLegacyConfig("org.jitsi.jicofo.ENABLE_TOF=true") {
                    config.tof.enabled shouldBe true
                }
            }
        }
        context("New config") {
            context("Disabling a codec") {
                withNewConfig("jicofo.codec.video.vp8.enabled=false") {
                    config.vp8.enabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.pt() }
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
                withNewConfig("jicofo.codec.video.vp8.pt=-1") {
                    config.vp8.enabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.pt() }
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
                withNewConfig("jicofo.codec.video.vp8.rtx-pt=-1") {
                    config.vp8.enabled() shouldBe true
                    config.vp8.pt() shouldBe 100
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
                withNewConfig(
                    """
                    jicofo {
                      codec {
                        video {
                          vp8 {
                            enabled=true
                            pt=-1
                            rtp-pt=111
                          }
                        }
                      }
                    }
                    """.trimIndent()
                ) {
                    config.vp8.enabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.pt() }
                    config.vp8.rtxEnabled() shouldBe false
                    shouldThrow<Throwable> { config.vp8.rtxPt() }
                }
            }
            context("Changing the PT and RTX PT") {
                withNewConfig("jicofo.codec.video.vp8.pt=111") {
                    config.vp8.enabled() shouldBe true
                    config.vp8.pt() shouldBe 111
                }
                withNewConfig("jicofo.codec.video.vp8.rtx-pt=112") {
                    config.vp8.enabled() shouldBe true
                    config.vp8.pt() shouldBe 100
                    config.vp8.rtxEnabled() shouldBe true
                    config.vp8.rtxPt() shouldBe 112
                }
            }
            context("Disabling/enabling extensions") {
                withNewConfig("jicofo.codec.rtp-extensions.framemarking.enabled=false") {
                    config.framemarking.enabled shouldBe false
                }
                withNewConfig("jicofo.codec.rtp-extensions.framemarking.enabled=true") {
                    config.framemarking.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.abs-send-time.enabled=false") {
                    config.absSendTime.enabled shouldBe false
                }
                withNewConfig("jicofo.codec.rtp-extensions.abs-send-time.enabled=true") {
                    config.absSendTime.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.rid.enabled=false") {
                    config.rid.enabled shouldBe false
                }
                withNewConfig("jicofo.codec.rtp-extensions.rid.enabled=true") {
                    config.rid.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.tof.enabled=false") {
                    config.tof.enabled shouldBe false
                }
                withNewConfig("jicofo.codec.rtp-extensions.tof.enabled=true") {
                    config.tof.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.tcc.enabled=false") {
                    config.tcc.enabled shouldBe false
                }
                withNewConfig("jicofo.codec.rtp-extensions.tcc.enabled=true") {
                    config.tcc.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.mid.enabled=false") {
                    config.mid.enabled shouldBe false
                }
                withNewConfig("jicofo.codec.rtp-extensions.mid.enabled=true") {
                    config.mid.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.av1-dependency-descriptor.enabled=true") {
                    config.av1DependencyDescriptor.enabled shouldBe true
                }
                withNewConfig("jicofo.codec.rtp-extensions.av1-dependency-descriptor.enabled=false") {
                    config.av1DependencyDescriptor.enabled shouldBe false
                }
            }
            context("Changing extension IDs") {
                withNewConfig("jicofo.codec.rtp-extensions.tcc.id=1") {
                    config.tcc.id shouldBe 1
                }
                withNewConfig("jicofo.codec.rtp-extensions.abs-send-time.id=1") {
                    config.absSendTime.id shouldBe 1
                }
            }
            context("Opus config") {
                withNewConfig("jicofo.codec.audio.opus.red.enabled=true") {
                    config.opus.red.enabled() shouldBe true
                    config.opus.red.pt() shouldBe 112
                }
            }
        }
        context("With both legacy and new config, legacy should take precedence") {
            withLegacyConfig("org.jitsi.jicofo.ENABLE_VP8=false") {
                withNewConfig("jicofo.codec.video.vp8.enabled=true") {
                    config.vp8.enabled() shouldBe false
                }
            }
            withLegacyConfig("org.jitsi.jicofo.ENABLE_VP8=true") {
                withNewConfig("jicofo.codec.video.vp8.enabled=false") {
                    config.vp8.enabled() shouldBe true
                }
            }
            withLegacyConfig("org.jitsi.jicofo.ENABLE_FRAMEMARKING=true") {
                withNewConfig("jicofo.codec.rtp-extensions.framemarking.enabled=false") {
                    config.framemarking.enabled() shouldBe true
                }
            }
            withLegacyConfig("org.jitsi.jicofo.ENABLE_FRAMEMARKING=false") {
                withNewConfig("jicofo.codec.rtp-extensions.framemarking.enabled=true") {
                    config.framemarking.enabled() shouldBe false
                }
            }
        }
    }
}
