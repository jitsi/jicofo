/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2023 - present 8x8, Inc
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

import java.lang.management.ManagementFactory
import org.jitsi.jicofo.metrics.JicofoMetricsContainer.Companion.instance as metricsContainer

class GlobalMetrics {
    companion object {
        @JvmField
        val threadCount = metricsContainer.registerLongGauge(
            "threads",
            "The current number of JVM threads"
        )

        val xmppDisconnects = metricsContainer.registerCounter(
            "xmpp_disconnects",
            "The number of times one of the XMPP connections has disconnected."
        )

        fun update() {
            threadCount.set(ManagementFactory.getThreadMXBean().threadCount.toLong())
        }
    }
}
