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
package org.jitsi.jicofo.util

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-cleaning map with weak values.
 * TODO: maybe move to jitsi-utils
 */
class WeakValueMap<K, V>(
    private val cleanInterval: Int = 100
) {
    private val map: MutableMap<K, WeakReference<V>> = ConcurrentHashMap<K, WeakReference<V>>()
    private var i = 0

    fun get(key: K) = map[key]?.get().also {
        maybeClean()
    }
    fun put(key: K, value: V) {
        map[key] = WeakReference(value)
        maybeClean()
    }
    fun containsKey(key: K): Boolean = (map[key]?.get() != null).also {
        maybeClean()
    }
    fun remove(key: K) = map.remove(key)?.get()
    fun values(): List<V> {
        clean()
        return map.values.mapNotNull { it.get() }.toList()
    }

    private fun maybeClean() = ((i++ % cleanInterval == 0)) && clean()
    private fun clean() = map.values.removeIf { it.get() == null }
}
