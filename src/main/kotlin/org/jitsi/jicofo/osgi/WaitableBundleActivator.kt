/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.jicofo.osgi

import org.jitsi.utils.secs
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import java.time.Duration

/**
 * A bundle activator that we can wait on until it starts, and exposes its bundle context.
 */
class WaitableBundleActivator : BundleActivator {
    override fun start(bundleContext: BundleContext) = synchronized(lock) {
        if (!started) {
            started = true
            Companion.bundleContext = bundleContext
            lock.notifyAll()
        }
    }

    override fun stop(bundleContext: BundleContext) = synchronized(lock) {
        Companion.bundleContext = null
    }

    companion object {
        private var started = false
        private val lock = Object()
        var bundleContext: BundleContext? = null
            private set

        @JvmOverloads
        @JvmStatic
        fun waitUntilStarted(timeout: Duration = 5.secs) = synchronized(lock) {
            if (!started) {
                try {
                    lock.wait(timeout.toMillis())
                    if (!started) {
                        throw RuntimeException("Failed to wait for the activator to get started")
                    }
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            }
        }
    }
}