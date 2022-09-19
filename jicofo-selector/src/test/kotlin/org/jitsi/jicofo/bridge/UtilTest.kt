/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi.jicofo.bridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension

class UtilTest : ShouldSpec() {
    init {
        context("ColibriStatsExtension.getDouble test") {
            with(ColibriStatsExtension()) {
                addStat("int", 5)
                addStat("double", 5.0)
                addStat("valid-string", "5")
                addStat("invalid-string", "five")

                getDouble("int") shouldBe 5.toDouble()
                getDouble("double") shouldBe 5.toDouble()
                getDouble("valid-string") shouldBe 5.toDouble()
                getDouble("invalid-string") shouldBe null
                getDouble("non-existent-key") shouldBe null
            }
        }
    }
}
