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

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.PacketQueue
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * An [Executor] which uses a [PacketQueue] to execute tasks via a delegating [ExecutorService] in the order in which
 * they were submitted.
 */
class QueueExecutor(
    executorService: ExecutorService,
    id: String,
    parentLogger: Logger = createLogger()
) : Executor, PacketQueue<Runnable>(
    Integer.MAX_VALUE,
    true,
    id,
    object : PacketHandler<Runnable> {
        private val logger = parentLogger.createChildLogger(QueueExecutor::class.java.simpleName).apply {
            addContext("id", id)
        }

        override fun handlePacket(runnable: Runnable): Boolean = try {
            runnable.run()
            true
        } catch (e: Throwable) {
            logger.error("Failed to execute command.", e)
            false
        }
    },
    executorService,
    Clock.systemUTC()
) {
    override fun execute(command: Runnable) = add(command)

    // We need to define a companion object for [createLogger] to work.
    companion object
}
