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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.AddOrRemove.Add
import org.jitsi.jicofo.conference.AddOrRemove.Remove
import org.jitsi.utils.MediaType.AUDIO
import org.jxmpp.jid.impl.JidCreate

class SourceAddRemoveQueueTest : ShouldSpec() {
    init {
        val jid1 = JidCreate.from("jid1")
        val s1 = ConferenceSourceMap(jid1 to EndpointSourceSet(Source(1, AUDIO))).unmodifiable

        val jid2 = JidCreate.from("jid2")
        val s2 = ConferenceSourceMap(jid2 to EndpointSourceSet(Source(2, AUDIO))).unmodifiable
        val s2new = ConferenceSourceMap(
            jid2 to EndpointSourceSet(
                setOf(
                    Source(2, AUDIO),
                    Source(222, AUDIO)
                )
            )
        ).unmodifiable

        val jid3 = JidCreate.from("jid3")
        val s3 = ConferenceSourceMap(jid3 to EndpointSourceSet(Source(3, AUDIO))).unmodifiable

        context("Queueing remote sources") {
            val queue = SourceAddRemoveQueue()

            queue.sourceAdd(s1)
            queue.get().let {
                it.size shouldBe 1
                it[0].action shouldBe Add
                it[0].sources.toMap() shouldBe s1.toMap()
            }

            // Consecutive source-adds should be merged.
            queue.sourceAdd(s2)
            queue.get().let {
                it.size shouldBe 1
                it[0].action shouldBe Add
                it[0].sources.toMap() shouldBe s1.copy().apply { add(s2) }.toMap()
            }

            // Consecutive source-adds should be merged.
            queue.sourceAdd(s2new)
            queue.get().let {
                it.size shouldBe 1
                it[0].action shouldBe Add
                it[0].sources.toMap() shouldBe s1.copy().apply { add(s2); add(s2new) }.toMap()
            }

            // A source-remove after a series of source-adds should be a new entry.
            queue.sourceRemove(s2new)
            queue.get().let {
                it.size shouldBe 2
                it[0].action shouldBe Add
                it[0].sources.toMap() shouldBe s1.copy().apply { add(s2); add(s2new) }.toMap()
                it[1].action shouldBe Remove
                it[1].sources.toMap() shouldBe s2new.toMap()
            }

            // A source-add following source-remove should be a new entry.
            queue.sourceAdd(s3)
            queue.get().let {
                it.size shouldBe 3
                it[0].action shouldBe Add
                it[0].sources.toMap() shouldBe s1.copy().apply { add(s2); add(s2new) }.toMap()
                it[1].action shouldBe Remove
                it[1].sources.toMap() shouldBe s2new.toMap()
                it[2].action shouldBe Add
                it[2].sources.toMap() shouldBe s3.toMap()
            }

            // Consecutive source-adds should be merged.
            queue.sourceAdd(s1)
            queue.get().let {
                it.size shouldBe 3
                it[0].action shouldBe Add
                it[0].sources.toMap() shouldBe s1.copy().apply { add(s2); add(s2new) }.toMap()
                it[1].action shouldBe Remove
                it[1].sources.toMap() shouldBe s2new.toMap()
                it[2].action shouldBe Add
                it[2].sources.toMap() shouldBe s3.copy().apply { add(s1) }.toMap()
            }

            queue.clear()
            queue.get() shouldBe emptyList()
        }
    }
}