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
import org.jitsi.utils.MediaType.AUDIO
import org.jitsi.utils.MediaType.VIDEO
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate
import java.lang.UnsupportedOperationException

@Suppress("NAME_SHADOWING")
@SuppressFBWarnings(
    value = ["RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"],
    justification = "False positives."
)
class SourcesTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val jid1 = JidCreate.from("jid1")
        val jid2 = JidCreate.from("jid2")
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

        context("EndpointSourceSet") {
            context("From XML") {
                // Assume serializing works correctly -- it's tested below.
                val contents = sourceSet.toJingle(jid1)
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
                val s8 = Source(8, VIDEO, injected = true)

                context("Without RTX") {
                    context("stripSimulcast") {
                        EndpointSourceSet(
                            setOf(s1, s2, s3, s7, s8),
                            setOf(sim)
                        ).stripSimulcast() shouldBe EndpointSourceSet(setOf(s1, s7, s8))
                    }
                    context("stripSimulcast and remove injected") {
                        EndpointSourceSet(
                            setOf(s1, s2, s3, s7, s8),
                            setOf(sim)
                        ).stripSimulcast(stripInjected = true) shouldBe EndpointSourceSet(setOf(s1, s7))
                    }
                }
                context("With multiple SIM groups") {
                    context("stripSimulcast") {
                        EndpointSourceSet(
                            sourceSet.sources + s8,
                            setOf(
                                sim,
                                SsrcGroup(SsrcGroupSemantics.Sim, listOf(4, 5, 6))
                            )
                        ).stripSimulcast() shouldBe EndpointSourceSet(setOf(s1, s4, s7, s8))
                    }
                    context("stripSimulcast and remove injected") {
                        EndpointSourceSet(
                            sourceSet.sources + s8,
                            setOf(
                                sim,
                                SsrcGroup(SsrcGroupSemantics.Sim, listOf(4, 5, 6))
                            )
                        ).stripSimulcast(stripInjected = true) shouldBe EndpointSourceSet(setOf(s1, s4, s7))
                    }
                }
                context("With RTX") {
                    context("stripSimulcast") {
                        EndpointSourceSet(
                            sourceSet.sources + s8,
                            sourceSet.ssrcGroups
                        ).stripSimulcast() shouldBe EndpointSourceSet(
                            setOf(s1, s4, s7, s8),
                            setOf(fid1)
                        )
                    }
                    context("stripSimulcast and remove injected") {
                        EndpointSourceSet(
                            sourceSet.sources + s8,
                            sourceSet.ssrcGroups
                        ).stripSimulcast(stripInjected = true) shouldBe EndpointSourceSet(
                            setOf(s1, s4, s7),
                            setOf(fid1)
                        )
                    }
                }
            }
        }
        context("ConferenceSourceMap") {
            val s7 = Source(7, AUDIO, injected = true)
            val endpoint1SourceSet = EndpointSourceSet(
                setOf(s1, s2, s7),
                setOf(
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                )
            )

            val endpoint1AdditionalSourceSet = EndpointSourceSet(
                setOf(
                    // The duplicates should be removed
                    s1, s2, s3, s4, s7
                ),
                setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 4)))
            )
            val endpoint1CombinedSourceSet = endpoint1SourceSet + endpoint1AdditionalSourceSet

            val endpoint2SourceSet = EndpointSourceSet(
                setOf(
                    Source(101, VIDEO),
                    Source(102, VIDEO),
                    Source(103, AUDIO)
                ),
                setOf(
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(101, 102))
                )
            )

            context("Constructors") {
                context("Default") {
                    val conferenceSourceMap = ConferenceSourceMap(mutableMapOf(jid1 to endpoint1SourceSet))
                    conferenceSourceMap.size shouldBe 1
                    conferenceSourceMap[jid1] shouldBe endpoint1SourceSet
                }
                context("With a single EndpointSourceSet") {
                    val conferenceSourceMap = ConferenceSourceMap(jid1 to endpoint1SourceSet)
                    conferenceSourceMap.size shouldBe 1
                    conferenceSourceMap[jid1] shouldBe endpoint1SourceSet
                }
                context("With multiple EndpointSourceSets") {
                    val conferenceSourceMap = ConferenceSourceMap(
                        jid1 to endpoint1SourceSet,
                        jid2 to endpoint2SourceSet
                    )
                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe endpoint1SourceSet
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
            }
            context("Add") {
                val conferenceSourceMap = ConferenceSourceMap(jid1 to endpoint1SourceSet)

                context("Without overlap in endpoints") {
                    conferenceSourceMap.add(jid2, endpoint2SourceSet)
                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe endpoint1SourceSet
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
                context("With overlap in endpoints") {
                    conferenceSourceMap.add(
                        ConferenceSourceMap(
                            jid1 to endpoint1AdditionalSourceSet,
                            jid2 to endpoint2SourceSet
                        )
                    )

                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe endpoint1CombinedSourceSet
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
            }
            context("Remove") {
                val conferenceSourceMap = ConferenceSourceMap(
                    jid1 to endpoint1CombinedSourceSet,
                    jid2 to endpoint2SourceSet
                )
                context("All of an endpoint's sources") {
                    conferenceSourceMap.remove(ConferenceSourceMap(jid2 to endpoint2SourceSet))
                    conferenceSourceMap.size shouldBe 1
                    conferenceSourceMap[jid1] shouldBe endpoint1CombinedSourceSet
                    conferenceSourceMap[jid2] shouldBe null
                }
                context("Some of an endpoint's sources") {
                    conferenceSourceMap.remove(ConferenceSourceMap(jid1 to endpoint1SourceSet))
                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe EndpointSourceSet(
                        setOf(s3, s4),
                        setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 4)))
                    )
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
                context("Nonexistent endpoint sources") {
                    conferenceSourceMap.remove(
                        ConferenceSourceMap(
                            jid1 to EndpointSourceSet(
                                setOf(Source(12345, VIDEO)),
                                setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(12345)))
                            )
                        )
                    )
                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe endpoint1CombinedSourceSet
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
                context("Nonexistent endpoint") {
                    conferenceSourceMap.remove(
                        ConferenceSourceMap(
                            JidCreate.from("differentJid") to endpoint1CombinedSourceSet
                        )
                    )
                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe endpoint1CombinedSourceSet
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
            }
            context("To Jingle") {
                val conferenceSourceMap = ConferenceSourceMap(jid1 to endpoint1SourceSet)
                val contents = conferenceSourceMap.toJingle()

                contents.size shouldBe 2

                val videoContent = contents.find { it.name == "video" }!!
                val videoRtpDesc = videoContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
                val videoSources =
                    videoRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                videoSources.map { Source(VIDEO, it) }.toSet() shouldBe setOf(
                    Source(1, VIDEO),
                    Source(2, VIDEO)
                )
                videoSources.forEach {
                    it.getFirstChildOfType(SSRCInfoPacketExtension::class.java).owner shouldBe jid1
                }

                val sourceGroupPacketExtension =
                    videoRtpDesc.getFirstChildOfType(SourceGroupPacketExtension::class.java)!!
                SsrcGroup.fromPacketExtension(sourceGroupPacketExtension) shouldBe
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))

                val audioContent = contents.find { it.name == "audio" }!!
                val audioRtpDesc = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
                val audioSources = audioRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                audioSources.map { Source(AUDIO, it) } shouldBe listOf(s7)
                audioSources.forEach {
                    it.getFirstChildOfType(SSRCInfoPacketExtension::class.java).owner shouldBe jid1
                }
            }
            context("removeInjected") {
                context("With remaining") {
                    val conferenceSourceMap = ConferenceSourceMap(
                        jid1 to endpoint1SourceSet,
                        jid2 to endpoint2SourceSet
                    ).stripInjected()

                    conferenceSourceMap.size shouldBe 2
                    conferenceSourceMap[jid1] shouldBe EndpointSourceSet(
                        setOf(
                            Source(1, VIDEO),
                            Source(2, VIDEO),
                        ),
                        setOf(
                            SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                        )
                    )
                    conferenceSourceMap[jid2] shouldBe endpoint2SourceSet
                }
                context("Without remaining") {
                    val conferenceSourceMap = ConferenceSourceMap(
                        jid1 to EndpointSourceSet(
                            setOf(Source(1, AUDIO, injected = true))
                        )
                    ).stripInjected()
                    conferenceSourceMap.isEmpty() shouldBe true
                }
            }
            context("unmodifiable") {
                val conferenceSourceMap = ConferenceSourceMap(
                    jid1 to endpoint1SourceSet,
                    jid2 to endpoint2SourceSet
                )

                val unmodifiable = conferenceSourceMap.unmodifiable
                unmodifiable[jid1] shouldBe endpoint1SourceSet

                conferenceSourceMap.add(jid1, endpoint1AdditionalSourceSet)
                conferenceSourceMap[jid1] shouldBe endpoint1CombinedSourceSet
                unmodifiable[jid1] shouldBe endpoint1CombinedSourceSet

                shouldThrow<UnsupportedOperationException> {
                    unmodifiable.add(ConferenceSourceMap())
                }
                shouldThrow<UnsupportedOperationException> {
                    unmodifiable.remove(ConferenceSourceMap())
                }
            }
            context("Strip") {
                val s7 = Source(7, AUDIO, injected = true)
                val sourceSet = EndpointSourceSet(
                    setOf(s1, s2, s3, s4, s5, s6, s7),
                    setOf(sim, fid1, fid2, fid3)
                )
                val conferenceSourceMap = ConferenceSourceMap(jid1 to sourceSet, jid2 to e2sourceSet)

                // Assume EndpointSourceSet.stripSimulcast works correctly, tested above.
                context("Nothing") {
                    conferenceSourceMap.strip(stripSimulcast = false, stripInjected = false) shouldBe
                        ConferenceSourceMap(jid1 to sourceSet, jid2 to e2sourceSet)
                }
                context("Simulcast") {
                    conferenceSourceMap.stripSimulcast()
                    conferenceSourceMap[jid1] shouldBe sourceSet.stripSimulcast()
                    conferenceSourceMap[jid2] shouldBe e2sourceSet.stripSimulcast()
                }
                context("Injected") {
                    conferenceSourceMap.stripInjected()
                    conferenceSourceMap[jid1] shouldBe sourceSet.stripInjected()
                    conferenceSourceMap[jid2] shouldBe e2sourceSet.stripInjected()
                }
                context("Simulcast and injected") {
                    conferenceSourceMap.strip(stripSimulcast = true, stripInjected = true)
                    conferenceSourceMap[jid1] shouldBe sourceSet.stripSimulcast(stripInjected = true)
                    conferenceSourceMap[jid2] shouldBe e2sourceSet.stripSimulcast(stripInjected = true)
                }
            }
        }
    }
}
