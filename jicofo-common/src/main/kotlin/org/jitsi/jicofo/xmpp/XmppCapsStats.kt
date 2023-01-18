/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022-Present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger

class XmppCapsStats {
    companion object {
        /**
         *  Maps a nodeVer string (the "node" and "ver" attributes from a caps extension (XEP-0115) joined by "#") to
         *  the associated set of features and a counter for the number of participants with that nodeVer.
         */
        private val map: MutableMap<String, FeaturesAndCount> = mutableMapOf()
        private const val MAX_ENTRIES = 1000
        private val logger = createLogger()

        @JvmStatic
        val stats: OrderedJsonObject
            get() = OrderedJsonObject().apply {
                synchronized(map) {
                    map.forEach { (nodeVer, e) ->
                        this[nodeVer] = e.json()
                    }
                }
            }

        fun update(nodeVer: String, features: Set<Features>) {
            synchronized(map) {
                if (map.size < MAX_ENTRIES) {
                    map.computeIfAbsent(nodeVer) { FeaturesAndCount(features) }.count++
                } else {
                    map[nodeVer]?.let {
                        it.count++
                    } ?: logger.warn("Too many entries. Ignoring nodeVer=$nodeVer, features=$features")
                }
            }
        }
    }

    private class FeaturesAndCount(val features: Set<Features>) {
        /** The number of participants seen with this set of features. */
        var count = 0
        fun json() = OrderedJsonObject().apply {
            this["count"] = count
            this["features"] = features.map { it.name }
        }
    }
}
