package org.jitsi.jicofo

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.protocol.xmpp.util.MediaSourceGroupMap
import org.jitsi.protocol.xmpp.util.MediaSourceMap
import org.jitsi.protocol.xmpp.util.SourceGroup
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate
import java.time.Clock
import java.time.Duration

class SsrcValidationPerfTest : ShouldSpec() {
    init {
        val numEndpoints = 500
        xcontext("SSRCValidator") {
            context("Add/Remove a source to a large conference") {
                val (conferenceSources, conferenceSourceGroups) = createSources(numEndpoints)
                conferenceSources.getSourcesForMedia("audio").size shouldBe numEndpoints
                conferenceSources.getSourcesForMedia("video").size shouldBe numEndpoints

                val endpointId = "newendpoint"
                val (sourcesToAdd, sourceGroupsToAdd) = createEndpointSources(endpointId)

                measureAndLog("Single add") {
                    val validator = SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                    val added = validator.tryAddSourcesAndGroups(sourcesToAdd, sourceGroupsToAdd)
                }
                conferenceSources.getSourcesForMedia("audio").size shouldBe numEndpoints + 1
                conferenceSources.getSourcesForMedia("video").size shouldBe numEndpoints + 1

                measureAndLog("Single remove") {
                    val validator = SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                    val removed = validator.tryRemoveSourcesAndGroups(sourcesToAdd, sourceGroupsToAdd)
                }
                conferenceSources.getSourcesForMedia("audio").size shouldBe numEndpoints
                conferenceSources.getSourcesForMedia("video").size shouldBe numEndpoints
            }
            context("Sequential add/remove") {
                val conferenceSources = MediaSourceMap()
                val conferenceSourceGroups = MediaSourceGroupMap()

                val allEndpointSources = HashMap<String, SG>()

                measureAndLog("Adding all endpoints") {
                    for (i in 0 until 500) {
                        val endpointId = "endpoint-$i"
                        val sg = createEndpointSources(endpointId)
                        val (endpointSources, endpointGroups) = sg
                        allEndpointSources[endpointId] = sg

                        measureAndLog("Adding endpoint $i") {
                            val validator =
                                SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                            val added = validator.tryAddSourcesAndGroups(endpointSources, endpointGroups)
                            conferenceSources.add(added[0] as MediaSourceMap)
                            conferenceSourceGroups.add(added[1] as MediaSourceGroupMap)
                        }
                    }
                }

                measureAndLog("Removing all endpoints") {
                    for (i in 0 until 500) {
                        val endpointId = "endpoint-$i"
                        val (endpointSources, endpointGroups) = allEndpointSources[endpointId]!!
                        measureAndLog("Removing endpoint $i") {
                            val validator =
                                SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                            val removed = validator.tryRemoveSourcesAndGroups(endpointSources, endpointGroups)
                            conferenceSources.remove(removed[0] as MediaSourceMap)
                            conferenceSourceGroups.remove(removed[1] as MediaSourceGroupMap)
                        }
                    }
                }
            }
        }
        xcontext("ValidatingConferenceSourceMap") {
            context("Add/Remove a source to a large conference2") {
                val conferenceSources = createConferenceSourceMap(numEndpoints)
                conferenceSources.size shouldBe numEndpoints

                val newEndpointSourceSet = createEndpointSourceSet("new-endpoint", ssrcCount)
                val newEndpointJid = JidCreate.fullFrom("$jidPrefix/new-endpoint")
                ssrcCount += newEndpointSourceSet.sources.size

                var added: ConferenceSourceMap? = null
                measureAndLog("Single add") {
                    added = conferenceSources.tryToAdd(newEndpointJid, newEndpointSourceSet)
                }
                added shouldBe ConferenceSourceMap(newEndpointJid to newEndpointSourceSet)
                conferenceSources.size shouldBe numEndpoints + 1

                var removed: ConferenceSourceMap? = null
                measureAndLog("Single remove") {
                    removed = conferenceSources.tryToRemove(newEndpointJid, newEndpointSourceSet)
                }
                conferenceSources.size shouldBe numEndpoints
                removed shouldBe ConferenceSourceMap(newEndpointJid to newEndpointSourceSet)
            }
            context("Sequential add/remove2") {
                val conferenceSources = ValidatingConferenceSourceMap()
                measureAndLog("Adding all endpoints") {
                    for (i in 0 until numEndpoints) {
                        val endpointId = "endpoint-$i"
                        val endpointJid = JidCreate.fullFrom("$jidPrefix/$endpointId")
                        val sourceSet = createEndpointSourceSet(endpointId, ssrcCount)
                        ssrcCount += sourceSet.sources.size
                        measureAndLog("Adding endpoint $i") {
                            conferenceSources.tryToAdd(endpointJid, sourceSet)
                        }
                    }
                }
                conferenceSources.size shouldBe numEndpoints
                measureAndLog("Removing all endpoints") {
                    var i = 0
                    while (conferenceSources.isNotEmpty()) {
                        val sourceToRemove = conferenceSources.entries.first()
                        measureAndLog("Removing endpoint $i") {
                            conferenceSources.tryToRemove(sourceToRemove.key, sourceToRemove.value)
                        }
                        i++
                    }
                }
                conferenceSources.size shouldBe 0
            }
        }
    }

    private var ssrcCount = 1L
    private val jidPrefix = "confname@conference.example.com/"
    private fun createEndpointSources(endpointId: String): SG {
        val s = MediaSourceMap()
        val g = MediaSourceGroupMap()

        s.addSources(
            "audio",
            listOf(
                SourcePacketExtension().apply {
                    ssrc = ssrcCount++
                    addChildExtension(
                        SSRCInfoPacketExtension().apply {
                            owner = JidCreate.fullFrom("$jidPrefix$endpointId")
                        }
                    )
                    addChildExtension(
                        ParameterPacketExtension("msid", "msid-$endpointId")
                    )
                }
            )
        )

        val base = ssrcCount
        val videoSources = mutableListOf<SourcePacketExtension>()
        for (i in 0 until 6) {
            videoSources.add(
                SourcePacketExtension().apply {
                    ssrc = base + i
                    addChildExtension(
                        SSRCInfoPacketExtension().apply {
                            owner = JidCreate.fullFrom("$jidPrefix$endpointId")
                        }
                    )
                    addChildExtension(
                        ParameterPacketExtension("msid", "msid-$endpointId")
                    )
                    s.addSource("video", this)
                }
            )
        }

        g.addSourceGroup(
            "video",
            SourceGroup("SIM", listOf(videoSources[0].copy(), videoSources[1].copy(), videoSources[2].copy()))
        )
        g.addSourceGroup("video", SourceGroup("FID", listOf(videoSources[0].copy(), videoSources[3].copy())))
        g.addSourceGroup("video", SourceGroup("FID", listOf(videoSources[1].copy(), videoSources[4].copy())))
        g.addSourceGroup("video", SourceGroup("FID", listOf(videoSources[2].copy(), videoSources[5].copy())))

        ssrcCount += 6
        return SG(s, g)
    }

    private fun createEndpointSourceSet(endpointId: String, ssrcBase: Long) = EndpointSourceSet(
        setOf(
            Source(ssrcBase, MediaType.VIDEO, msid = "msid-$endpointId"),
            Source(ssrcBase + 1, MediaType.VIDEO, msid = "msid-$endpointId"),
            Source(ssrcBase + 2, MediaType.VIDEO, msid = "msid-$endpointId"),
            Source(ssrcBase + 3, MediaType.VIDEO, msid = "msid-$endpointId"),
            Source(ssrcBase + 4, MediaType.VIDEO, msid = "msid-$endpointId"),
            Source(ssrcBase + 5, MediaType.VIDEO, msid = "msid-$endpointId"),
            Source(ssrcBase + 6, MediaType.AUDIO, msid = "msid-$endpointId")
        ),
        setOf(
            SsrcGroup(SsrcGroupSemantics.Sim, listOf(ssrcBase, ssrcBase + 1, ssrcBase + 2)),
            SsrcGroup(SsrcGroupSemantics.Fid, listOf(ssrcBase, ssrcBase + 3)),
            SsrcGroup(SsrcGroupSemantics.Fid, listOf(ssrcBase + 1, ssrcBase + 4)),
            SsrcGroup(SsrcGroupSemantics.Fid, listOf(ssrcBase + 2, ssrcBase + 5)),
        )
    )

    private fun createConferenceSourceMap(numEndpoints: Int) = ValidatingConferenceSourceMap().apply {
        for (i in 0 until numEndpoints) {
            val endpointSourceSet = createEndpointSourceSet(i.toString(), ssrcCount)
            ssrcCount += endpointSourceSet.sources.size
            add(ConferenceSourceMap(JidCreate.fullFrom("$jidPrefix/endpoint$i") to endpointSourceSet))
        }
    }

    private fun createSources(numEndpoints: Int): SG {
        val sources = MediaSourceMap()
        val groups = MediaSourceGroupMap()

        for (i in 0 until numEndpoints) {
            val (s, g) = createEndpointSources("endpoint-$i")
            sources.add(s)
            groups.add(g)
        }
        return SG(sources, groups)
    }

    private val logger = createLogger()
    private fun measureAndLog(name: String, clock: Clock = Clock.systemUTC(), block: () -> Unit) {
        logger.info("$name took ${measure(clock, block).toMillis()}")
    }
}

data class SG(val sources: MediaSourceMap, val groups: MediaSourceGroupMap)

fun measure(clock: Clock = Clock.systemUTC(), block: () -> Unit): Duration {
    val start = clock.instant()
    block()
    return Duration.between(start, clock.instant())
}
