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

import org.jitsi.utils.ms
import org.jitsi.utils.stats.RateTracker
import java.time.Clock
import java.time.Duration

/** Keep a count of some events which have been triggered to happen within some interval, but which
 * may or may not happen, and thus should expire from the count after some time if they have not.
 * The intended use case is visitors who have been invited to a visitor ChatRoom.
 */
class PendingCount(
    timeout: Duration,
    clock: Clock = Clock.systemUTC()
) {
    private var occurredEvents = 0L

    private var tracker = object : RateTracker(timeout, 100.ms, clock) {
        override fun bucketExpired(count: Long) {
            occurredEvents = (occurredEvents - count).coerceAtLeast(0)
        }
    }

    @Synchronized
    fun eventPending() {
        tracker.update(1)
    }

    @Synchronized
    fun eventOccurred() {
        occurredEvents = (occurredEvents + 1).coerceAtMost(tracker.getAccumulatedCount())
    }

    @Synchronized
    fun getCount(): Long = tracker.getAccumulatedCount() - occurredEvents
}
