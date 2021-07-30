package org.jitsi.jicofo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.jicofo.conference.source.ValidationFailedException
import org.jitsi.utils.MediaType.AUDIO
import org.jitsi.utils.MediaType.VIDEO
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class ValidatingConferenceSourceMapTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        val conferenceSources = ValidatingConferenceSourceMap()

        context("Port of SSRCValidatorTest") {
            val jid1 = JidCreate.from("jid1")
            val jid2 = JidCreate.from("jid2")
            context("test2ParticipantsWithSimAndRtx") {
                val endpoint1Sources = createSourcesForEndpoint(jid1, 1)
                val endpoint2Sources = createSourcesForEndpoint(jid2, 111)

                conferenceSources.tryToAdd(jid1, endpoint1Sources) shouldBe endpoint1Sources
                conferenceSources.tryToAdd(jid2, endpoint2Sources) shouldBe endpoint2Sources
            }
            context("testNegative") {
                val sourcesWithInvalidSsrc = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(-1, AUDIO)),
                        emptySet()
                    )
                )

                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, sourcesWithInvalidSsrc)
                }
            }
            context("testZero") {
                // TODO why is 0 invalid?
                val sourcesWithInvalidSsrc = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(0, AUDIO)),
                        emptySet()
                    )
                )

                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, sourcesWithInvalidSsrc)
                }
            }
            context("testInvalidNumber") {
                val sourcesWithInvalidSsrc = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(0x1_0000_0000, AUDIO)),
                        emptySet()
                    )
                )

                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, sourcesWithInvalidSsrc)
                }
            }
            context("testDuplicate") {
                val source = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(1, AUDIO)),
                        emptySet()
                    )
                )
                val duplicateJid1 = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(1, AUDIO, msid = "differentMsid")),
                        emptySet()
                    )
                )
                val duplicateJid2 = ConferenceSourceMap(
                    jid2,
                    EndpointSourceSet(
                        setOf(Source(1, AUDIO, msid = "differentMsid")),
                        emptySet()
                    )
                )

                conferenceSources.tryToAdd(jid1, source) shouldBe source
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, duplicateJid1)
                }
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid2, duplicateJid2)
                }
            }
            context("testDuplicateDifferentMediaType") {
                val source = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(1, AUDIO)),
                        emptySet()
                    )
                )
                val duplicateJid1 = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(1, VIDEO, msid = "differentMsid")),
                        emptySet()
                    )
                )
                val duplicateJid2 = ConferenceSourceMap(
                    jid2,
                    EndpointSourceSet(
                        setOf(Source(1, VIDEO, msid = "differentMsid")),
                        emptySet()
                    )
                )

                conferenceSources.tryToAdd(jid1, source) shouldBe source
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, duplicateJid1)
                }
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid2, duplicateJid2)
                }
            }
            context("testMSIDDuplicate") {
                val source1 = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(1, AUDIO, cname = "cname1", msid = "stream1 track1")),
                        emptySet()
                    )
                )
                val source2 = ConferenceSourceMap(
                    jid1,
                    EndpointSourceSet(
                        setOf(Source(2, AUDIO, cname = "cname1", msid = "stream1 track1")),
                        emptySet()
                    )
                )
//                Assert.assertEquals(
//                    "Not grouped SSRC 3 has conflicting"
//                            + " MSID 'stream2 track2' with 2",
//                    exc.message
//                )

                context("Separate adds") {
                    conferenceSources.tryToAdd(jid1, source1) shouldBe source1
                    shouldThrow<ValidationFailedException> {
                        conferenceSources.tryToAdd(jid1, source2)
                    }
                }
                context("Single add") {
                    source1.add(source2)
                    shouldThrow<ValidationFailedException> {
                        conferenceSources.tryToAdd(jid1, source1)
                    }
                }
            }
            context("testMSIDMismatchInTheSameGroup") {
                val sources = createSourcesForEndpoint(jid1)
                val replacedSources = sources[jid1]!!.sources.map {
                    // Replace the source with SSRC=1 with one with different cname and msid
                    if (it.ssrc == 1L)
                        Source(1, VIDEO, cname = "differentCname", msid = "different msid")
                    else
                        it
                }.toSet()

//            String errorMsg = exc.getMessage();
//            assertTrue(
//                "Invalid message (constant needs update ?): " + errorMsg,
//                errorMsg.startsWith(
//                    "MSID mismatch detected "
//                        + "in group SourceGroup[FID, ssrc=10, ssrc=20, ]"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(
                        jid1, ConferenceSourceMap(jid1, replacedSources, sources[jid1]!!.ssrcGroups)
                    )
                }
            }

            context("testMSIDMismatchInTheSameGroup") {
                val cname = "cname"
                val msid = "msid"
                val sources = setOf(
                    Source(1, VIDEO, cname = cname, msid = msid),
                    Source(2, VIDEO, cname = cname, msid = msid),
                    Source(3, VIDEO, cname = cname, msid = msid),
                    Source(4, VIDEO, cname = cname, msid = "different-msid")
                )
                val groups = setOf(
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 4))
                )

//            String errorMsg = exc.getMessage();
//            assertTrue(
//                "Invalid message (constant needs update ?): " + errorMsg,
//                errorMsg.startsWith(
//                    "MSID conflict across FID groups: vstream vtrack,"
//                        + " SourceGroup[FID, ssrc=30, ssrc=40, ]@")
//                    && errorMsg.contains(
//                        " conflicts with group SourceGroup"
//                            + "[FID, ssrc=10, ssrc=20, ]@"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, sources, groups))
                }
            }
            context("testMsidConflictFidGroups") {
                val cname = "cname"
                val msid = "msid"
                val sources = setOf(
                    Source(1, VIDEO, cname = cname, msid = msid),
                    Source(2, VIDEO, cname = cname, msid = msid),
                    Source(3, VIDEO, cname = cname, msid = msid),
                    Source(4, VIDEO, cname = cname, msid = "different-msid")
                )
                val groups = setOf(
                    SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 3)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 4))
                )
//            String errorMsg = exc.getMessage();
//            assertTrue(
//                "Invalid message (constant needs update ?): " + errorMsg,
//                errorMsg.startsWith(
//                    "MSID mismatch detected in group "
//                        + "SourceGroup[FID, ssrc=30, ssrc=40, ]"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, sources, groups))
                }
            }
            context("testMsidConflictSimGroups") {
                val cname = "cname"
                val msid = "msid"
                val sources = setOf(
                    Source(1, VIDEO, cname = cname, msid = msid),
                    Source(2, VIDEO, cname = cname, msid = msid),
                    Source(3, VIDEO, cname = cname, msid = msid),
                    Source(4, VIDEO, cname = cname, msid = msid)
                )
                val groups = setOf(
                    SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2)),
                    SsrcGroup(SsrcGroupSemantics.Sim, listOf(3, 4))
                )
//            String errorMsg = exc.getMessage();
//            assertTrue(
//                "Invalid message (constant needs update ?): " + errorMsg,
//                errorMsg.startsWith(
//                    "MSID conflict across SIM groups: vstream vtrack, ssrc=30"
//                        + " conflicts with group Simulcast[ssrc=10,ssrc=20,]"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, sources, groups))
                }
            }
            context("testNoMsidSimGroup") {
                val cname = "cname"
                val sources = setOf(
                    Source(1, VIDEO, cname = cname, msid = null),
                    Source(2, VIDEO, cname = cname, msid = null),
                    Source(3, VIDEO, cname = cname, msid = null)
                )
                val groups = setOf(
                    SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3)),
                )
//            String errorMsg = exc.getMessage();
//            assertTrue(
//                    "Invalid message (constant needs update ?): " + errorMsg,
//                    errorMsg.startsWith(
//                            "Grouped ssrc=10 has no 'msid'"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, sources, groups))
                }
            }
            context("testTrackMismatchInTheSameGroup") {
                val sources = createSourcesForEndpoint(jid1)
                val replacedSources = sources[jid1]!!.sources.map {
                    // Replace the source with SSRC=1 with one with different cname and msid
                    if (it.ssrc == 1L)
                        Source(1, VIDEO, cname = "differentCname", msid = "different msid")
                    else
                        it
                }.toSet()

//            String errorMsg = exc.getMessage();
//            assertTrue(
//                "Invalid message (constant needs update ?): " + errorMsg,
//                errorMsg.startsWith(
//                    "MSID mismatch detected "
//                        + "in group SourceGroup[FID, ssrc=10, ssrc=20, ]"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(
                        jid1, ConferenceSourceMap(jid1, replacedSources, sources[jid1]!!.ssrcGroups)
                    )
                }
            }
            context("testSSRCLimit") {
                val sources = setOf(
                    Source(1, AUDIO),
                    Source(2, AUDIO),
                    Source(3, AUDIO),
                    Source(4, AUDIO),
                    Source(5, AUDIO),
                    Source(6, AUDIO)
                )

                // TODO set limit to 4
                // val added = conferenceSources.add(sources)
                // TODO: should we fail with an exception?
                // added.sources.size shouldBe 4
            }
            context("testEmptyGroup") {
                val endpointSources = createSourcesForEndpoint(jid1)
                val emptySimGroup = SsrcGroup(SsrcGroupSemantics.Sim, emptyList())
                val emptyFidGroup = SsrcGroup(SsrcGroupSemantics.Fid, emptyList())

                val endpointSourceSet = endpointSources[jid1]!!

                val added = conferenceSources.tryToAdd(
                    jid1,
                    ConferenceSourceMap(
                        jid1,
                        endpointSourceSet.sources,
                        setOf(
                            *(endpointSourceSet.ssrcGroups.toTypedArray()),
                            emptyFidGroup,
                            emptySimGroup
                        )
                    )
                )

                // Empty groups should be ignored
                added[jid1]!!.ssrcGroups shouldBe endpointSourceSet.ssrcGroups
            }
            context("testGroupedSSRCNotFound") {
                val cname = "cname"
                val msid = "msid"
                val sources = setOf(
                    Source(1, VIDEO, cname = cname, msid = msid)
                )
                val groups = setOf(
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                )
//            String errorMsg = e.getMessage();
//            assertTrue(
//                    "Invalid message (constant needs update ?): " + errorMsg,
//                    errorMsg.startsWith(
//                        "Source ssrc=2 not found in 'video' for group:"
//                            + " SourceGroup[FID, ssrc=1, ssrc=2, ]"));
                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, sources, groups))
                }
            }
            context("testDuplicatedGroups") {
                val cname = "cname"
                val msid = "msid"
                val sources = setOf(
                    Source(1, VIDEO, cname = cname, msid = msid),
                    Source(2, VIDEO, cname = cname, msid = msid)
                )
                val groups = setOf(
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2))
                )

                val added = conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, sources, groups))
                added[jid1]!!.sources shouldBe sources
                added[jid1]!!.ssrcGroups shouldBe setOf(SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 2)))
            }
            context("testStateBrokenByRemoval") {
                val cname = "videocname"
                val msid = "vstream vtarck"

                val videoSources = listOf(
                    Source(1, VIDEO, cname = cname, msid = msid),
                    Source(2, VIDEO, cname = cname, msid = msid),
                    Source(3, VIDEO, cname = cname, msid = msid),
                    Source(4, VIDEO, cname = cname, msid = msid),
                    Source(5, VIDEO, cname = cname, msid = msid),
                    Source(6, VIDEO, cname = cname, msid = msid)
                )

                val ssrcGroups = listOf(
                    SsrcGroup(SsrcGroupSemantics.Sim, listOf(1, 2, 3)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(1, 4)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(2, 5)),
                    SsrcGroup(SsrcGroupSemantics.Fid, listOf(3, 6)),
                )
                conferenceSources.tryToAdd(jid1, ConferenceSourceMap(jid1, videoSources.toSet(), ssrcGroups.toSet()))

                val sourcesToRemove = setOf(
                    videoSources[0], videoSources[1]
                )
                val groupsToRemove = setOf(
                    ssrcGroups[0],
                    ssrcGroups[1]
                )

                shouldThrow<ValidationFailedException> {
                    conferenceSources.tryToRemove(jid1, ConferenceSourceMap(jid1, sourcesToRemove, groupsToRemove))
                }
            }
        }
    }
}

private fun createSourcesForEndpoint(owner: Jid, ssrcBase: Long = 1): ConferenceSourceMap {
    val cname = "videocname-$owner"
    val msid = "vstream-$owner vtarck"

    val videoSources = listOf(
        Source(ssrcBase, VIDEO, cname = cname, msid = msid),
        Source(ssrcBase + 1, VIDEO, cname = cname, msid = msid),
        Source(ssrcBase + 2, VIDEO, cname = cname, msid = msid),
        Source(ssrcBase + 3, VIDEO, cname = cname, msid = msid),
        Source(ssrcBase + 4, VIDEO, cname = cname, msid = msid),
        Source(ssrcBase + 5, VIDEO, cname = cname, msid = msid)
    )

    return ConferenceSourceMap(
        owner to EndpointSourceSet(
            videoSources.toSet(),
            setOf(
                SsrcGroup(SsrcGroupSemantics.Sim, listOf(ssrcBase, ssrcBase + 1, ssrcBase + 2)),
                SsrcGroup(SsrcGroupSemantics.Fid, listOf(ssrcBase, ssrcBase + 3)),
                SsrcGroup(SsrcGroupSemantics.Fid, listOf(ssrcBase + 1, ssrcBase + 4)),
                SsrcGroup(SsrcGroupSemantics.Fid, listOf(ssrcBase + 2, ssrcBase + 5))
            )
        )
    )
}
