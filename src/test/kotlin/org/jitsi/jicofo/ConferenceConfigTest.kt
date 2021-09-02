/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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
package org.jitsi.jicofo

import io.kotest.matchers.shouldBe

class ConferenceConfigTest : ConfigTest() {
    init {
        context("source-signaling-delays") {
            context("With no delay configured") {
                val config = ConferenceConfig()
                for (i in 0..100) {
                    config.getSourceSignalingDelayMs(i) shouldBe 0
                }
            }
            context("With delay configured") {
                withNewConfig(
                    """
                    jicofo.conference.source-signaling-delays {
                        // Intentionally out of order
                        200 = 1000,
                        100 = 500,
                        300 = 2000
                    }
                    """.trimIndent()
                ) {
                    val config = ConferenceConfig()
                    config.getSourceSignalingDelayMs(0) shouldBe 0
                    config.getSourceSignalingDelayMs(50) shouldBe 0
                    config.getSourceSignalingDelayMs(99) shouldBe 0
                    config.getSourceSignalingDelayMs(100) shouldBe 500
                    config.getSourceSignalingDelayMs(199) shouldBe 500
                    config.getSourceSignalingDelayMs(200) shouldBe 1000
                    config.getSourceSignalingDelayMs(299) shouldBe 1000
                    config.getSourceSignalingDelayMs(300) shouldBe 2000
                    config.getSourceSignalingDelayMs(5000) shouldBe 2000
                }
            }
        }
    }
}
