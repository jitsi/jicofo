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
package org.jitsi.jicofo.mock

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * An executor which queues tasks and runs them only when [runAll] is called. Use it to control when queued work
 * (e.g. Jingle IQ processing) runs relative to other events.
 */
class DeferredExecutor : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    /** Run the queued tasks in order, including the tasks that they queue, until none are left. */
    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }

    override fun shutdown() {}
    override fun shutdownNow(): List<Runnable> = emptyList()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
