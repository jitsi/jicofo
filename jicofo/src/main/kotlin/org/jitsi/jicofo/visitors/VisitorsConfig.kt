/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.jicofo.visitors

import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import java.time.Duration

class VisitorsConfig private constructor() {
    val enabled: Boolean by config {
        "jicofo.visitors.enabled".from(newConfig)
    }
    val maxParticipants: Int by config {
        "jicofo.visitors.max-participants".from(newConfig)
    }
    val maxVisitorsPerNode: Int by config {
        "jicofo.visitors.max-visitors-per-node".from(newConfig)
    }

    val notificationInterval: Duration by config {
        "jicofo.visitors.notification-interval".from(newConfig)
    }

    val autoEnableBroadcast: Boolean by config {
        "jicofo.visitors.auto-enable-broadcast".from(newConfig)
    }

    val requireMucConfigFlag: Boolean by config {
        "jicofo.visitors.require-muc-config-flag".from(newConfig)
    }

    val enableLiveRoom: Boolean by config {
        "jicofo.visitors.enable-live-room".from(newConfig)
    }

    companion object {
        @JvmField
        val config = VisitorsConfig()
    }
}
