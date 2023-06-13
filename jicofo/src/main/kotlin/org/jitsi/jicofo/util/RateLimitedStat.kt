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

    private var setter: (() -> Int)? = null

    val value: Int
        get() = synchronized(lock) { _value }

    fun adjustValue(delta: Int) {
        val report: Boolean
        synchronized(lock) {
            _value += delta

            report = valueUpdated()
        }

        if (report) {
            execute()
        }
    }

    /** Pass a function which will retrieve the value.  This will only be called once the timeout has
     * expired, thus rate-limiting how often the value will be set.  Use this to rate limit expensive methods
     * for setting the value.
     */
    fun setValue(setter: () -> Int) {
        val report: Boolean
        synchronized(lock) {
            this.setter = setter

            report = valueUpdated()
        }

        if (report) {
            execute()
        }
    }

    /** Call this inside the synchronization block after the value or its setter is updated.  If it returns true,
     * call [execute] immediately, *outside* the synchronization block.
     */
    private fun valueUpdated(): Boolean {
        val now = Instant.now()
        if (updateTask != null) {
            return false
        }

        lastChanged?.let {
            if (Duration.between(it, now) < changeInterval) {
                val notificationTime = it.plus(changeInterval)
                val delay = Duration.between(now, notificationTime)
                updateTask = scheduledPool.schedule(
                    { this.execute() },
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS
                )
                return false
            }
        }
        return true
    }

    private fun execute() {
        val value: Int
        synchronized(lock) {
            setter?.let { this._value = it() }.also { setter = null }
            value = this._value
            lastChanged = clock.instant()
            updateTask = null
        }
        onChanged(value)
    }

    fun stop() = updateTask?.cancel(false)
}
