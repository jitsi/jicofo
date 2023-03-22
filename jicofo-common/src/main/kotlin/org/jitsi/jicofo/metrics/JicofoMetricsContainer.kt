/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022 - present 8x8, Inc
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
package org.jitsi.jicofo.metrics

import org.jitsi.config.JitsiConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.metaconfig.config
import org.jitsi.metrics.MetricsContainer
import org.jitsi.utils.logging2.createLogger
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class JicofoMetricsContainer private constructor() : MetricsContainer(namespace = "jitsi_jicofo") {
    private val logger = createLogger()
    private val subtasks: MutableList<() -> Unit> = CopyOnWriteArrayList()

    private var updateTask: ScheduledFuture<*>? = null

    // Allow updates to be disabled for tests
    var disablePeriodicUpdates = false

    fun addUpdateTask(subtask: () -> Unit) {
        if (disablePeriodicUpdates) {
            logger.warn("Periodic updates are disabled, will not execute update task.")
            return
        }

        subtasks.add(subtask)
        synchronized(this) {
            if (updateTask == null) {
                logger.info("Scheduling metrics update task with interval $updateInterval.")
                updateTask = TaskPools.scheduledPool.scheduleAtFixedRate(
                    { updateMetrics() },
                    0,
                    updateInterval.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    fun updateMetrics() {
        synchronized(this) {
            logger.debug("Running ${subtasks.size} subtasks.")
            subtasks.forEach {
                try {
                    it.invoke()
                } catch (e: Exception) {
                    logger.warn("Exception while running subtask", e)
                }
            }
        }
    }

    fun stop() = synchronized(this) {
        updateTask?.cancel(false)
        updateTask = null
        subtasks.clear()
    }

    companion object {
        @JvmStatic
        val instance = JicofoMetricsContainer()

        val updateInterval: Duration by config {
            "jicofo.metrics.update-interval".from(JitsiConfig.newConfig)
        }
    }
}
