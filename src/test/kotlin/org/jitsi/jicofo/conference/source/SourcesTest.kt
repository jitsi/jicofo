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
import io.kotest.matchers.shouldNotBe
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate
import java.lang.UnsupportedOperationException

@SuppressFBWarnings(
    value = ["RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"],
    justification = "False positives."
)
class SourcesTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        context("Source") {
            context("From XML") {
                val packetExtension = SourcePacketExtension().apply {
                    ssrc = 1
                    addChildExtension(ParameterPacketExtension("msid", "msid"))
                    addChildExtension(ParameterPacketExtension("cname", "cname"))
                    isInjected = true
                }

                Source(MediaType.VIDEO, packetExtension) shouldBe
                    Source(1, MediaType.VIDEO, msid = "msid", cname = "cname", injected = true)
            }
            context("To XML") {
                val msidValue = "msid-value"
                val cnameValue = "cname-value"
                val source = Source(1, MediaType.VIDEO, msid = msidValue, cname = cnameValue, injected = true)
                val ownerJid = JidCreate.fullFrom("confname@conference.example.com/abcdabcd")
                val extension = source.toPacketExtension(owner = ownerJid)

                extension.ssrc shouldBe 1
                extension.isInjected shouldBe true
                val parameters = extension.getChildExtensionsOfType(ParameterPacketExtension::class.java)
                parameters.filter { it.name == "msid" && it.value == msidValue }.size shouldBe 1
                parameters.filter { it.name == "cname" && it.value == cnameValue }.size shouldBe 1

                val ssrcInfo = extension.getFirstChildOfType(SSRCInfoPacketExtension::class.java)
                ssrcInfo shouldNotBe null
                ssrcInfo.owner shouldBe ownerJid
            }
        }
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
        context("SsrcGroup") {
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
        }
        context("EndpointSourceSet") {
            context("From XML") {
                // Assume serializing works correctly -- it's tested below.
                val ownerJid = JidCreate.from("confname@conference.example.com/abcdabcd")

                val sources = setOf(Source(1, MediaType.VIDEO), Source(2, MediaType.VIDEO), Source(3, MediaType.AUDIO))
                val ssrcGroups = setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)))

                val contents = EndpointSourceSet(sources, ssrcGroups).toJingle(ownerJid)
                val parsed = EndpointSourceSet.fromJingle(contents)
                // Use the provided ownerJid, not the one encoded in XML.
                parsed.sources shouldBe sources
                parsed.ssrcGroups shouldBe ssrcGroups
            }
            context("To XML") {
                val endpointSourceSet = EndpointSourceSet(
                    setOf(Source(1, MediaType.VIDEO), Source(2, MediaType.VIDEO), Source(3, MediaType.AUDIO)),
                    setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)))
                )

                val contents = endpointSourceSet.toJingle()
                contents.size shouldBe 2

                val videoContent = contents.find { it.name == "video" }!!
                val videoRtpDesc = videoContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
                val videoSources = videoRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                    .map { Source(MediaType.VIDEO, it) }
                videoSources.toSet() shouldBe setOf(
                    Source(1, MediaType.VIDEO),
                    Source(2, MediaType.VIDEO)
                )
                val sourceGroupPacketExtension =
                    videoRtpDesc.getFirstChildOfType(SourceGroupPacketExtension::class.java)!!
                SsrcGroup.fromPacketExtension(sourceGroupPacketExtension) shouldBe
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))

                val audioContent = contents.find { it.name == "audio" }!!
                val audioRtpDesc = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
                val audioSources = audioRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                    .map { Source(MediaType.AUDIO, it) }
                audioSources shouldBe listOf(Source(3, MediaType.AUDIO))
            }
            context("ConferenceSourceMap") {
                val endpoint1Jid = JidCreate.from("jid1")
                val endpoint1SourceSet = EndpointSourceSet(
                    setOf(
                        Source(1, MediaType.VIDEO),
                        Source(2, MediaType.VIDEO),
                        Source(3, MediaType.AUDIO, injected = true)
                    ),
                    setOf(
                        SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                    )
                )

                val endpoint1AdditionalSourceSet = EndpointSourceSet(
                    setOf(
                        // The duplicates should be removed
                        Source(1, MediaType.VIDEO),
                        Source(2, MediaType.VIDEO),
                        Source(3, MediaType.AUDIO, injected = true),
                        Source(4, MediaType.VIDEO),
                        Source(5, MediaType.VIDEO),
                        Source(6, MediaType.AUDIO),
                    ),
                    setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(4, 5)))
                )
                val endpoint1CombinedSourceSet = endpoint1SourceSet + endpoint1AdditionalSourceSet

                val endpoint2Jid = JidCreate.from("jid2")
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
                        val conferenceSourceMap = ConferenceSourceMap(
                            mutableMapOf(
                                endpoint1Jid to endpoint1SourceSet
                            )
                        )
                        conferenceSourceMap.size shouldBe 1
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1SourceSet
                    }
                    context("With a single EndpointSourceSet") {
                        val conferenceSourceMap = ConferenceSourceMap(endpoint1Jid to endpoint1SourceSet)
                        conferenceSourceMap.size shouldBe 1
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1SourceSet
                    }
                    context("With multiple EndpointSourceSets") {
                        val conferenceSourceMap = ConferenceSourceMap(
                            endpoint1Jid to endpoint1SourceSet,
                            endpoint2Jid to endpoint2SourceSet
                        )
                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1SourceSet
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                }
                context("Add") {
                    val conferenceSourceMap = ConferenceSourceMap(endpoint1Jid to endpoint1SourceSet)

                    context("Without overlap in endpoints") {
                        conferenceSourceMap.add(endpoint2Jid, endpoint2SourceSet)
                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1SourceSet
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                    context("With overlap in endpoints") {
                        conferenceSourceMap.add(
                            ConferenceSourceMap(
                                endpoint1Jid to endpoint1AdditionalSourceSet,
                                endpoint2Jid to endpoint2SourceSet
                            )
                        )

                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1CombinedSourceSet
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                }
                context("Remove") {
                    val conferenceSourceMap = ConferenceSourceMap(
                        endpoint1Jid to endpoint1CombinedSourceSet,
                        endpoint2Jid to endpoint2SourceSet
                    )
                    context("All of an endpoint's sources") {
                        conferenceSourceMap.remove(ConferenceSourceMap(endpoint2Jid to endpoint2SourceSet))
                        conferenceSourceMap.size shouldBe 1
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1CombinedSourceSet
                        conferenceSourceMap[endpoint2Jid] shouldBe null
                    }
                    context("Some of an endpoint's sources") {
                        conferenceSourceMap.remove(ConferenceSourceMap(endpoint1Jid to endpoint1SourceSet))
                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe EndpointSourceSet(
                            setOf(
                                Source(4, MediaType.VIDEO),
                                Source(5, MediaType.VIDEO),
                                Source(6, MediaType.AUDIO),
                            ),
                            setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(4, 5)))
                        )
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                    context("Nonexistent endpoint sources") {
                        conferenceSourceMap.remove(
                            ConferenceSourceMap(
                                endpoint1Jid to EndpointSourceSet(
                                    setOf(Source(12345, MediaType.VIDEO)),
                                    setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(12345)))
                                )
                            )
                        )
                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1CombinedSourceSet
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                    context("Nonexistent endpoint") {
                        conferenceSourceMap.remove(
                            ConferenceSourceMap(
                                JidCreate.from("differentJid") to endpoint1CombinedSourceSet
                            )
                        )
                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe endpoint1CombinedSourceSet
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                }
                context("To Jingle") {
                    val conferenceSourceMap = ConferenceSourceMap(endpoint1Jid to endpoint1SourceSet)
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
                        it.getFirstChildOfType(SSRCInfoPacketExtension::class.java).owner shouldBe endpoint1Jid
                    }

                    val sourceGroupPacketExtension =
                        videoRtpDesc.getFirstChildOfType(SourceGroupPacketExtension::class.java)!!
                    SsrcGroup.fromPacketExtension(sourceGroupPacketExtension) shouldBe
                        SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))

                    val audioContent = contents.find { it.name == "audio" }!!
                    val audioRtpDesc = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)!!
                    val audioSources = audioRtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java)
                    audioSources.map { Source(MediaType.AUDIO, it) } shouldBe
                        listOf(Source(3, MediaType.AUDIO, injected = true))
                    audioSources.forEach {
                        it.getFirstChildOfType(SSRCInfoPacketExtension::class.java).owner shouldBe endpoint1Jid
                    }
                }
                context("removeInjected") {
                    context("With remaining") {
                        val conferenceSourceMap = ConferenceSourceMap(
                            endpoint1Jid to endpoint1SourceSet,
                            endpoint2Jid to endpoint2SourceSet
                        ).removeInjected()

                        conferenceSourceMap.size shouldBe 2
                        conferenceSourceMap[endpoint1Jid] shouldBe EndpointSourceSet(
                            setOf(
                                Source(1, MediaType.VIDEO),
                                Source(2, MediaType.VIDEO),
                            ),
                            setOf(
                                SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                            )
                        )
                        conferenceSourceMap[endpoint2Jid] shouldBe endpoint2SourceSet
                    }
                    context("Without remaining") {
                        val conferenceSourceMap = ConferenceSourceMap(
                            endpoint1Jid to EndpointSourceSet(
                                setOf(Source(1, MediaType.AUDIO, injected = true))
                            )
                        ).removeInjected()
                        conferenceSourceMap.isEmpty() shouldBe true
                    }
                }
                context("unmodifiable") {
                    val conferenceSourceMap = ConferenceSourceMap(
                        endpoint1Jid to endpoint1SourceSet,
                        endpoint2Jid to endpoint2SourceSet
                    )

                    val unmodifiable = conferenceSourceMap.unmodifiable
                    unmodifiable[endpoint1Jid] shouldBe endpoint1SourceSet

                    conferenceSourceMap.add(endpoint1Jid, endpoint1AdditionalSourceSet)
                    conferenceSourceMap[endpoint1Jid] shouldBe endpoint1CombinedSourceSet
                    unmodifiable[endpoint1Jid] shouldBe endpoint1CombinedSourceSet

                    shouldThrow <UnsupportedOperationException> {
                        unmodifiable.add(ConferenceSourceMap())
                    }
                    shouldThrow <UnsupportedOperationException> {
                        unmodifiable.remove(ConferenceSourceMap())
                    }
                }
            }
        }
    }
}
