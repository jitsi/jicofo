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

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * An executor that queues tasks until [runAll] is called, so tests can control when "async" work executes. Unlike
 * [inPlaceExecutor] it does not run tasks re-entrantly, which matters for code that assumes responses are processed
 * on a separate thread (e.g. after a lock has been released).
 */
class PendingExecutor {
    private val pending = ArrayDeque<Runnable>()

    val executor: ExecutorService = mockk {
        every { submit(any<Runnable>()) } answers {
            pending.add(firstArg<Runnable>())
            CompletableFuture<Unit>().apply { complete(Unit) }
        }
        every { execute(any()) } answers {
            pending.add(firstArg<Runnable>())
        }
    }

    /** Run all queued tasks, including any that get queued while draining. */
    fun runAll() {
        while (pending.isNotEmpty()) {
            pending.removeFirst().run()
        }
    }
}
