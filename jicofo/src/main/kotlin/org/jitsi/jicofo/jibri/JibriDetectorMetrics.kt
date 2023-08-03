/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2023-Present 8x8, Inc.
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
package org.jitsi.jicofo.jibri

import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.utils.OrderedJsonObject

class JibriDetectorMetrics {
    companion object {
        private val jibriInstanceCount = JicofoMetricsContainer.instance.registerLongGauge(
            "jibri_instances",
            "Current number of jibri instances",
            0
        )
        private val jibriInstanceAvailableCount = JicofoMetricsContainer.instance.registerLongGauge(
            "jibri_instances_available",
            "Current number of available (not in use) jibri instances",
            0
        )
        private val sipJibriInstanceCount = JicofoMetricsContainer.instance.registerLongGauge(
            "sip_jibri_instances",
            "Current number of SIP jibri instances",
            0
        )
        private val sipJibriInstanceAvailableCount = JicofoMetricsContainer.instance.registerLongGauge(
            "sip_jibri_instances_available",
            "Current number of available (not in use) SIP jibri instances",
            0
        )

        fun updateMetrics(jibriDetector: JibriDetector?, sipJibriDetector: JibriDetector?) {
            jibriDetector?.instanceCount?.toLong()?.let {
                jibriInstanceCount.set(it)
            }
            jibriDetector?.getInstanceCount { it.status.isAvailable }?.toLong()?.let {
                jibriInstanceAvailableCount.set(it)
            }
            sipJibriDetector?.instanceCount?.toLong()?.let {
                sipJibriInstanceCount.set(it)
            }
            sipJibriDetector?.getInstanceCount { it.status.isAvailable }?.toLong()?.let {
                sipJibriInstanceAvailableCount.set(it)
            }
        }

        fun appendStats(o: OrderedJsonObject) {
            o["jibri_detector"] = OrderedJsonObject().apply {
                put("count", jibriInstanceCount.get())
                put("available", jibriInstanceAvailableCount.get())
            }
            o["sip_jibri_detector"] = OrderedJsonObject().apply {
                put("count", sipJibriInstanceCount.get())
                put("available", sipJibriInstanceAvailableCount.get())
            }
        }
    }
}
