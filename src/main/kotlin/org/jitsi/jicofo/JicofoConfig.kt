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
package org.jitsi.jicofo

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.metaconfig.config
import java.time.Duration

class JicofoConfig {
    val localRegion: String? by optionalconfig {
        "org.jitsi.jicofo.BridgeSelector.LOCAL_REGION".from(JitsiConfig.legacyConfig)
        "$BASE.local-region".from(JitsiConfig.newConfig)
    }

    val enableSctp: Boolean by config {
        "$BASE.sctp.enabled".from(JitsiConfig.newConfig)
    }

    /**
     * The ID of the jicofo instance to use for Octo.
     */
    val octoId: Int by config {
        "org.jitsi.jicofo.SHORT_ID".from(JitsiConfig.legacyConfig)
        "jicofo.octo.id".from(JitsiConfig.newConfig)
    }

    val conferenceStartTimeout: Duration by config {
        "org.jitsi.focus.IDLE_TIMEOUT".from(JitsiConfig.legacyConfig)
        "jicofo.conference-initial-timeout".from(JitsiConfig.newConfig)
    }

    val autoOwner: Boolean by config {
        "org.jitsi.jicofo.DISABLE_AUTO_OWNER".from(JitsiConfig.legacyConfig)
        "jicofo.auto-owner".from(JitsiConfig.newConfig)
    }

    fun enableSctp() = enableSctp

    fun localRegion() = localRegion

    companion object {
        const val BASE = "jicofo"

        @JvmField
        val config = JicofoConfig()
    }
}
