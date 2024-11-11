/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022 - present 8x8, Inc
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
package org.jitsi.jicofo.ktor

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

class RestConfig private constructor() {
    val port: Int by config {
        "jicofo.rest.port".from(JitsiConfig.newConfig)
    }

    val host: String by config {
        "jicofo.rest.host".from(JitsiConfig.newConfig)
    }

    private val enabledProp: Boolean by config {
        "jicofo.rest.enabled".from(JitsiConfig.newConfig)
    }

    val enabled = enabledProp && port > 0

    val enablePrometheus: Boolean by config {
        "jicofo.rest.prometheus.enabled".from(JitsiConfig.newConfig)
    }

    val enableConferenceRequest: Boolean by config {
        "jicofo.rest.conference-request.enabled".from(JitsiConfig.newConfig)
    }

    val enableMoveEndpoints: Boolean by config {
        "jicofo.rest.move-endpoints.enabled".from(JitsiConfig.newConfig)
    }

    val enableDebug: Boolean by config {
        "jicofo.rest.debug.enabled".from(JitsiConfig.newConfig)
    }

    val pinEnabled: Boolean by config {
        "jicofo.rest.pin.enabled".from(JitsiConfig.newConfig)
    }

    companion object {
        @JvmField
        val config = RestConfig()
    }
}
