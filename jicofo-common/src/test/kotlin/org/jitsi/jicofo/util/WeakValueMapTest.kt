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
import io.kotest.matchers.shouldBe
import java.lang.ref.WeakReference

class WeakValueMapTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    // Note the test values must not have references held elsewhere (e.g. interned strings or boxed small integers).
    private class Value(val v: Int)

    private fun addCollectableValue(map: WeakValueMap<String, Value>): WeakReference<Value> {
        val value = Value(2)
        map.put("weak", value)
        return WeakReference(value)
    }

    init {
        val map = WeakValueMap<String, Value>(cleanInterval = 2)

        context("Basic operations") {
            val value = Value(1)
            map.put("one", value)

            should("get") {
                map.get("one")?.v shouldBe 1
                map.get("two") shouldBe null
            }
            should("containsKey") {
                map.containsKey("one") shouldBe true
                map.containsKey("two") shouldBe false
            }
            should("values") {
                map.values().map { it.v } shouldBe listOf(1)
            }
            should("remove") {
                map.remove("one")?.v shouldBe 1
                map.containsKey("one") shouldBe false
                map.values() shouldBe emptyList()
            }
        }
        context("Weak semantics") {
            val strong = Value(1)
            map.put("strong", strong)
            // The only strong reference to the value is held inside addCollectableValue, so it is collectable once
            // the function returns. The returned WeakReference tells us when it has actually been collected.
            val canary = addCollectableValue(map)
            map.containsKey("weak") shouldBe true

            var collected = false
            for (i in 0..500) {
                System.gc()
                if (canary.get() == null) {
                    collected = true
                    break
                }
                Thread.sleep(10)
            }

            should("expire entries once the value is collected") {
                collected shouldBe true
                map.get("weak") shouldBe null
                map.containsKey("weak") shouldBe false
                map.values().map { it.v } shouldBe listOf(1)
                map.containsKey("strong") shouldBe true
            }
        }
    }
}
