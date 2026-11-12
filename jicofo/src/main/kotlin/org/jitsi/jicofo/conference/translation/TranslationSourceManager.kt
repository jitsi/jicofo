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
 * name. SSRCs come from the conference's [org.jitsi.jicofo.conference.SyntheticSsrcMinter] and are kept stable across
 * updates: an existing `<sender, language>` keeps its SSRC, so the map is only ever extended or pruned, never
 * churned. The exception is [remint], for when another bridge starts to inject the sources.
 *
 * This class holds the allocation state. It does not touch the conference source map and signals nothing: the caller
 * applies the returned [TranslationSources] to the conference.
 */
class TranslationSourceManager(
    /** Returns a new SSRC that is free in the conference (see [org.jitsi.jicofo.conference.SyntheticSsrcMinter]). */
    private val mintSsrc: () -> Long
) {
    /** sender endpoint id -> (language -> synthetic source). */
    private val allocated = mutableMapOf<String, MutableMap<String, Source>>()

    /**
     * Recompute the synthetic sources for [requests].
     *
     * @param requests sender endpoint id -> requested language codes.
     * @param baseNameResolver returns the base (first audio) source name for a sender, or null when the sender is not
     * present or has no audio source — such entries are skipped (and any existing synthetic sources for them dropped).
     */
    @Synchronized
    fun update(requests: Map<String, List<String>>, baseNameResolver: (String) -> String?): TranslationSources {
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
                        ssrc = mintSsrc(),
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

    /**
     * Give every allocated source a new SSRC under the same name. The caller does this when another bridge starts to
     * inject the sources: that bridge can not continue the RTP sequence numbers and timestamps of the old stream, so
     * receivers must see a new stream and not a stream that goes back. The next [update] returns the new sources.
     */
    @Synchronized
    fun remint() = allocated.values.forEach { remint(it) }

    /**
     * Give the allocated sources of [sender] new SSRCs, as [remint] does for all sources.
     *
     * @return whether [sender] had any allocated source.
     */
    @Synchronized
    fun remint(sender: String): Boolean {
        val byLanguage = allocated[sender]?.takeIf { it.isNotEmpty() } ?: return false
        remint(byLanguage)
        return true
    }

    private fun remint(byLanguage: MutableMap<String, Source>) =
        byLanguage.replaceAll { _, source -> source.copy(ssrc = mintSsrc()) }

    /** All synthetic SSRCs currently allocated. Tests read it. */
    @Synchronized
    fun allocatedSsrcs(): Set<Long> = allocated.values.flatMap { it.values }.map { it.ssrc }.toSet()

    companion object {
        /** The synthetic source name for a base source name and a language: "<baseName>.<language>". */
        fun nameFor(baseName: String, language: String) = "$baseName.$language"
    }
}
