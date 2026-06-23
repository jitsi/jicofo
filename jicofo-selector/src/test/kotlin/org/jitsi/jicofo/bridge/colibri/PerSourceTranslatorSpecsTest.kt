/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.jicofo.bridge.colibri

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI

class PerSourceTranslatorSpecsTest : ShouldSpec() {
    private val url = URI("wss://example.com/t")

    init {
        context("A sender within the per-connect limit") {
            val request = TranslationRequest("aaaaaaaa", "aaaaaaaa-a0", listOf("aaaaaaaa-a0.en", "aaaaaaaa-a0.es"))
            val specs = perSourceTranslatorSpecs(request, url, maxLanguagesPerConnect = 5, httpHeaders = emptyMap())

            should("produce a single connect with all languages") {
                specs.size shouldBe 1
                specs[0].id shouldBe "translator-aaaaaaaa-a0-0"
                specs[0].type shouldBe Connect.Types.TRANSLATOR
                specs[0].exports shouldContainExactly listOf("aaaaaaaa-a0")
                specs[0].requests shouldContainExactly listOf("aaaaaaaa-a0.en", "aaaaaaaa-a0.es")
            }
        }

        context("A sender exceeding the per-connect limit") {
            val languages = (1..7).map { "aaaaaaaa-a0.l$it" }
            val request = TranslationRequest("aaaaaaaa", "aaaaaaaa-a0", languages)
            val specs = perSourceTranslatorSpecs(request, url, maxLanguagesPerConnect = 3, httpHeaders = emptyMap())

            should("split into ceil(n / max) connects with stable ids") {
                specs.map { it.id } shouldContainExactly listOf(
                    "translator-aaaaaaaa-a0-0",
                    "translator-aaaaaaaa-a0-1",
                    "translator-aaaaaaaa-a0-2"
                )
            }
            should("chunk the languages, each connect exporting the same source") {
                specs[0].requests shouldContainExactly languages.subList(0, 3)
                specs[1].requests shouldContainExactly languages.subList(3, 6)
                specs[2].requests shouldContainExactly languages.subList(6, 7)
                specs.forEach { it.exports shouldContainExactly listOf("aaaaaaaa-a0") }
            }
        }

        context("A ping passed through") {
            val request = TranslationRequest("aaaaaaaa", "aaaaaaaa-a0", listOf("aaaaaaaa-a0.en", "aaaaaaaa-a0.fr"))
            val specs = perSourceTranslatorSpecs(
                request,
                url,
                maxLanguagesPerConnect = 1,
                httpHeaders = emptyMap(),
                ping = ConnectSpec.Ping(10000, 3000)
            )

            should("set the ping on every connect") {
                specs.size shouldBe 2
                specs.forEach { it.ping shouldBe ConnectSpec.Ping(10000, 3000) }
            }
        }

        context("No ping by default") {
            should("leave ping null") {
                perSourceTranslatorSpecs(
                    TranslationRequest("aaaaaaaa", "aaaaaaaa-a0", listOf("aaaaaaaa-a0.en")),
                    url,
                    maxLanguagesPerConnect = 5,
                    httpHeaders = emptyMap()
                ).single().ping shouldBe null
            }
        }

        context("A sender with no requested languages") {
            should("produce no connects") {
                perSourceTranslatorSpecs(
                    TranslationRequest("aaaaaaaa", "aaaaaaaa-a0", emptyList()),
                    url,
                    maxLanguagesPerConnect = 5,
                    httpHeaders = emptyMap()
                ) shouldBe emptyList()
            }
        }
    }
}
