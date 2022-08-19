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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig

class ConferenceConfigTest : ShouldSpec() {
    init {
        context("source-signaling-delays") {
            context("With no delay configured") {
                for (i in 0..100) {
                    ConferenceConfig.config.getSourceSignalingDelayMs(i) shouldBe 0
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
                    ConferenceConfig.config.apply {
                        getSourceSignalingDelayMs(0) shouldBe 0
                        getSourceSignalingDelayMs(50) shouldBe 0
                        getSourceSignalingDelayMs(99) shouldBe 0
                        getSourceSignalingDelayMs(100) shouldBe 500
                        getSourceSignalingDelayMs(199) shouldBe 500
                        getSourceSignalingDelayMs(200) shouldBe 1000
                        getSourceSignalingDelayMs(299) shouldBe 1000
                        getSourceSignalingDelayMs(300) shouldBe 2000
                        getSourceSignalingDelayMs(5000) shouldBe 2000
                    }
                }
            }
        }
    }
}
