/*
 * Copyright @ 2026 - present 8x8, Inc.
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
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.TaskPools
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService

class RateLimitedStatTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val clock = FakeClock()
    private val reported = mutableListOf<Int>()

    /** Tasks scheduled for later execution, run them with [runPending]. */
    private val pendingTasks = mutableListOf<Runnable>()
    private val capturingScheduler: ScheduledExecutorService = mockk {
        every { schedule(any(), any(), any()) } answers {
            pendingTasks.add(firstArg())
            mockk(relaxed = true)
        }
    }

    private fun runPending() {
        val tasks = pendingTasks.toList()
        pendingTasks.clear()
        tasks.forEach { it.run() }
    }

    private val interval = Duration.ofSeconds(10)
    private val stat = RateLimitedStat(interval, { reported.add(it) }, initialValue = 0, clock = clock)

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.scheduledPool = capturingScheduler
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetScheduledPool()
    }

    init {
        context("The first change is reported immediately") {
            stat.value = 1
            reported shouldBe listOf(1)
            pendingTasks.size shouldBe 0
        }
        context("Changes within the interval are batched") {
            stat.value = 1
            reported shouldBe listOf(1)

            clock.elapse(Duration.ofSeconds(1))
            stat.adjustValue(1)
            stat.adjustValue(1)
            reported shouldBe listOf(1)
            // Only one update task should have been scheduled.
            pendingTasks.size shouldBe 1

            runPending()
            reported shouldBe listOf(1, 3)
            stat.value shouldBe 3
        }
        context("A change after the interval has passed is reported immediately") {
            stat.value = 1
            reported shouldBe listOf(1)

            clock.elapse(interval.plusSeconds(1))
            stat.value = 2
            reported shouldBe listOf(1, 2)
            pendingTasks.size shouldBe 0
        }
        context("adjustValue accumulates") {
            stat.adjustValue(5)
            stat.value shouldBe 5
            clock.elapse(Duration.ofSeconds(1))
            stat.adjustValue(-2)
            stat.value shouldBe 3
            runPending()
            reported shouldBe listOf(5, 3)
        }
    }
}
