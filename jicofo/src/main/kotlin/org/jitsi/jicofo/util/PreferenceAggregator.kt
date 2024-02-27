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
package org.jitsi.jicofo.util

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.json.simple.JSONArray

/** Aggregate lists of preferences coming from a large group of people, such that the resulting aggregated
 * list consists of preference items supported by everyone, and in a rough consensus of preference order.
 *
 * The intended use case is maintaining the list of supported codecs for conference visitors.
 *
 * Preference orders are aggregated using the Borda count; this isn't theoretically optimal, but it should be
 * good enough, and it's computationally cheap.
 */
class PreferenceAggregator(
    parentLogger: Logger,
    private val onChanged: (List<String>) -> Unit
) {
    private val logger = createChildLogger(parentLogger)
    private val lock = Any()

    var aggregate: List<String> = emptyList()
        private set

    var count = 0
        private set

    private val values = mutableMapOf<String, ValueInfo>()

    /**
     * Add a preference to the aggregator.
     */
    fun addPreference(prefs: List<String>) {
        val distinctPrefs = prefs.distinct()
        if (distinctPrefs != prefs) {
            logger.warn("Preferences $prefs contains repeated values")
        }
        synchronized(lock) {
            count++
            distinctPrefs.forEachIndexed { index, element ->
                val info = values.computeIfAbsent(element) { ValueInfo() }
                info.count++
                info.rankAggregate += index
            }
            updateAggregate()
        }
    }

    /**
     * Remove a preference from the aggregator.
     */
    fun removePreference(prefs: List<String>) {
        val distinctPrefs = prefs.distinct()
        if (distinctPrefs != prefs) {
            logger.warn("Preferences $prefs contains repeated values")
        }
        synchronized(lock) {
            count--
            check(count >= 0) {
                "Preference count $count should not be negative"
            }
            distinctPrefs.forEachIndexed { index, element ->
                val info = values[element]
                check(info != null) {
                    "Preference info for $element should exist when preferences are being removed"
                }
                info.count--
                check(info.count >= 0) {
                    "Preference count for $element ${info.count} should not be negative"
                }
                info.rankAggregate -= index
                check(info.rankAggregate >= 0) {
                    "Preference rank aggregate for $element ${info.rankAggregate} should not be negative"
                }
                if (info.count == 0) {
                    check(info.rankAggregate == 0) {
                        "Preference rank aggregate for $element ${info.rankAggregate} should be zero " +
                            "when preference count is 0"
                    }
                    values.remove(element)
                }
            }
            updateAggregate()
        }
    }

    fun reset() {
        synchronized(lock) {
            aggregate = emptyList()
            count = 0
            values.clear()
        }
    }

    fun debugState() = OrderedJsonObject().apply {
        synchronized(lock) {
            put("count", count)
            put(
                "ranks",
                OrderedJsonObject().apply {
                    this@PreferenceAggregator.values.asSequence()
                        .sortedBy { it.value.rankAggregate }
                        .forEach { put(it.key, it.value.debugState()) }
                }
            )
            put("aggregate", JSONArray().apply { addAll(aggregate) })
        }
    }

    private fun updateAggregate() {
        val newAggregate = values.asSequence()
            .filter { it.value.count == count }
            .sortedBy { it.value.rankAggregate }
            .map { it.key }
            .toList()
        if (aggregate != newAggregate) {
            aggregate = newAggregate
            /* ?? Do we need to drop the lock before calling this? */
            onChanged(aggregate)
        }
    }

    private class ValueInfo {
        var count = 0
        var rankAggregate = 0

        fun debugState() = OrderedJsonObject().apply {
            put("count", count)
            put("rank_aggregate", rankAggregate)
        }
    }
}
