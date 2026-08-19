/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo.xmpp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.MockXmppProvider
import org.jitsi.jicofo.mock.inPlaceExecutor

class VisitorConnectionMonitorTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    init {
        val v1 = MockXmppProvider(MockXmppConnection().xmppConnection, "v1")
        val v2 = MockXmppProvider(MockXmppConnection().xmppConnection, "v2")
        val resetNodes = mutableListOf<String>()
        val monitor = VisitorConnectionMonitor(listOf(v1.xmppProvider, v2.xmppProvider)) { resetNodes.add(it) }

        context("When a visitor connection authenticates") {
            should("not report a reset if the stream was resumed") {
                v1.authenticated(resumed = true)

                resetNodes.shouldBeEmpty()
            }
            should("report a reset if the stream was not resumed") {
                v1.authenticated(resumed = false)

                resetNodes shouldContainExactly listOf("v1")
            }
            should("report a reset for the node that reconnected only") {
                v2.authenticated(resumed = false)
                v1.authenticated(resumed = true)

                resetNodes shouldContainExactly listOf("v2")
            }
            should("report every reset") {
                v1.authenticated(resumed = false)
                v2.authenticated(resumed = false)
                v1.authenticated(resumed = false)

                resetNodes shouldContainExactly listOf("v1", "v2", "v1")
            }
        }
        context("After shutdown") {
            should("not report a reset") {
                monitor.shutdown()
                v1.authenticated(resumed = false)

                resetNodes.shouldBeEmpty()
            }
        }
    }
}
