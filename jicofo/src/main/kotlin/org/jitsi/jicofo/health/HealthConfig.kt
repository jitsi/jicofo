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
package org.jitsi.jicofo.health

import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import java.time.Duration

class HealthConfig private constructor() {
    val enabled: Boolean by config {
        "org.jitsi.jicofo.health.ENABLE_HEALTH_CHECKS".from(legacyConfig)
        "jicofo.health.enabled".from(newConfig)
    }

    val interval: Duration by config {
        "jicofo.health.interval".from(newConfig)
    }

    val timeout: Duration by config {
        "jicofo.health.timeout".from(newConfig)
    }

    val maxCheckDuration: Duration by config {
        "jicofo.health.max-check-duration".from(newConfig)
    }

    val roomNamePrefix: String by config {
        "jicofo.health.room-name-prefix".from(newConfig)
    }

    companion object {
        @JvmField
        val config = HealthConfig()
    }
}
