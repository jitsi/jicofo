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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.utils.MediaType.AUDIO
import org.jitsi.utils.MediaType.VIDEO
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

@Suppress("NAME_SHADOWING")
class EndpointSourceSetTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        context("From XML") {
            // Assume serializing works correctly -- it's tested below.
            val contents = sourceSet.toJingle(JID_1)
            val parsed = EndpointSourceSet.fromJingle(contents)
            // Use the provided ownerJid, not the one encoded in XML.
            parsed shouldBe sourceSet
        }
        context("To XML") {
            val contents = sourceSet.toJingle()
            contents.size shouldBe 2

            val videoContent = contents.find { it.name == "video" }!!
            val videoRtpDesc = videoContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
            val videoSources = videoRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                .map { Source(VIDEO, it) }.toSet()
            videoSources shouldBe sourceSet.sources.filter { it.mediaType == VIDEO }.toSet()

            val sourceGroupPacketExtensions =
                videoRtpDesc.getChildExtensionsOfType(SourceGroupPacketExtension::class.java)
            sourceGroupPacketExtensions.map { SsrcGroup.fromPacketExtension(it) }.toSet() shouldBe
                sourceSet.ssrcGroups

            val audioContent = contents.find { it.name == "audio" }!!
            val audioRtpDesc = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
            val audioSources = audioRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                .map { Source(AUDIO, it) }.toSet()
            audioSources shouldBe sourceSet.sources.filter { it.mediaType == AUDIO }.toSet()
        }
        context("Strip simulcast") {
            val s8 = Source(8, VIDEO)

            context("Without RTX") {
                EndpointSourceSet(
                    setOf(s1, s2, s3, s7, s8),
                    setOf(sim)
                ).stripSimulcast shouldBe EndpointSourceSet(setOf(s1, s7, s8))
            }
            context("With multiple SIM groups") {
                EndpointSourceSet(
                    sourceSet.sources + s8,
                    setOf(
                        sim,
                        SsrcGroup(SsrcGroupSemantics.Sim, listOf(4, 5, 6))
                    )
                ).stripSimulcast shouldBe EndpointSourceSet(setOf(s1, s4, s7, s8))
            }
            context("With RTX") {
                EndpointSourceSet(
                    sourceSet.sources + s8,
                    sourceSet.ssrcGroups
                ).stripSimulcast shouldBe EndpointSourceSet(
                    setOf(s1, s4, s7, s8),
                    setOf(fid1)
                )
            }
            context("Compact JSON") {
                // See the documentation of [EndpointSourceSet.compactJson] for the expected JSON format.
                val json = JSONParser().parse(sourceSet.compactJson)
                json.shouldBeInstanceOf<JSONArray>()

                val videoSourceList = json[0]
                videoSourceList.shouldBeInstanceOf<JSONArray>()
                videoSourceList.map { (it as JSONObject).toMap() }.toSet() shouldBe setOf(
                    mapOf("s" to 1L),
                    mapOf("s" to 2L),
                    mapOf("s" to 3L),
                    mapOf("s" to 4L),
                    mapOf("s" to 5L),
                    mapOf("s" to 6L)
                )

                val videoSsrcGroups = json[1]
                videoSsrcGroups.shouldBeInstanceOf<JSONArray>()
                videoSsrcGroups.map { (it as JSONArray).toList() }.toSet() shouldBe setOf(
                    listOf("s", 1, 2, 3),
                    listOf("f", 1, 4),
                    listOf("f", 2, 5),
                    listOf("f", 3, 6)
                )

                val audioSourceList = json[2]
                audioSourceList.shouldBeInstanceOf<JSONArray>()
                audioSourceList.map { (it as JSONObject).toMap() }.toSet() shouldBe setOf(
                    mapOf("s" to 7L)
                )

                // No audio source groups encoded.
                json.size shouldBe 3
            }
        }
    }
}

const val JID_1 = "jid1"
const val JID_2 = "jid2"
val s1 = Source(1, VIDEO)
val s2 = Source(2, VIDEO)
val s3 = Source(3, VIDEO)
val s4 = Source(4, VIDEO)
val s5 = Source(5, VIDEO)
val s6 = Source(6, VIDEO)
val s7 = Source(7, AUDIO)
val sim = SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3))
val fid1 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 4))
val fid2 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(2, 5))
val fid3 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 6))
val sourceSet = EndpointSourceSet(
    setOf(s1, s2, s3, s4, s5, s6, s7),
    setOf(sim, fid1, fid2, fid3)
)

val e2s1 = Source(11, VIDEO)
val e2s2 = Source(12, VIDEO)
val e2s3 = Source(13, VIDEO)
val e2s4 = Source(14, VIDEO)
val e2s5 = Source(15, VIDEO)
val e2s6 = Source(16, VIDEO)
val e2s7 = Source(17, AUDIO)
val e2sim = SsrcGroup(SsrcGroupSemantics.Sim, listOf(11, 12, 13))
val e2fid1 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(11, 14))
val e2fid2 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(12, 15))
val e2fid3 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(13, 16))
val e2sourceSet = EndpointSourceSet(
    setOf(e2s1, e2s2, e2s3, e2s4, e2s5, e2s6, e2s7),
    setOf(e2sim, e2fid1, e2fid2, e2fid3)
)
