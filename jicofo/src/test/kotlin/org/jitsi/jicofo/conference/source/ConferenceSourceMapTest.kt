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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import shouldBeValidJson
import java.lang.UnsupportedOperationException

@Suppress("NAME_SHADOWING")
@SuppressFBWarnings(
    value = ["RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"],
    justification = "False positives."
)
class ConferenceSourceMapTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val s7 = Source(7, MediaType.AUDIO)
        val endpoint1SourceSet = EndpointSourceSet(
            setOf(s1, s2, s7),
            setOf(
                SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
            )
        )

        val endpoint1AdditionalSourceSet = EndpointSourceSet(
            setOf(
                // The duplicates should be removed
                s1,
                s2,
                s3,
                s4,
                s7
            ),
            setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 4)))
        )
        val endpoint1CombinedSourceSet = endpoint1SourceSet + endpoint1AdditionalSourceSet

        val endpoint2SourceSet = EndpointSourceSet(
            setOf(
                Source(101, MediaType.VIDEO),
                Source(102, MediaType.VIDEO),
                Source(103, MediaType.AUDIO)
            ),
            setOf(
                SsrcGroup(SsrcGroupSemantics.Fid, listOf(101, 102))
            )
        )

        context("Constructors") {
            context("Default") {
                val conferenceSourceMap = ConferenceSourceMap(mutableMapOf(JID_1 to endpoint1SourceSet))
                conferenceSourceMap.size shouldBe 1
                conferenceSourceMap[JID_1] shouldBe endpoint1SourceSet
            }
            context("With a single EndpointSourceSet") {
                val conferenceSourceMap = ConferenceSourceMap(JID_1 to endpoint1SourceSet)
                conferenceSourceMap.size shouldBe 1
                conferenceSourceMap[JID_1] shouldBe endpoint1SourceSet
            }
            context("With multiple EndpointSourceSets") {
                val conferenceSourceMap = ConferenceSourceMap(
                    JID_1 to endpoint1SourceSet,
                    JID_2 to endpoint2SourceSet
                )
                conferenceSourceMap.size shouldBe 2
                conferenceSourceMap[JID_1] shouldBe endpoint1SourceSet
                conferenceSourceMap[JID_2] shouldBe endpoint2SourceSet
            }
        }
        context("Add") {
            val conferenceSourceMap = ConferenceSourceMap(JID_1 to endpoint1SourceSet)

            context("Without overlap in endpoints") {
                conferenceSourceMap.add(JID_2, endpoint2SourceSet)
                conferenceSourceMap.size shouldBe 2
                conferenceSourceMap[JID_1] shouldBe endpoint1SourceSet
                conferenceSourceMap[JID_2] shouldBe endpoint2SourceSet
            }
            context("With overlap in endpoints") {
                conferenceSourceMap.add(
                    ConferenceSourceMap(
                        JID_1 to endpoint1AdditionalSourceSet,
                        JID_2 to endpoint2SourceSet
                    )
                )

                conferenceSourceMap.size shouldBe 2
                conferenceSourceMap[JID_1] shouldBe endpoint1CombinedSourceSet
                conferenceSourceMap[JID_2] shouldBe endpoint2SourceSet
            }
        }
        context("Remove") {
            val conferenceSourceMap = ConferenceSourceMap(
                JID_1 to endpoint1CombinedSourceSet,
                JID_2 to endpoint2SourceSet
            )
            context("All of an endpoint's sources") {
                conferenceSourceMap.remove(ConferenceSourceMap(JID_2 to endpoint2SourceSet))
                conferenceSourceMap.size shouldBe 1
                conferenceSourceMap[JID_1] shouldBe endpoint1CombinedSourceSet
                conferenceSourceMap[JID_2] shouldBe null
            }
            context("Some of an endpoint's sources") {
                conferenceSourceMap.remove(ConferenceSourceMap(JID_1 to endpoint1SourceSet))
                conferenceSourceMap.size shouldBe 2
                conferenceSourceMap[JID_1] shouldBe EndpointSourceSet(
                    setOf(s3, s4),
                    setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 4)))
                )
                conferenceSourceMap[JID_2] shouldBe endpoint2SourceSet
            }
            context("Nonexistent endpoint sources") {
                conferenceSourceMap.remove(
                    ConferenceSourceMap(
                        JID_1 to EndpointSourceSet(
                            setOf(Source(12345, MediaType.VIDEO)),
                            setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(12345)))
                        )
                    )
                )
                conferenceSourceMap.size shouldBe 2
                conferenceSourceMap[JID_1] shouldBe endpoint1CombinedSourceSet
                conferenceSourceMap[JID_2] shouldBe endpoint2SourceSet
            }
            context("Nonexistent endpoint") {
                conferenceSourceMap.remove(ConferenceSourceMap("differentJid" to endpoint1CombinedSourceSet))
                conferenceSourceMap.size shouldBe 2
                conferenceSourceMap[JID_1] shouldBe endpoint1CombinedSourceSet
                conferenceSourceMap[JID_2] shouldBe endpoint2SourceSet
            }
        }
        context("Operator plus") {
            val a = ConferenceSourceMap(JID_1 to endpoint1SourceSet)
            val b = ConferenceSourceMap(JID_2 to endpoint2SourceSet)
            (a + b) shouldBe ConferenceSourceMap(JID_1 to endpoint1SourceSet, JID_2 to endpoint2SourceSet)
        }
        context("Operator minus") {
            val a = ConferenceSourceMap(JID_1 to endpoint1SourceSet, JID_2 to endpoint2SourceSet)
            val b = ConferenceSourceMap(JID_1 to endpoint1SourceSet)
            (a - b) shouldBe ConferenceSourceMap(JID_2 to endpoint2SourceSet)
        }
        context("To Jingle") {
            val conferenceSourceMap = ConferenceSourceMap(JID_1 to endpoint1SourceSet)
            val contents = conferenceSourceMap.toJingle()

            contents.size shouldBe 2

            val videoContent = contents.find { it.name == "video" }!!
            val videoRtpDesc = videoContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
            val videoSources =
                videoRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
            videoSources.map { Source(MediaType.VIDEO, it) }.toSet() shouldBe setOf(
                Source(1, MediaType.VIDEO),
                Source(2, MediaType.VIDEO)
            )
            videoSources.forEach {
                it.getFirstChildOfType(SSRCInfoPacketExtension::class.java).owner shouldBe JID_1
            }

            val sourceGroupPacketExtension =
                videoRtpDesc.getFirstChildOfType(SourceGroupPacketExtension::class.java)!!
            SsrcGroup.fromPacketExtension(sourceGroupPacketExtension) shouldBe
                SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))

            val audioContent = contents.find { it.name == "audio" }!!
            val audioRtpDesc = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
            val audioSources = audioRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
            audioSources.map { Source(MediaType.AUDIO, it) } shouldBe listOf(s7)
            audioSources.forEach {
                it.getFirstChildOfType(SSRCInfoPacketExtension::class.java).owner shouldBe JID_1
            }
        }
        context("unmodifiable") {
            val conferenceSourceMap = ConferenceSourceMap(
                JID_1 to endpoint1SourceSet,
                JID_2 to endpoint2SourceSet
            )

            val unmodifiable = conferenceSourceMap.unmodifiable
            unmodifiable[JID_1] shouldBe endpoint1SourceSet

            conferenceSourceMap.add(JID_1, endpoint1AdditionalSourceSet)
            conferenceSourceMap[JID_1] shouldBe endpoint1CombinedSourceSet
            unmodifiable[JID_1] shouldBe endpoint1CombinedSourceSet

            shouldThrow<UnsupportedOperationException> {
                unmodifiable.add(ConferenceSourceMap())
            }
            shouldThrow<UnsupportedOperationException> {
                unmodifiable.remove(ConferenceSourceMap())
            }
        }
        context("Strip") {
            val s7 = Source(7, MediaType.AUDIO)
            val sourceSet = EndpointSourceSet(
                setOf(s1, s2, s3, s4, s5, s6, s7),
                setOf(sim, fid1, fid2, fid3)
            )
            val conferenceSourceMap = ConferenceSourceMap(JID_1 to sourceSet, JID_2 to e2sourceSet)

            // Assume EndpointSourceSet.stripSimulcast works correctly, tested above.
            context("Simulcast") {
                conferenceSourceMap.stripSimulcast()
                conferenceSourceMap[JID_1] shouldBe sourceSet.stripSimulcast
                conferenceSourceMap[JID_2] shouldBe e2sourceSet.stripSimulcast
            }
        }
        context("Compact JSON") {
            val endpointId1 = "bebebebe"
            val endpointId2 = "dadadada"
            val jvb = "jvb"
            val jvbEndpointSourceSet = EndpointSourceSet(Source(12345, MediaType.VIDEO))

            val conferenceSourceMap = ConferenceSourceMap(
                endpointId1 to sourceSet,
                endpointId2 to e2sourceSet,
                jvb to jvbEndpointSourceSet
            )
            val jsonString = conferenceSourceMap.compactJson()
            val json = JSONParser().parse(jsonString)
            json.shouldBeInstanceOf<JSONObject>()
            json.size shouldBe 3
            json.keys shouldBe setOf(endpointId1, endpointId2, "jvb")
        }
        context("Debug json") {
            val conferenceSourceMap = ConferenceSourceMap(
                "jid1" to endpoint1CombinedSourceSet,
                "jid2" to endpoint2SourceSet
            )
            conferenceSourceMap.toJson().shouldBeValidJson()
        }
    }
}
