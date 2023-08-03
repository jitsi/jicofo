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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.time.FakeClock
import java.time.Duration

class PendingCountTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        context("Pending count") {
            val clock = FakeClock()
            val pendingCount = PendingCount(Duration.ofSeconds(15), clock)

            should("Correctly count events that are pending") {
                pendingCount.getCount() shouldBe 0
                pendingCount.eventPending()
                pendingCount.getCount() shouldBe 1
                pendingCount.eventPending()
                pendingCount.getCount() shouldBe 2
            }

            should("Correctly count down when events occur") {
                pendingCount.eventPending()
                pendingCount.eventPending()
                pendingCount.getCount() shouldBe 2
                pendingCount.eventOccurred()
                pendingCount.getCount() shouldBe 1
            }

            should("Expire pending events after the expiration time") {
                pendingCount.eventPending()
                clock.elapse(Duration.ofSeconds(10))

                pendingCount.eventPending()
                pendingCount.getCount() shouldBe 2

                clock.elapse(Duration.ofSeconds(10))
                pendingCount.getCount() shouldBe 1

                pendingCount.eventOccurred()
                pendingCount.getCount() shouldBe 0
            }

            should("Correctly handle count when occurred events expire") {
                pendingCount.eventPending()
                clock.elapse(Duration.ofSeconds(10))

                pendingCount.eventPending()
                pendingCount.eventOccurred()
                pendingCount.getCount() shouldBe 1

                clock.elapse(Duration.ofSeconds(10))
                pendingCount.getCount() shouldBe 1

                clock.elapse(Duration.ofSeconds(10))
                pendingCount.getCount() shouldBe 0
            }

            should("Not let the count go negative even if more events occur than are pending") {
                pendingCount.eventPending()
                pendingCount.eventOccurred()
                pendingCount.getCount() shouldBe 0

                pendingCount.eventOccurred()
                pendingCount.getCount() shouldBe 0

                clock.elapse(Duration.ofSeconds(10))

                pendingCount.eventPending()
                pendingCount.getCount() shouldBe 1

                clock.elapse(Duration.ofSeconds(10))
                pendingCount.getCount() shouldBe 1

                clock.elapse(Duration.ofSeconds(10))
                pendingCount.getCount() shouldBe 0
            }
        }
    }
}
