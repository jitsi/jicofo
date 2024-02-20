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
package org.jitsi.jicofo.metrics

import org.jitsi.config.JitsiConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.metaconfig.config
import org.jitsi.metrics.MetricsContainer
import org.jitsi.metrics.MetricsUpdater
import java.time.Duration

class JicofoMetricsContainer private constructor() : MetricsContainer(namespace = "jitsi_jicofo") {
    val metricsUpdater = MetricsUpdater(TaskPools.scheduledPool, updateInterval)

    companion object {
        private val updateInterval: Duration by config {
            "jicofo.metrics.update-interval".from(JitsiConfig.newConfig)
        }

        @JvmStatic
        val instance = JicofoMetricsContainer()
    }
}
