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
package org.jitsi.jicofo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.GroupMsidMismatchException
import org.jitsi.jicofo.conference.source.InvalidFidGroupException
import org.jitsi.jicofo.conference.source.InvalidSsrcException
import org.jitsi.jicofo.conference.source.MsidConflictException
import org.jitsi.jicofo.conference.source.RequiredParameterMissingException
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SourceDoesNotExistException
import org.jitsi.jicofo.conference.source.SourceGroupDoesNotExistException
import org.jitsi.jicofo.conference.source.SsrcAlreadyUsedException
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupLimitExceededException
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.jicofo.conference.source.SsrcLimitExceededException
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.jicofo.conference.source.ValidationFailedException
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
            ConferenceConfig.config.maxSsrcsPerUser,
            ConferenceConfig.config.maxSsrcGroupsPerUser
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
                    ConferenceConfig.config.maxSsrcGroupsPerUser
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
                    ConferenceConfig.config.maxSsrcsPerUser,
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
