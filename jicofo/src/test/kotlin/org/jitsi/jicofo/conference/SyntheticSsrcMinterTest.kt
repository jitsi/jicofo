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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.utils.MediaType

class SyntheticSsrcMinterTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val conferenceSources = ValidatingConferenceSourceMap(20, 20)

    /** The candidates that the generator returns, in order. It repeats the last candidate when they run out. */
    private fun minter(vararg candidates: Long): SyntheticSsrcMinter {
        var next = 0
        return SyntheticSsrcMinter(conferenceSources) { candidates[minOf(next++, candidates.size - 1)] }
    }

    init {
        context("Minting") {
            should("return the candidates of the generator in order") {
                val minter = minter(100, 101)
                minter.mint() shouldBe 100L
                minter.mint() shouldBe 101L
            }
            should("skip an SSRC of a source in the conference, synthetic or not") {
                conferenceSources.add("aaaaaaaa", EndpointSourceSet(Source(100, MediaType.AUDIO, name = "aaaaaaaa-a0")))
                conferenceSources.add(
                    "bbbbbbbb",
                    EndpointSourceSet(Source(101, MediaType.AUDIO, name = "bbbbbbbb-a0.en", synthetic = true))
                )
                minter(100, 101, 102).mint() shouldBe 102L
            }
            should("skip a source that joined the conference after the minter was created") {
                val minter = minter(100, 101)
                conferenceSources.add("aaaaaaaa", EndpointSourceSet(Source(100, MediaType.AUDIO, name = "aaaaaaaa-a0")))
                minter.mint() shouldBe 101L
            }
            should("skip an SSRC that it minted, though no source in the conference has it") {
                val minter = minter(100, 100, 101)
                minter.mint() shouldBe 100L
                minter.mint() shouldBe 101L
            }
            should("skip zero and use only the low 32 bits of a candidate") {
                minter(0, 0x1_0000_0000L, 0x1_0000_0007L).mint() shouldBe 7L
            }
            should("fail when the generator returns no free SSRC") {
                val minter = minter(100)
                minter.mint() shouldBe 100L
                shouldThrow<IllegalStateException> { minter.mint() }
            }
        }
        context("With the default generator") {
            should("mint distinct SSRCs in the 32-bit range") {
                val minter = SyntheticSsrcMinter(conferenceSources)
                val ssrcs = (1..100).map { minter.mint() }
                ssrcs.toSet().size shouldBe 100
                ssrcs.all { it in 1..0xFFFF_FFFFL } shouldBe true
            }
        }
    }
}
