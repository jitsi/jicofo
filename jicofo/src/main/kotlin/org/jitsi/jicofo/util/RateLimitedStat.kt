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

import org.jitsi.jicofo.TaskPools.Companion.scheduledPool
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A statistic whose value is only reported at a limited rate. */
class RateLimitedStat
@JvmOverloads
constructor(
    private val changeInterval: Duration,
    private val onChanged: (Int) -> Unit,
    initialValue: Int = 0,
    private val clock: Clock = Clock.systemUTC()
) {
    private val lock = Any()

    private var _value = initialValue

    private var lastChanged: Instant? = null

    private var updateTask: Future<*>? = null

    val value: Int
        get() = synchronized(lock) { _value }
    // TODO: we could also allow directly setting this value not just adjusting it, but will need to duplicate
    //  or refactor code from adjustValue

    fun adjustValue(delta: Int) {
        synchronized(lock) {
            _value += delta

            val now = Instant.now()
            if (updateTask != null) {
                return
            }

            lastChanged?.let {
                if (Duration.between(it, now) < changeInterval) {
                    val notificationTime = it.plus(changeInterval)
                    val delay = Duration.between(now, notificationTime)
                    updateTask = scheduledPool.schedule(
                        { this.reportChanged() },
                        delay.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                }
            }
        }
        reportChanged()
    }

    fun stop() = updateTask?.cancel(false)

    private fun reportChanged() {
        val value: Int
        synchronized(this) {
            value = this._value
            lastChanged = clock.instant()
            updateTask = null
        }
        onChanged(value)
    }
}
