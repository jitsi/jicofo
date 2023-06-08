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

import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import java.time.Duration

class JicofoConfig private constructor() {
    val localRegion: String? by optionalconfig {
        "org.jitsi.jicofo.BridgeSelector.LOCAL_REGION".from(legacyConfig)
        "$BASE.local-region".from(newConfig)
    }

    val enableSctp: Boolean by config {
        "$BASE.sctp.enabled".from(newConfig)
    }

    fun enableSctp() = enableSctp

    fun localRegion() = localRegion

    // TODO this logically should be in VisitorsConfig, but that's in jicofo and this is needed by ChatRoom
    // in jicofo-common
    val vnodeJoinLatencyInterval: Duration by config {
        "jicofo.visitors.vnode-join-latency-interval".from(newConfig)
    }

    companion object {
        const val BASE = "jicofo"

        @JvmField
        val config = JicofoConfig()
    }
}
