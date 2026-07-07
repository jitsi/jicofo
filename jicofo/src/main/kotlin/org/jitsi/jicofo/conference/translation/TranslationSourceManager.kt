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

import org.jitsi.jicofo.conference.source.Source
import org.jitsi.utils.MediaType
import java.util.concurrent.ThreadLocalRandom

/**
 * The result of recomputing the set of synthetic translation sources.
 *
 * @param sourcesBySender for each sender endpoint id, the set of synthetic audio sources (one per requested language).
 * @param requestNames the names of the synthetic sources the bridge requests from the translator (each encodes the
 * language as a "<baseName>.<lang>" suffix).
 * @param exportNames the names of the (real) sources the bridge exports to the translator, i.e. the senders' base
 * audio sources to be translated.
 */
data class TranslationSources(
    val sourcesBySender: Map<String, Set<Source>>,
    val requestNames: Set<String>,
    val exportNames: Set<String>
) {
    val isEmpty: Boolean
        get() = sourcesBySender.isEmpty()
}

/**
 * Computes and tracks the synthetic audio [Source]s used for live translation.
 *
 * Given the aggregated request map (sender endpoint id -> requested language codes), it produces one synthetic audio
 * source per `<sender, language>`, named `<baseName>.<language>` where `baseName` is the sender's first audio source
 * name. SSRCs are minted to avoid collisions with the conference and are kept stable across updates: an existing
 * `<sender, language>` keeps its SSRC, so the map is only ever extended or pruned, never churned.
 *
 * This class holds the allocation state but performs no side effects (it does not touch the conference source map or
 * signal anything). The caller applies the returned [TranslationSources] to the conference.
 */
class TranslationSourceManager(
    /** Mints a candidate SSRC. Overridable for tests; defaults to a random 32-bit value. */
    private val ssrcGenerator: () -> Long = { ThreadLocalRandom.current().nextLong(1, MAX_SSRC) }
) {
    /** sender endpoint id -> (language -> synthetic source). */
    private val allocated = mutableMapOf<String, MutableMap<String, Source>>()

    /**
     * Recompute the synthetic sources for [requests].
     *
     * @param requests sender endpoint id -> requested language codes.
     * @param baseNameResolver returns the base (first audio) source name for a sender, or null when the sender is not
     * present or has no audio source — such entries are skipped (and any existing synthetic sources for them dropped).
     * @param ssrcInUse returns true when an SSRC is already used in the conference (by non-synthetic sources). Newly
     * minted SSRCs avoid these as well as any already-allocated synthetic SSRC.
     */
    @Synchronized
    fun update(
        requests: Map<String, List<String>>,
        baseNameResolver: (String) -> String?,
        ssrcInUse: (Long) -> Boolean
    ): TranslationSources {
        val next = mutableMapOf<String, MutableMap<String, Source>>()

        for ((sender, languages) in requests) {
            if (languages.isEmpty()) continue
            val baseName = baseNameResolver(sender) ?: continue
            val existingForSender = allocated[sender]
            val nextForSender = mutableMapOf<String, Source>()

            for (language in languages.toSet()) {
                val existing = existingForSender?.get(language)
                nextForSender[language] = if (existing != null) {
                    // Keep the SSRC stable; refresh the name in case the base source name changed.
                    val expectedName = nameFor(baseName, language)
                    if (existing.name == expectedName) existing else existing.copy(name = expectedName)
                } else {
                    Source(
                        ssrc = mintSsrc(ssrcInUse, next),
                        mediaType = MediaType.AUDIO,
                        name = nameFor(baseName, language),
                        synthetic = true
                    )
                }
            }
            next[sender] = nextForSender
        }

        allocated.clear()
        allocated.putAll(next)

        val sourcesBySender = next.mapValues { (_, byLang) -> byLang.values.toSet() }
        val requestNames = next.values.flatMap { it.values }.map { it.name!! }.toSet()
        val exportNames = next.keys.mapNotNull { baseNameResolver(it) }.toSet()

        return TranslationSources(sourcesBySender, requestNames, exportNames)
    }

    /** All synthetic SSRCs currently allocated. */
    @Synchronized
    fun allocatedSsrcs(): Set<Long> = allocated.values.flatMap { it.values }.map { it.ssrc }.toSet()

    private fun mintSsrc(ssrcInUse: (Long) -> Boolean, next: Map<String, Map<String, Source>>): Long {
        val usedByNext = next.values.flatMap { it.values }.map { it.ssrc }.toSet()
        val usedByAllocated = allocated.values.flatMap { it.values }.map { it.ssrc }.toSet()
        repeat(MAX_MINT_ATTEMPTS) {
            val candidate = ssrcGenerator() and 0xFFFFFFFFL
            if (candidate != 0L && !ssrcInUse(candidate) && candidate !in usedByNext && candidate !in usedByAllocated) {
                return candidate
            }
        }
        throw IllegalStateException("Failed to mint a non-conflicting SSRC after $MAX_MINT_ATTEMPTS attempts")
    }

    companion object {
        private const val MAX_SSRC = 0x1_0000_0000L
        private const val MAX_MINT_ATTEMPTS = 1000

        /** The synthetic source name for a base source name and a language: "<baseName>.<language>". */
        fun nameFor(baseName: String, language: String) = "$baseName.$language"
    }
}
