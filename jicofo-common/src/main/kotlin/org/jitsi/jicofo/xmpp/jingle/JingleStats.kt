/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

package org.jitsi.jicofo.xmpp.jingle

import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.metrics.CounterMetric
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.json.simple.JSONObject
import java.util.concurrent.ConcurrentHashMap

class JingleStats {
    companion object {
        private val stanzasReceivedByAction: MutableMap<JingleAction, CounterMetric> = ConcurrentHashMap()
        private val stanzasSentByAction: MutableMap<JingleAction, CounterMetric> = ConcurrentHashMap()

        @JvmStatic
        fun stanzaReceived(action: JingleAction) {
            stanzasReceivedByAction.computeIfAbsent(action) {
                JicofoMetricsContainer.instance.registerCounter(
                    "jingle_${action.name.lowercase()}_received",
                    "Number of ${action.name} stanzas received"
                )
            }.inc()
        }

        @JvmStatic
        fun stanzaSent(action: JingleAction) {
            stanzasSentByAction.computeIfAbsent(action) {
                JicofoMetricsContainer.instance.registerCounter(
                    "jingle_${action.name.lowercase()}_sent",
                    "Number of ${action.name} stanzas sent."
                )
            }.inc()
        }

        @JvmStatic
        fun toJson() = JSONObject().apply {
            this["sent"] = stanzasSentByAction.map { it.key to it.value.get() }.toMap()
            this["received"] = stanzasReceivedByAction.map { it.key to it.value.get() }.toMap()
        }
    }
}
