/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
package org.jitsi.jicofo.conference.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension

class SsrcGroupTest : ShouldSpec() {
    init {
        context("SsrcGroupSemantics") {
            context("Parsing") {
                SsrcGroupSemantics.fromString("sim") shouldBe SsrcGroupSemantics.Sim
                SsrcGroupSemantics.fromString("SIM") shouldBe SsrcGroupSemantics.Sim
                SsrcGroupSemantics.fromString("sIM") shouldBe SsrcGroupSemantics.Sim
                SsrcGroupSemantics.fromString("fiD") shouldBe SsrcGroupSemantics.Fid

                shouldThrow<NoSuchElementException> {
                    SsrcGroupSemantics.fromString("invalid")
                }
            }
            context("To string") {
                SsrcGroupSemantics.Sim.toString() shouldBe "SIM"
                SsrcGroupSemantics.Fid.toString() shouldBe "FID"
            }
        }
        context("From XML") {
            val packetExtension = SourceGroupPacketExtension().apply {
                semantics = "sim"
                addSources(
                    listOf(
                        SourcePacketExtension().apply { ssrc = 1 },
                        SourcePacketExtension().apply { ssrc = 2 },
                        SourcePacketExtension().apply { ssrc = 3 }
                    )
                )
            }

            val ssrcGroup = SsrcGroup.fromPacketExtension(packetExtension)
            ssrcGroup shouldBe SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3))
        }
        context("To XML") {
            val ssrcGroup = SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3))
            val packetExtension = ssrcGroup.toPacketExtension()
            packetExtension.semantics shouldBe "SIM"
            packetExtension.sources.size shouldBe 3
            packetExtension.sources.map { Source(MediaType.VIDEO, it) }.toList() shouldBe listOf(
                Source(1, MediaType.VIDEO),
                Source(2, MediaType.VIDEO),
                Source(3, MediaType.VIDEO)
            )
        }
        context("Compact JSON") {
            SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3)).compactJson shouldBe """
                ["s",1,2,3]
            """.trimIndent()
        }
    }
}
