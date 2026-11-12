/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo.conference

import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Mints the SSRCs of the synthetic audio sources of a conference: the translation sources and the sources of the voice
 * agents. A conference has a single instance, which the managers of both kinds of source share.
 *
 * A new SSRC is not used by a source in the conference source map, and this instance never minted it. An SSRC is
 * not given out a second time after its source is gone, because a receiver can still have state for it. The sources of
 * the agents are not in the conference source map, so the record of this instance is also what keeps a translation
 * source and the source of an agent apart.
 */
class SyntheticSsrcMinter @JvmOverloads constructor(
    /** Read to avoid the SSRCs of the sources in the conference; never modified. */
    private val conferenceSources: ValidatingConferenceSourceMap,
    /** Returns a candidate SSRC. Overridable for tests; defaults to a random 32-bit value. */
    private val generator: () -> Long = { ThreadLocalRandom.current().nextLong(1, MAX_SSRC) }
) {
    /** Every SSRC that this instance minted. It grows by a single entry for each synthetic source and each re-mint. */
    private val minted = mutableSetOf<Long>()

    /** @throws IllegalStateException if no free SSRC is found in [MAX_MINT_ATTEMPTS] attempts. */
    @Synchronized
    fun mint(): Long {
        repeat(MAX_MINT_ATTEMPTS) {
            val candidate = generator() and 0xFFFF_FFFFL
            if (candidate != 0L && candidate !in minted && !usedInConference(candidate)) {
                minted.add(candidate)
                return candidate
            }
        }
        throw IllegalStateException("Failed to mint a non-conflicting SSRC after $MAX_MINT_ATTEMPTS attempts")
    }

    private fun usedInConference(ssrc: Long): Boolean =
        conferenceSources.unmodifiable().values.any { set -> set.sources.any { it.ssrc == ssrc } }

    companion object {
        private const val MAX_SSRC = 0x1_0000_0000L
        private const val MAX_MINT_ATTEMPTS = 1000
    }
}
