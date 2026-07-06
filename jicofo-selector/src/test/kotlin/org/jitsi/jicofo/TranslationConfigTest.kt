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
import org.jitsi.jicofo.xmpp.RoomMetadata

class TranslationConfigTest : ShouldSpec() {
    init {
        context("http-headers") {
            context("With no headers configured") {
                withNewConfig("") {
                    TranslationConfig.config.httpHeaders shouldBe emptyMap()
                }
            }

            context("With headers configured") {
                withNewConfig(
                    """
                    jicofo.translation.http-headers {
                        "Authorization" = "Bearer token123"
                        "X-API-Key" = "secret-key"
                    }
                    """.trimIndent()
                ) {
                    TranslationConfig.config.httpHeaders shouldBe mapOf(
                        "Authorization" to "Bearer token123",
                        "X-API-Key" to "secret-key"
                    )
                }
            }
        }

        context("processTranslationMetadata") {
            val baseHeaders = mapOf("Base-Header" to "base-value", "Shared-Header" to "base-shared")

            context("With null translation") {
                should("return null so callers fall back to config headers") {
                    TranslationConfig.processTranslationMetadata(null, baseHeaders) shouldBe null
                }
            }

            context("With translation having no headers") {
                should("return null so callers fall back to config headers") {
                    TranslationConfig.processTranslationMetadata(
                        RoomMetadata.Metadata.Translation(),
                        baseHeaders
                    ) shouldBe null
                }
            }

            context("With custom headers") {
                val customHeaders = mapOf("X-Custom" to "custom-value", "Shared-Header" to "custom-shared")
                should("return base headers merged with custom, custom taking precedence") {
                    TranslationConfig.processTranslationMetadata(
                        RoomMetadata.Metadata.Translation(httpHeaders = customHeaders),
                        baseHeaders
                    ) shouldBe mapOf(
                        "Base-Header" to "base-value",
                        "Shared-Header" to "custom-shared",
                        "X-Custom" to "custom-value"
                    )
                }
            }

            context("With empty custom headers") {
                should("return the base headers unchanged") {
                    TranslationConfig.processTranslationMetadata(
                        RoomMetadata.Metadata.Translation(httpHeaders = emptyMap()),
                        baseHeaders
                    ) shouldBe baseHeaders
                }
            }
        }
    }
}
