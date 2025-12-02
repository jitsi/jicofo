/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc
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

class TranscriptionConfigTest : ShouldSpec() {
    init {
        context("http-headers") {
            context("With no headers configured") {
                withNewConfig("") {
                    TranscriptionConfig.config.httpHeaders shouldBe emptyMap()
                }
            }

            context("With headers configured") {
                withNewConfig(
                    """
                    jicofo.transcription.http-headers {
                        "Authorization" = "Bearer token123"
                        "Content-Type" = "application/json"
                        "User-Agent" = "JitsiMeet/1.0"
                        "X-API-Key" = "secret-key"
                    }
                    """.trimIndent()
                ) {
                    TranscriptionConfig.config.httpHeaders shouldBe mapOf(
                        "Authorization" to "Bearer token123",
                        "Content-Type" to "application/json",
                        "User-Agent" to "JitsiMeet/1.0",
                        "X-API-Key" to "secret-key"
                    )
                }
            }

            context("With empty headers block") {
                withNewConfig(
                    """
                    jicofo.transcription.http-headers { }
                    """.trimIndent()
                ) {
                    TranscriptionConfig.config.httpHeaders shouldBe emptyMap()
                }
            }

            context("With single header") {
                withNewConfig(
                    """
                    jicofo.transcription.http-headers {
                        "Authorization" = "Bearer single-token"
                    }
                    """.trimIndent()
                ) {
                    TranscriptionConfig.config.httpHeaders shouldBe mapOf(
                        "Authorization" to "Bearer single-token"
                    )
                }
            }
        }

        context("url-template with headers") {
            context("Both URL template and headers configured") {
                withNewConfig(
                    """
                    jicofo.transcription {
                        url-template = "wss://{{REGION}}.example.com/recorder/{{MEETING_ID}}"
                        http-headers {
                            "Authorization" = "Bearer api-token"
                            "Content-Type" = "application/json"
                        }
                    }
                    """.trimIndent()
                ) {
                    val config = TranscriptionConfig.config

                    // Test URL functionality still works
                    val url = config.getUrl("test-meeting-123")
                    url?.resolve("REGION", "us-west")?.toString() shouldBe
                        "wss://us-west.example.com/recorder/test-meeting-123"

                    // Test headers are available
                    config.httpHeaders shouldBe mapOf(
                        "Authorization" to "Bearer api-token",
                        "Content-Type" to "application/json"
                    )
                }
            }
        }
    }
}
