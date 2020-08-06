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

class VideoCodecConfig(
    legacyBase: String,
    newBase: String,
    private val codecName: String
) {
    private val enabled: Boolean by config {
        "$legacyBase.ENABLE_$codecName".from(JitsiConfig.legacyConfig)
        "$newBase.enabled".from(JitsiConfig.newConfig)
    }

    fun enabled() = enabled && pt > 0

    private val pt: Int by config {
        "$legacyBase.${codecName}_PT".from(JitsiConfig.legacyConfig)
        "$newBase.pt".from(JitsiConfig.newConfig)
    }

    @Throws(IllegalStateException::class)
    fun pt(): Int = if (enabled()) pt else throw IllegalStateException("$codecName is not enabled.")

    private val rtxPt: Int by config {
        "$legacyBase.${codecName}_RTX_PT".from(JitsiConfig.legacyConfig)
        "$newBase.rtx-pt".from(JitsiConfig.newConfig)
    }

    fun rtxEnabled(): Boolean = enabled() && rtxPt > 0

    @Throws(IllegalStateException::class)
    fun rtxPt(): Int = if (rtxEnabled()) rtxPt else throw IllegalStateException("RTX is not enabled for $codecName")

}

open class RtpExtensionConfig(base: String ) {
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
    val vp8 = VideoCodecConfig(LEGACY_BASE, "$BASE.vp8", "VP8")
    @JvmField
    val vp9 = VideoCodecConfig(LEGACY_BASE, "$BASE.vp9", "VP9")
    @JvmField
    val h264 = VideoCodecConfig(LEGACY_BASE, "$BASE.h264", "H264")

    @JvmField
    val framemarking: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_FRAMEMARKING", "$EXTENSIONS_BASE.framemarking")
    @JvmField
    val absSendTime: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_AST", "$EXTENSIONS_BASE.abs-send-time")
    @JvmField
    val rid: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_RID", "$EXTENSIONS_BASE.rid")
    @JvmField
    val tof: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_TOF", "$EXTENSIONS_BASE.tof")
    @JvmField
    val videoContentType: RtpExtensionConfig =
        RtpExtensionConfigWithLegacy("$LEGACY_BASE.ENABLE_VIDEO_CONTENT_TYPE", "$EXTENSIONS_BASE.video-content-type")
    @JvmField
    val tcc = RtpExtensionConfig("$EXTENSIONS_BASE.tcc")
    @JvmField
    val audioLevel = RtpExtensionConfig("$EXTENSIONS_BASE.audio-level")

    companion object {
        const val LEGACY_BASE = "org.jitsi.jicofo"
        const val BASE = "jicofo.codec"
        const val EXTENSIONS_BASE = "$BASE.rtp-extensions"

        @JvmField
        val config = Config()
    }
}

