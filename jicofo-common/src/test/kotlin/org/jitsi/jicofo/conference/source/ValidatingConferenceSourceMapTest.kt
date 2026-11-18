/*
 * Copyright @ 2018 - present 8x8, Inc.
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
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.MediaType.AUDIO
import org.jitsi.utils.MediaType.VIDEO

@Suppress("NAME_SHADOWING")
class ValidatingConferenceSourceMapTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        val jid1 = "jid1"
        val jid2 = "jid2"
        val msid = "msid"

        val s1 = Source(1, VIDEO, msid = msid)
        val s2 = Source(2, VIDEO, msid = msid)
        val s3 = Source(3, VIDEO, msid = msid)
        val s4 = Source(4, VIDEO, msid = msid)
        val s5 = Source(5, VIDEO, msid = msid)
        val s6 = Source(6, VIDEO, msid = msid)
        val s7 = Source(7, AUDIO)
        val videoSources = setOf(s1, s2, s3, s4, s5, s6)
        val sources = setOf(s1, s2, s3, s4, s5, s6, s7)

        val sim = SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3))
        val fid1 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 4))
        val fid2 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(2, 5))
        val fid3 = SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 6))
        val groups = setOf(sim, fid1, fid2, fid3)

        val sourceSet = EndpointSourceSet(sources, groups)

        val conferenceSources = ValidatingConferenceSourceMap(
            MAX_SSRCS_PER_USER,
            MAX_SSRC_GROUPS_PER_USER
        )

        context("Adding sources.") {
            context("Empty sources") {
                conferenceSources.tryToAdd(jid1, EndpointSourceSet()) shouldBe EndpointSourceSet.EMPTY
            }
            context("Standard VP8 signaling with simulcast and RTX") {
                context("Signaled all together") {
                    conferenceSources.tryToAdd(jid1, sourceSet) shouldBe sourceSet
                }
                context("Signaled with video first") {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(videoSources, groups)) shouldBe
                        EndpointSourceSet(videoSources, groups)
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(s7)) shouldBe EndpointSourceSet(s7)
                }
                context("Signaled with audio first") {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(s7)) shouldBe EndpointSourceSet(s7)
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(videoSources, groups)) shouldBe
                        EndpointSourceSet(videoSources, groups)
                }
                context("Adding a second endpoint") {
                    val sourceSet2 = EndpointSourceSet(
                        setOf(
                            Source(101, VIDEO, msid = "msid2"),
                            Source(102, VIDEO, msid = "msid2"),
                            Source(103, AUDIO)
                        ),
                        setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(101, 102)))
                    )

                    conferenceSources.tryToAdd(jid1, sourceSet) shouldBe sourceSet
                    conferenceSources.tryToAdd(jid2, sourceSet2) shouldBe sourceSet2
                }
            }
            context("Invalid SSRCs") {
                val invalidSsrcs = listOf(-1, 0, 0x1_0000_0000)
                invalidSsrcs.forEach {
                    val sourcesWithInvalidSsrc = EndpointSourceSet(setOf(Source(it, AUDIO)))

                    shouldThrow<InvalidSsrcException> {
                        conferenceSources.tryToAdd(jid1, sourcesWithInvalidSsrc)
                    }
                }
            }
            context("Max SSRC count limit") {
                val conferenceSources = ValidatingConferenceSourceMap(
                    maxSsrcsPerUser = 4,
                    MAX_SSRC_GROUPS_PER_USER
                )
                context("At once") {
                    shouldThrow<SsrcLimitExceededException> {
                        conferenceSources.tryToAdd(jid1, sourceSet)
                    }
                }
                context("With multiple adds") {
                    val sourceSet1 = EndpointSourceSet(
                        setOf(s1, s2, s3),
                        setOf(sim)
                    )
                    val sourceSet2 = EndpointSourceSet(
                        setOf(s4, s5, s6),
                        setOf(fid1, fid2, fid3)
                    )

                    conferenceSources.tryToAdd(jid1, sourceSet1) shouldBe sourceSet1
                    shouldThrow<SsrcLimitExceededException> {
                        conferenceSources.tryToAdd(jid1, sourceSet2)
                    }
                }
            }
            context("Max ssrc-group count limit") {
                val conferenceSources = ValidatingConferenceSourceMap(
                    MAX_SSRCS_PER_USER,
                    maxSsrcGroupsPerUser = 2
                )
                context("At once") {
                    shouldThrow<SsrcGroupLimitExceededException> {
                        conferenceSources.tryToAdd(jid1, sourceSet)
                    }
                }
                context("With multiple adds") {
                    val sourceSet1 = EndpointSourceSet(
                        setOf(s1, s2, s3),
                        setOf(sim)
                    )
                    val sourceSet2 = EndpointSourceSet(
                        setOf(s4, s5, s6),
                        setOf(fid1, fid2, fid3)
                    )
                    conferenceSources.tryToAdd(jid1, sourceSet1) shouldBe sourceSet1
                    shouldThrow<SsrcGroupLimitExceededException> {
                        conferenceSources.tryToAdd(jid1, sourceSet2)
                    }
                }
            }
            context("Duplicate SSRCs") {
                val source = EndpointSourceSet(setOf(Source(1, AUDIO)))
                val duplicateSourceAudio = EndpointSourceSet(
                    setOf(Source(1, AUDIO, msid = "differentMsid"))
                )
                val duplicateSourceVideo = EndpointSourceSet(
                    setOf(Source(1, VIDEO, msid = "differentMsid"))
                )

                conferenceSources.tryToAdd(jid1, source) shouldBe source
                context("Advertised by the same endpoint.") {
                    shouldThrow<SsrcAlreadyUsedException> {
                        conferenceSources.tryToAdd(jid1, duplicateSourceAudio)
                    }
                    shouldThrow<SsrcAlreadyUsedException> {
                        conferenceSources.tryToAdd(jid1, duplicateSourceVideo)
                    }
                }
                context("Advertised by another endpoint.") {
                    shouldThrow<SsrcAlreadyUsedException> {
                        conferenceSources.tryToAdd(jid2, duplicateSourceAudio)
                    }
                    shouldThrow<SsrcAlreadyUsedException> {
                        conferenceSources.tryToAdd(jid2, duplicateSourceVideo)
                    }
                }
            }
            context("SSRC group with missing MSID") {
                val sources = setOf(
                    Source(1, VIDEO, msid = null),
                    Source(2, VIDEO, msid = null),
                    Source(3, VIDEO, msid = null)
                )
                val groups = setOf(SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3)))
                shouldThrow<RequiredParameterMissingException> {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(sources, groups))
                }
            }
            context("SSRC group with different MSID values") {
                val sources = setOf(
                    Source(1, VIDEO, msid = msid),
                    Source(2, VIDEO, msid = msid),
                    Source(3, VIDEO, msid = "differentMsid")
                )
                val groups = setOf(SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3)))

                shouldThrow<GroupMsidMismatchException> {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(sources, groups))
                }
            }
            context("Group with an SSRC that has no source signaled.") {
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(
                        jid1,
                        EndpointSourceSet(
                            // No source for ssrc 2
                            setOf(Source(1, VIDEO, msid = "msid")),
                            setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)))
                        )
                    )
                }
            }
            context("FID groups") {
                // Fid groups must have exactly 2 ssrcs
                shouldThrow<InvalidFidGroupException> {
                    conferenceSources.tryToAdd(
                        jid1,
                        EndpointSourceSet(
                            setOf(s1, s2, s3),
                            setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2, 3)))
                        )
                    )
                }
                shouldThrow<InvalidFidGroupException> {
                    conferenceSources.tryToAdd(
                        jid1,
                        EndpointSourceSet(
                            setOf(s1),
                            setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(1)))
                        )
                    )
                }
            }
            context("MSID Conflicts") {
                val sourceSet1 = EndpointSourceSet(setOf(s1))
                val sourceSet2 = EndpointSourceSet(setOf(s2))
                val combinedSourceSet = EndpointSourceSet(setOf(s1, s2))

                context("With another endpoint") {
                    conferenceSources.tryToAdd(jid1, sourceSet1)
                    shouldThrow<MsidConflictException> {
                        // jid2 tries to use an MSID already used by jid1
                        conferenceSources.tryToAdd(jid2, sourceSet2)
                    }
                }
                context("Multiple non-grouped SSRCs can have MSID=null") {
                    // I don't know if this is actually desired and/or used by clients. It is existing behavior that
                    // AdvertiseSSRCsTest depends upon.
                    val sourceSet = EndpointSourceSet(
                        setOf(
                            Source(1, VIDEO),
                            Source(2, VIDEO),
                            Source(3, VIDEO),
                            Source(4, AUDIO),
                            Source(5, AUDIO)
                        )
                    )
                    conferenceSources.tryToAdd(jid1, sourceSet) shouldBe sourceSet
                }
                context("Within the sources of the same endpoint.") {
                    context("Ungrouped") {
                        context("Added separately") {
                            conferenceSources.tryToAdd(jid1, sourceSet1)
                            // s1 and s2 have the same MSID
                            shouldThrow<MsidConflictException> {
                                conferenceSources.tryToAdd(jid1, sourceSet2)
                            }
                        }
                        context("Added together") {
                            shouldThrow<MsidConflictException> {
                                conferenceSources.tryToAdd(jid1, combinedSourceSet)
                            }
                        }
                    }
                    context("In independent FID groups") {
                        val endpointSourceSet = EndpointSourceSet(
                            setOf(s1, s2, s4, s5),
                            setOf(fid1, fid2)
                        )
                        shouldThrow<MsidConflictException> {
                            conferenceSources.tryToAdd(jid1, endpointSourceSet)
                        }
                    }
                    context("In independent SIM groups") {
                        val endpointSourceSet = EndpointSourceSet(
                            setOf(s1, s2, s3, s4),
                            setOf(
                                SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2)),
                                SsrcGroup(SsrcGroupSemantics.Sim, listOf(3, 4))
                            )
                        )
                        shouldThrow<MsidConflictException> {
                            conferenceSources.tryToAdd(jid1, endpointSourceSet)
                        }
                    }
                }
                context("Audio and video can have the same MSID") {
                    context("With no MSID") {
                        val sourceSet = EndpointSourceSet(
                            setOf(
                                Source(1, AUDIO),
                                Source(2, VIDEO)
                            )
                        )
                        conferenceSources.tryToAdd(jid1, sourceSet) shouldBe sourceSet
                    }
                    context("With MSID and no groups") {
                        val sourceSet = EndpointSourceSet(setOf(s1, Source(111, AUDIO, msid = msid)))

                        conferenceSources.tryToAdd(jid1, sourceSet) shouldBe sourceSet
                    }
                    context("With MSID and groups") {
                        val sourceSet = EndpointSourceSet(
                            sources + Source(111, AUDIO, msid = msid),
                            groups
                        )

                        conferenceSources.tryToAdd(jid1, sourceSet) shouldBe sourceSet
                    }
                }
            }
            context("Adding an empty group") {
                val accepted = conferenceSources.tryToAdd(
                    jid1,
                    EndpointSourceSet(
                        setOf(s1, s2, s3),
                        setOf(
                            sim,
                            SsrcGroup(SsrcGroupSemantics.Sim, emptyList()),
                            SsrcGroup(SsrcGroupSemantics.Fid, emptyList()),
                        )
                    )
                )

                // The empty groups should be silently ignored.
                accepted shouldBe EndpointSourceSet(setOf(s1, s2, s3), setOf(sim))
            }
            context("Source names") {
                val ownName = EndpointSourceSet(Source(11, AUDIO, name = "jid1-a0", msid = "m1"))
                val simulcast = EndpointSourceSet(
                    setOf(
                        Source(21, VIDEO, name = "jid1-v0", msid = "m3"),
                        Source(22, VIDEO, name = "jid1-v0", msid = "m3")
                    ),
                    setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(21, 22)))
                )

                context("With the prefix check") {
                    context("A name that starts with the endpoint ID is accepted") {
                        conferenceSources.tryToAdd(jid1, ownName) shouldBe ownName
                    }
                    context("Sources without a name are accepted") {
                        val noName = EndpointSourceSet(Source(12, AUDIO, msid = "m2"))
                        conferenceSources.tryToAdd(jid1, noName) shouldBe noName
                    }
                    context("Sources of one stream can share a name") {
                        conferenceSources.tryToAdd(jid1, simulcast) shouldBe simulcast
                    }
                    context("A synthetic name derived from the endpoint's own name is accepted") {
                        val translated = EndpointSourceSet(Source(13, AUDIO, name = "jid1-a0.fr", synthetic = true))
                        conferenceSources.tryToAdd(jid1, translated) shouldBe translated
                    }
                    context("A name with the prefix of another endpoint is rejected") {
                        context("when the other endpoint has not used it yet") {
                            shouldThrow<SourceNameNotOwnedException> {
                                conferenceSources.tryToAdd(jid2, ownName)
                            }
                            conferenceSources[jid2] shouldBe null
                        }
                        context("when the other endpoint uses it") {
                            conferenceSources.tryToAdd(jid1, ownName) shouldBe ownName
                            shouldThrow<SourceNameNotOwnedException> {
                                conferenceSources.tryToAdd(
                                    jid2,
                                    EndpointSourceSet(Source(14, AUDIO, name = "jid1-a0", msid = "m4"))
                                )
                            }
                            conferenceSources[jid2] shouldBe null
                        }
                    }
                    context("A name with no endpoint ID prefix is rejected") {
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "other")))
                        }
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "jid1")))
                        }
                        conferenceSources[jid1] shouldBe null
                    }
                    context("A name must have a token after the endpoint ID") {
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "jid1-")))
                        }
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "jid1-.fr")))
                        }
                    }
                    context("A name must match the media type") {
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "jid1-v0")))
                        }
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, VIDEO, name = "jid1-a0")))
                        }
                    }
                    context("A name that clients can not parse is rejected") {
                        listOf("jid1-x", "jid1-a", "jid1-a0x", "jid1-a12345", "jid1-a0\",\"x\":\"y").forEach {
                            shouldThrow<SourceNameNotOwnedException> {
                                conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = it)))
                            }
                        }
                        conferenceSources[jid1] shouldBe null
                    }
                    context("A source that is not synthetic can not have a suffix") {
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "jid1-a0.fr")))
                        }
                    }
                    context("The suffix of a synthetic source must be short and use a limited set of characters") {
                        listOf("jid1-a0.", "jid1-a0.a.b", "jid1-a0.\"", "jid1-a0." + "x".repeat(33)).forEach {
                            shouldThrow<SourceNameNotOwnedException> {
                                conferenceSources.tryToAdd(
                                    jid1,
                                    EndpointSourceSet(Source(15, AUDIO, name = it, synthetic = true))
                                )
                            }
                        }
                    }
                    context("A synthetic source can not use the name of a source that is not synthetic") {
                        conferenceSources.tryToAdd(jid1, ownName) shouldBe ownName
                        shouldThrow<SourceNameConflictException> {
                            conferenceSources.tryToAdd(
                                jid1,
                                EndpointSourceSet(Source(16, AUDIO, name = "jid1-a0", synthetic = true))
                            )
                        }
                    }
                    context("An endpoint ID with a \".\" does not make a name valid for two endpoints") {
                        // Without a suffix on signaled sources, "abcd1234-a0.pt-BR" is not a valid name of endpoint
                        // "abcd1234-a0.pt".
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(
                                "abcd1234-a0.pt",
                                EndpointSourceSet(Source(15, AUDIO, name = "abcd1234-a0.pt-BR"))
                            )
                        }
                    }
                    context("A suffix after the token can have a \"-\"") {
                        val name = EndpointSourceSet(Source(15, AUDIO, name = "jid1-a0.pt-BR", synthetic = true))
                        conferenceSources.tryToAdd(jid1, name) shouldBe name
                    }
                    context("An endpoint ID followed by \"-\" that is a prefix of another endpoint ID") {
                        // "agent" followed by "-" is a prefix of "agent-x", but the names of "agent-x" (for
                        // example "agent-x-a0") are not valid names of "agent".
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToAdd(
                                "agent",
                                EndpointSourceSet(Source(15, AUDIO, name = "agent-x-a0"))
                            )
                        }
                        shouldThrow<SourceNameNotOwnedException> {
                            conferenceSources.tryToReserve("agent", setOf(Source(15, AUDIO, name = "agent-x-a0")))
                        }
                        val name = EndpointSourceSet(Source(15, AUDIO, name = "agent-x-a0"))
                        conferenceSources.tryToAdd("agent-x", name) shouldBe name
                    }
                }
                context("Without the name checks") {
                    val conferenceSources = ValidatingConferenceSourceMap(
                        MAX_SSRCS_PER_USER,
                        MAX_SSRC_GROUPS_PER_USER,
                        validateSourceNames = false
                    )

                    context("A name with no endpoint ID prefix is accepted") {
                        val name = EndpointSourceSet(Source(15, AUDIO, name = "other"))
                        conferenceSources.tryToAdd(jid1, name) shouldBe name
                    }
                    context("Two endpoints can use the same name") {
                        val name1 = EndpointSourceSet(Source(15, AUDIO, name = "audio"))
                        val name2 = EndpointSourceSet(Source(16, AUDIO, name = "audio"))
                        conferenceSources.tryToAdd(jid1, name1) shouldBe name1
                        conferenceSources.tryToAdd(jid2, name2) shouldBe name2
                    }
                    context("A synthetic source can not use the name of a source that is not synthetic") {
                        conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "jid1-a0.fr")))
                        shouldThrow<SourceNameConflictException> {
                            conferenceSources.tryToAdd(
                                jid1,
                                EndpointSourceSet(Source(16, AUDIO, name = "jid1-a0.fr", synthetic = true))
                            )
                        }
                    }
                    context("A reserved name is rejected") {
                        conferenceSources.tryToReserve("agent-x", setOf(Source(500, AUDIO, name = "agent-x-a0")))
                        shouldThrow<SourceNameConflictException> {
                            conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(15, AUDIO, name = "agent-x-a0")))
                        }
                    }
                    context("A name can be reserved after the signaled sources with it are removed") {
                        conferenceSources.tryToAdd(jid1, simulcast) shouldBe simulcast
                        val toReserve = setOf(Source(500, VIDEO, name = "jid1-v0", synthetic = true))

                        context("but not while one of them remains") {
                            conferenceSources.tryToRemove(jid1, EndpointSourceSet(Source(21, VIDEO, msid = "m3")))
                            shouldThrow<SourceNameConflictException> {
                                conferenceSources.tryToReserve(jid1, toReserve)
                            }
                        }
                        context("with tryToRemove") {
                            conferenceSources.tryToRemove(jid1, simulcast)
                            conferenceSources.tryToReserve(jid1, toReserve)
                            conferenceSources.getReserved(jid1) shouldBe toReserve
                        }
                        context("with remove(owner)") {
                            conferenceSources.remove(jid1)
                            conferenceSources.tryToReserve(jid1, toReserve)
                            conferenceSources.getReserved(jid1) shouldBe toReserve
                        }
                    }
                }
            }
        }
        context("Reserved sources") {
            val agent = "agent1"
            val reserved = Source(500, AUDIO, name = "agent1-a0", synthetic = true)
            conferenceSources.tryToReserve(agent, setOf(reserved))

            context("are not part of the map's content") {
                conferenceSources[agent] shouldBe null
                conferenceSources.getReserved(agent) shouldBe setOf(reserved)
                conferenceSources.isSsrcUsed(500) shouldBe true
            }
            context("A participant can not signal a reserved SSRC") {
                shouldThrow<SsrcAlreadyUsedException> {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(500, AUDIO, name = "jid1-a0")))
                }
                conferenceSources[jid1] shouldBe null
            }
            context("A participant can not signal a reserved source name") {
                val conferenceSources = ValidatingConferenceSourceMap(
                    MAX_SSRCS_PER_USER,
                    MAX_SSRC_GROUPS_PER_USER,
                    validateSourceNames = false
                )
                conferenceSources.tryToReserve(agent, setOf(reserved))
                shouldThrow<SourceNameConflictException> {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(501, AUDIO, name = "agent1-a0")))
                }
                conferenceSources[jid1] shouldBe null
            }
            context("A source can not be reserved") {
                conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(1, AUDIO, name = "jid1-a0"))) shouldBe
                    EndpointSourceSet(Source(1, AUDIO, name = "jid1-a0"))

                context("with the SSRC of a signaled source") {
                    shouldThrow<SsrcAlreadyUsedException> {
                        conferenceSources.tryToReserve("agent2", setOf(Source(1, AUDIO, name = "agent2-a0")))
                    }
                    conferenceSources.getReserved("agent2") shouldBe emptySet()
                }
                context("with the SSRC that another endpoint reserved") {
                    shouldThrow<SsrcAlreadyUsedException> {
                        conferenceSources.tryToReserve("agent2", setOf(Source(500, AUDIO, name = "agent2-a0")))
                    }
                    conferenceSources.getReserved("agent2") shouldBe emptySet()
                }
                context("with the name of a signaled source") {
                    val conferenceSources = ValidatingConferenceSourceMap(
                        MAX_SSRCS_PER_USER,
                        MAX_SSRC_GROUPS_PER_USER,
                        validateSourceNames = false
                    )
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(1, AUDIO, name = "agent2-a0")))
                    shouldThrow<SourceNameConflictException> {
                        conferenceSources.tryToReserve("agent2", setOf(Source(2, AUDIO, name = "agent2-a0")))
                    }
                    conferenceSources.getReserved("agent2") shouldBe emptySet()
                }
                context("with a name that does not start with the endpoint ID") {
                    shouldThrow<SourceNameNotOwnedException> {
                        conferenceSources.tryToReserve("agent2", setOf(Source(2, AUDIO, name = "jid1-a1")))
                    }
                }
                context("with an invalid SSRC") {
                    shouldThrow<InvalidSsrcException> {
                        conferenceSources.tryToReserve("agent2", setOf(Source(0, AUDIO, name = "agent2-a0")))
                    }
                }
            }
            context("A new reservation replaces the old one") {
                val replacement = reserved.copy(ssrc = 600)
                conferenceSources.tryToReserve(agent, setOf(replacement))
                conferenceSources.getReserved(agent) shouldBe setOf(replacement)
                conferenceSources.isSsrcUsed(500) shouldBe false
                conferenceSources.isSsrcUsed(600) shouldBe true
            }
            context("A source without a name is checked with the name that the bridge uses for it") {
                conferenceSources.tryToReserve("agent-x", setOf(Source(600, AUDIO, name = "agent-x-a0")))
                shouldThrow<SourceNameConflictException> {
                    conferenceSources.tryToAdd("agent-x", EndpointSourceSet(Source(601, AUDIO)))
                }
                conferenceSources["agent-x"] shouldBe null

                conferenceSources.tryToAdd("agent-y", EndpointSourceSet(Source(602, AUDIO)))
                shouldThrow<SourceNameConflictException> {
                    conferenceSources.tryToReserve("agent-y", setOf(Source(603, AUDIO, name = "agent-y-a0")))
                }
                conferenceSources.getReserved("agent-y") shouldBe emptySet()
            }
            context("A failed reservation keeps the earlier one") {
                conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(1, AUDIO, name = "jid1-a0")))
                shouldThrow<SsrcAlreadyUsedException> {
                    conferenceSources.tryToReserve(agent, setOf(reserved.copy(ssrc = 1)))
                }
                conferenceSources.getReserved(agent) shouldBe setOf(reserved)
                conferenceSources.isSsrcUsed(500) shouldBe true
            }
            context("The same source can be reserved again") {
                conferenceSources.tryToReserve(agent, setOf(reserved))
                conferenceSources.getReserved(agent) shouldBe setOf(reserved)
            }
            context("A reservation can not repeat an SSRC") {
                shouldThrow<SsrcAlreadyUsedException> {
                    conferenceSources.tryToReserve(
                        "agent2",
                        setOf(Source(700, AUDIO, name = "agent2-a0"), Source(700, VIDEO, name = "agent2-v0"))
                    )
                }
                conferenceSources.getReserved("agent2") shouldBe emptySet()
            }
            context("Signaled and reserved sources of the same endpoint ID") {
                val reservedWithMsid = Source(800, AUDIO, name = "agent2-a0", msid = "agent2-msid", synthetic = true)
                conferenceSources.tryToReserve("agent2", setOf(reservedWithMsid))

                context("A signaled source can not use the reserved name") {
                    shouldThrow<SourceNameConflictException> {
                        conferenceSources.tryToAdd("agent2", EndpointSourceSet(Source(801, AUDIO, name = "agent2-a0")))
                    }
                    conferenceSources["agent2"] shouldBe null
                }
                context("A signaled source can not use the reserved MSID") {
                    shouldThrow<MsidConflictException> {
                        conferenceSources.tryToAdd(
                            "agent2",
                            EndpointSourceSet(Source(801, AUDIO, name = "agent2-a1", msid = "agent2-msid"))
                        )
                    }
                    conferenceSources["agent2"] shouldBe null
                }
                context("A source can not be reserved with the name of a signaled source") {
                    conferenceSources.tryToAdd(jid1, EndpointSourceSet(Source(1, AUDIO, name = "jid1-a0")))
                    shouldThrow<SourceNameConflictException> {
                        conferenceSources.tryToReserve(jid1, setOf(Source(2, AUDIO, name = "jid1-a0")))
                    }
                    conferenceSources.getReserved(jid1) shouldBe emptySet()
                }
            }
            context("Releasing a reservation frees its SSRC and name") {
                val conferenceSources = ValidatingConferenceSourceMap(
                    MAX_SSRCS_PER_USER,
                    MAX_SSRC_GROUPS_PER_USER,
                    validateSourceNames = false
                )
                conferenceSources.tryToReserve(agent, setOf(reserved))
                conferenceSources.tryToReserve(agent, emptySet())
                conferenceSources.getReserved(agent) shouldBe emptySet()
                conferenceSources.isSsrcUsed(500) shouldBe false
                val sameSource = EndpointSourceSet(Source(500, AUDIO, name = "agent1-a0"))
                conferenceSources.tryToAdd(jid1, sameSource) shouldBe sameSource
            }
        }
        context("Removing sources") {

            conferenceSources.tryToAdd(jid1, sourceSet)

            context("Empty sources") {
                conferenceSources.tryToRemove(jid1, EndpointSourceSet()) shouldBe EndpointSourceSet.EMPTY
            }
            context("Successful removal") {
                context("Of all sources and groups") {
                    conferenceSources.tryToRemove(jid1, sourceSet) shouldBe sourceSet
                }
                context("Of all sources, groups assumed") {
                    conferenceSources.tryToRemove(jid1, EndpointSourceSet(sources)) shouldBe sourceSet
                }
                context("Of a subset of sources") {
                    // s1 remains, with no associated groups.
                    val toRemove = EndpointSourceSet(sources - s1, groups)
                    conferenceSources.tryToRemove(jid1, toRemove) shouldBe toRemove
                }
            }
            context("Removing non-signaled sources") {
                context("Another endpoint's sources") {
                    shouldThrow<SourceDoesNotExistException> {
                        conferenceSources.tryToRemove(jid2, sourceSet)
                    }
                }
                context("A non-signaled source") {
                    shouldThrow<SourceDoesNotExistException> {
                        conferenceSources.tryToRemove(jid1, EndpointSourceSet(Source(1111, AUDIO)))
                    }
                }
                context("A non-signaled ssrc-group") {
                    shouldThrow<SourceGroupDoesNotExistException> {
                        conferenceSources.tryToRemove(
                            jid1,
                            EndpointSourceSet(
                                sources,
                                groups + SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                            )
                        )
                    }
                }
            }
            context("Removing a subset of sources") {
                context("Leaving two FID groups") {
                    shouldThrow<MsidConflictException> {
                        conferenceSources.tryToRemove(
                            jid1,
                            EndpointSourceSet(
                                setOf(s1, s4),
                                setOf(sim, fid1)
                            )
                        )
                    }
                }
                context("Removing only part of a simulcast group's sources") {
                    // This matches multiple failure conditions (MSIF conflict, missing source from a group)
                    shouldThrow<ValidationFailedException> {
                        conferenceSources.tryToRemove(jid1, EndpointSourceSet(s1))
                    }
                }
                context("Removing a group, but not its sources") {
                    shouldThrow<MsidConflictException> {
                        conferenceSources.tryToRemove(jid1, EndpointSourceSet(sim))
                    }
                    shouldThrow<MsidConflictException> {
                        conferenceSources.tryToRemove(jid1, EndpointSourceSet(fid1))
                    }
                    shouldThrow<MsidConflictException> {
                        conferenceSources.tryToRemove(jid1, EndpointSourceSet(ssrcGroups = groups))
                    }
                }
            }
        }
    }
}

// Default values from jicofo.conference.max-ssrcs-per-user / max-ssrc-groups-per-user.
private const val MAX_SSRCS_PER_USER = 20
private const val MAX_SSRC_GROUPS_PER_USER = 20
