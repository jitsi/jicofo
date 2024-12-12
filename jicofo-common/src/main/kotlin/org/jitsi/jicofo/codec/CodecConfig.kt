/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

open class CodecConfig(protected val base: String, protected val name: String) {
    protected open val enabled: Boolean by config {
        "$base.enabled".from(JitsiConfig.newConfig)
    }

    protected open val pt: Int by config {
        "$base.pt".from(JitsiConfig.newConfig)
    }

    fun enabled() = enabled && pt > 0

    @Throws(IllegalStateException::class)
    fun pt(): Int = if (enabled()) pt else throw IllegalStateException("$name is not enabled.")

    open val enableRemb = false
}

open class RtxCodecConfig(base: String, name: String) : CodecConfig(base, name) {
    protected open val rtxPt: Int by config {
        "$base.rtx-pt".from(JitsiConfig.newConfig)
    }

    fun rtxEnabled(): Boolean = enabled() && rtxPt > 0

    @Throws(IllegalStateException::class)
    fun rtxPt(): Int = if (rtxEnabled()) rtxPt else throw IllegalStateException("RTX is not enabled for $name")
}

private class RtxCodecConfigWithLegacy(
    legacyBase: String,
    newBase: String,
    name: String
) : RtxCodecConfig(newBase, name) {
    override val enabled: Boolean by config {
        "$legacyBase.ENABLE_$name".from(JitsiConfig.legacyConfig)
        "$newBase.enabled".from(JitsiConfig.newConfig)
    }

    override val pt: Int by config {
        "$legacyBase.${name}_PT".from(JitsiConfig.legacyConfig)
        "$newBase.pt".from(JitsiConfig.newConfig)
    }

    override val rtxPt: Int by config {
        "$legacyBase.${name}_RTX_PT".from(JitsiConfig.legacyConfig)
        "$newBase.rtx-pt".from(JitsiConfig.newConfig)
    }

    override val enableRemb: Boolean by config {
        "$newBase.enable-remb".from(JitsiConfig.newConfig)
    }
}

class OpusConfig : CodecConfig("jicofo.codec.audio.opus", "opus") {
    private val minptime: Int by config {
        "$base.minptime".from(JitsiConfig.newConfig)
    }

    fun minptime() = minptime

    private val useInbandFec: Boolean by config {
        "$base.use-inband-fec".from(JitsiConfig.newConfig)
    }

    fun useInbandFec() = useInbandFec

    @JvmField
    val red = CodecConfig("$base.red", "red")
}

open class RtpExtensionConfig(base: String) {
    open val enabled: Boolean by config {
        "$base.enabled".from(JitsiConfig.newConfig)
    }
    fun enabled() = enabled

    val id: Int by config {
        "$base.id".from(JitsiConfig.newConfig)
    }
    fun id() = id
}

private class RtpExtensionConfigWithLegacy(
    legacyEnabledName: String,
    base: String
) : RtpExtensionConfig(base) {
    override val enabled: Boolean by config {
        legacyEnabledName.from(JitsiConfig.legacyConfig)
        "$base.enabled".from(JitsiConfig.newConfig)
    }
}

class Config {
    @JvmField
    val av1: RtxCodecConfig = RtxCodecConfigWithLegacy(LEGACY_BASE, "jicofo.codec.video.av1", "AV1")

    @JvmField
    val vp8: RtxCodecConfig = RtxCodecConfigWithLegacy(LEGACY_BASE, "jicofo.codec.video.vp8", "VP8")

    @JvmField
    val vp9: RtxCodecConfig = RtxCodecConfigWithLegacy(LEGACY_BASE, "jicofo.codec.video.vp9", "VP9")

    @JvmField
    val h264: RtxCodecConfig = RtxCodecConfigWithLegacy(LEGACY_BASE, "jicofo.codec.video.h264", "H264")

    @JvmField
    val opus = OpusConfig()

    @JvmField
    val telephoneEvent = CodecConfig("jicofo.codec.audio.telephone-event", "telephone-event")

    @JvmField
    val audioLevel = RtpExtensionConfig("jicofo.codec.rtp-extensions.audio-level")

    @JvmField
    val av1DependencyDescriptor: RtpExtensionConfig =
        RtpExtensionConfig("jicofo.codec.rtp-extensions.av1-dependency-descriptor")

    val videoLayersAllocation: RtpExtensionConfig =
        RtpExtensionConfig("jicofo.codec.rtp-extensions.video-layers-allocation")

    @JvmField
    val tof: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_TOF", "jicofo.codec.rtp-extensions.tof")

    @JvmField
    val absSendTime: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_AST", "jicofo.codec.rtp-extensions.abs-send-time")

    @JvmField
    val rid: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_RID", "jicofo.codec.rtp-extensions.rid")

    @JvmField
    val tcc = RtpExtensionConfig("jicofo.codec.rtp-extensions.tcc")

    @JvmField
    val mid = RtpExtensionConfig("jicofo.codec.rtp-extensions.mid")

    @JvmField
    val videoContentType: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy(
            "$LEGACY_BASE.ENABLE_VIDEO_CONTENT_TYPE",
            "jicofo.codec.rtp-extensions.video-content-type"
        )

    @JvmField
    val framemarking: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_FRAMEMARKING", "jicofo.codec.rtp-extensions.framemarking")
    val extmapAllowMixed: Boolean by config {
        "jicofo.codec.rtp-extensions.extmap-allow-mixed".from(JitsiConfig.newConfig)
    }

    companion object {
        const val LEGACY_BASE = "org.jitsi.jicofo"

        @JvmField
        val config = Config()
    }
}
