/*
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
package org.jitsi.jicofo.conference.translation

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jitsi.utils.MediaType

class TranslationSourceManagerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    /** A deterministic SSRC generator returning 100, 101, 102, ... */
    private var nextSsrc = 100L
    private val sequentialGenerator: () -> Long = { nextSsrc++ }

    /** Every sender resolves to "<id>-a0" unless overridden. */
    private val baseName: (String) -> String? = { "$it-a0" }
    private val noneInUse: (Long) -> Boolean = { false }

    init {
        val manager = TranslationSourceManager(sequentialGenerator)

        context("A single sender and language") {
            val result = manager.update(mapOf("aaaaaaaa" to listOf("en")), baseName, noneInUse)
            val sources = result.sourcesBySender["aaaaaaaa"]!!

            should("produce one synthetic audio source named <base>.<lang>") {
                sources.size shouldBe 1
                val source = sources.first()
                source.name shouldBe "aaaaaaaa-a0.en"
                source.mediaType shouldBe MediaType.AUDIO
                source.synthetic shouldBe true
                source.ssrc shouldBe 100L
            }
            should("report the request and export names for the connect") {
                result.requestNames shouldContainExactly setOf("aaaaaaaa-a0.en")
                result.exportNames shouldContainExactly setOf("aaaaaaaa-a0")
            }
        }

        context("Multiple languages for one sender") {
            val result = manager.update(mapOf("aaaaaaaa" to listOf("en", "es")), baseName, noneInUse)
            val sources = result.sourcesBySender["aaaaaaaa"]!!

            should("produce one source per language with distinct ssrcs") {
                sources.map { it.name } shouldContainExactlyInAnyOrder listOf("aaaaaaaa-a0.en", "aaaaaaaa-a0.es")
                sources.map { it.ssrc }.toSet().size shouldBe 2
            }
            should("request both synthetic names") {
                result.requestNames shouldContainExactlyInAnyOrder listOf("aaaaaaaa-a0.en", "aaaaaaaa-a0.es")
            }
        }

        context("Stability across updates") {
            val first = manager.update(mapOf("aaaaaaaa" to listOf("en")), baseName, noneInUse)
            val ssrc = first.sourcesBySender["aaaaaaaa"]!!.first().ssrc

            should("keep the same ssrc when re-applying the same request") {
                val second = manager.update(mapOf("aaaaaaaa" to listOf("en")), baseName, noneInUse)
                second.sourcesBySender["aaaaaaaa"]!!.first().ssrc shouldBe ssrc
            }
            should("keep existing ssrc and mint a fresh one when adding a language") {
                val second = manager.update(mapOf("aaaaaaaa" to listOf("en", "es")), baseName, noneInUse)
                val byName = second.sourcesBySender["aaaaaaaa"]!!.associateBy { it.name }
                byName["aaaaaaaa-a0.en"]!!.ssrc shouldBe ssrc
                byName["aaaaaaaa-a0.es"]!!.ssrc shouldBe 101L
            }
            should("drop a language that is no longer requested, keeping the other") {
                manager.update(mapOf("aaaaaaaa" to listOf("en", "es")), baseName, noneInUse)
                val third = manager.update(mapOf("aaaaaaaa" to listOf("es")), baseName, noneInUse)
                val sources = third.sourcesBySender["aaaaaaaa"]!!
                sources.size shouldBe 1
                sources.first().name shouldBe "aaaaaaaa-a0.es"
            }
        }

        context("SSRC collision avoidance") {
            // 100 is already used by the conference; the manager must skip it and pick 101.
            val result = manager.update(mapOf("aaaaaaaa" to listOf("en")), baseName, ssrcInUse = { it == 100L })
            should("mint an ssrc that does not collide with the conference") {
                result.sourcesBySender["aaaaaaaa"]!!.first().ssrc shouldBe 101L
            }
        }

        context("Sender not present or without an audio source") {
            val result = manager.update(
                mapOf("aaaaaaaa" to listOf("en"), "bbbbbbbb" to listOf("fr")),
                baseNameResolver = { if (it == "aaaaaaaa") "aaaaaaaa-a0" else null },
                noneInUse
            )
            should("skip the unresolved sender and keep the resolved one") {
                result.sourcesBySender.keys shouldContainExactly setOf("aaaaaaaa")
                result.requestNames shouldContainExactly setOf("aaaaaaaa-a0.en")
            }
        }

        context("Sender's audio source appears on a later update") {
            var hasAudio = false
            val lateResolver: (String) -> String? = { if (it == "aaaaaaaa" && hasAudio) "aaaaaaaa-a0" else null }

            // Request arrives before the sender has an audio source: skipped, no synthetic source.
            val first = manager.update(mapOf("aaaaaaaa" to listOf("es")), lateResolver, noneInUse)

            should("skip the sender while it has no audio source") {
                first.isEmpty shouldBe true
            }
            should("create the synthetic source once the audio source appears and the same request is re-applied") {
                hasAudio = true
                val second = manager.update(mapOf("aaaaaaaa" to listOf("es")), lateResolver, noneInUse)

                second.sourcesBySender["aaaaaaaa"]!!.map { it.name } shouldContainExactly listOf("aaaaaaaa-a0.es")
            }
        }

        context("Empty requests") {
            manager.update(mapOf("aaaaaaaa" to listOf("en")), baseName, noneInUse)
            val result = manager.update(emptyMap(), baseName, noneInUse)
            should("clear all synthetic sources") {
                result.isEmpty shouldBe true
                manager.allocatedSsrcs().isEmpty() shouldBe true
            }
        }
    }
}
