package org.jitsi.jicofo.bridge

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.optionalconfig
import java.time.Duration

class ExternalBridgeSelectionStrategyConfig private constructor() {
    val url: String? by optionalconfig {
        "${BASE}.url".from(JitsiConfig.newConfig)
    }
    fun url() = url

    val timeout: Duration? by optionalconfig {
        "${BASE}.timeout".from(JitsiConfig.newConfig)
    }
    fun timeout() = timeout

    val fallbackStrategy: String? by optionalconfig {
        "${BASE}.fallback-strategy".from(JitsiConfig.newConfig)
    }
    fun fallbackStrategy() = fallbackStrategy

    companion object {
        const val BASE = "jicofo.bridge.external-selection-strategy"

        @JvmField
        val config = ExternalBridgeSelectionStrategyConfig()
    }
}