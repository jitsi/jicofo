package org.jitsi.jicofo

import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.protocol.xmpp.util.MediaSourceGroupMap
import org.jitsi.protocol.xmpp.util.MediaSourceMap
import org.jitsi.protocol.xmpp.util.SourceGroup
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate
import java.time.Clock
import java.time.Duration

class SsrcValidationPerfTest : ShouldSpec() {
    init {
        xcontext("Add/Remove a source to a large conference") {
            val (conferenceSources, conferenceSourceGroups) = createSources(500)
            logger.warn("conference audio sources count: ${conferenceSources.getSourcesForMedia("audio").size}")
            logger.warn("conference video sources count: ${conferenceSources.getSourcesForMedia("video").size}")

            val endpointId = "newendpoint"
            val (sourcesToAdd, sourceGroupsToAdd) = createEndpointSources(endpointId)

            measureAndLog("Single add") {
                val validator = SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                val added = validator.tryAddSourcesAndGroups(sourcesToAdd, sourceGroupsToAdd)
            }

            measureAndLog("Single remove") {
                val validator = SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                val removed = validator.tryRemoveSourcesAndGroups(sourcesToAdd, sourceGroupsToAdd)
            }
        }
        xcontext("Sequential add/remove") {
            val conferenceSources = MediaSourceMap()
            val conferenceSourceGroups = MediaSourceGroupMap()

            val allEndpointSources = HashMap<String, SG>()

            measureAndLog("Adding all endpoints") {
                for (i in 0 until 500) {
                    measureAndLog("Adding endpoint $i") {
                        val endpointId = "endpoint-$i"
                        val sg = createEndpointSources(endpointId)
                        val (endpointSources, endpointGroups) = sg
                        allEndpointSources[endpointId] = sg

                        val validator = SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                        val added = validator.tryAddSourcesAndGroups(endpointSources, endpointGroups)
                        conferenceSources.add(added[0] as MediaSourceMap)
                        conferenceSourceGroups.add(added[1] as MediaSourceGroupMap)
                    }
                }
            }

            measureAndLog("Removing all endpoints") {
                for (i in 0 until 500) {
                    measureAndLog("Removing endpoint $i") {
                        val endpointId = "endpoint-$i"
                        val (endpointSources, endpointGroups) = allEndpointSources[endpointId]!!

                        val validator = SSRCValidator(endpointId, conferenceSources, conferenceSourceGroups, 20, logger)
                        val removed = validator.tryRemoveSourcesAndGroups(endpointSources, endpointGroups)
                        conferenceSources.remove(removed[0] as MediaSourceMap)
                        conferenceSourceGroups.remove(removed[1] as MediaSourceGroupMap)
                    }
                }
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
