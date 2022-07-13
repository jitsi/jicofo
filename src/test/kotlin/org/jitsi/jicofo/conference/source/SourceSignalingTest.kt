package org.jitsi.jicofo.conference.source

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.jicofo.conference.AddOrRemove.Add
import org.jitsi.jicofo.conference.AddOrRemove.Remove
import org.jitsi.jicofo.conference.SourceSignaling
import org.jitsi.utils.MediaType
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.impl.JidCreate

class SourceSignalingTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val jid1 = JidCreate.from("jid1")
        val e1a = Source(1, MediaType.AUDIO)
        val e1v = Source(11, MediaType.VIDEO)
        val s1 = ConferenceSourceMap(jid1 to EndpointSourceSet(setOf(e1a, e1v))).unmodifiable

        val jid2 = JidCreate.from("jid2")
        val e2a = Source(2, MediaType.AUDIO)
        val e2v = Source(22, MediaType.VIDEO)
        val s2 = ConferenceSourceMap(jid2 to EndpointSourceSet(setOf(e2a, e2v))).unmodifiable

        val e2a2 = Source(222, MediaType.AUDIO)
        val e2v2 = Source(2222, MediaType.VIDEO)
        val s2new = ConferenceSourceMap(jid2 to EndpointSourceSet(setOf(e2a2, e2v2))).unmodifiable

        val jid3 = JidCreate.from("jid3")
        val e3a = Source(3, MediaType.AUDIO)
        val s3 = ConferenceSourceMap(jid3 to EndpointSourceSet(e3a)).unmodifiable

        val jid4 = JidCreate.from("jid3")
        val e4a1 = Source(4, MediaType.AUDIO)
        val e4v1a = Source(43, MediaType.VIDEO, name = "e4-v1")
        val e4v1b = Source(44, MediaType.VIDEO, name = "e4-v1")
        val e4v1c = Source(45, MediaType.VIDEO, name = "e4-v1")
        val e4ss1a = Source(46, MediaType.VIDEO, name = "e4-ss1", videoType = VideoType.Desktop)
        val e4ss1b = Source(47, MediaType.VIDEO, name = "e4-ss1", videoType = VideoType.Desktop)
        val e4ss1c = Source(48, MediaType.VIDEO, name = "e4-ss1", videoType = VideoType.Desktop)
        val s4audio = ConferenceSourceMap(jid4 to EndpointSourceSet(e4a1))
        val s4video = ConferenceSourceMap(
            jid4 to EndpointSourceSet(
                setOf(e4v1a, e4v1b, e4v1c),
                setOf()
            )
        )
        val s4ss = ConferenceSourceMap(
            jid4 to EndpointSourceSet(
                setOf(e4ss1a, e4ss1b, e4ss1c),
                setOf()
            )
        )
        val e4sources = setOf(e4a1, e4v1a, e4v1b, e4v1c, e4ss1a, e4ss1b, e4ss1c)
        val e4groups = setOf(
            SsrcGroup(SsrcGroupSemantics.Sim, listOf(43, 44, 45), MediaType.VIDEO),
            SsrcGroup(SsrcGroupSemantics.Sim, listOf(46, 47, 48), MediaType.VIDEO)
        )
        val s4 = ConferenceSourceMap(jid4 to EndpointSourceSet(e4sources, e4groups))

        context("Queueing remote sources") {
            val sourceSignaling = SourceSignaling()
            sourceSignaling.update().shouldBeEmpty()

            context("Resetting") {
                sourceSignaling.addSources(s1)
                sourceSignaling.reset(ConferenceSourceMap())
                sourceSignaling.update().shouldBeEmpty()

                sourceSignaling.addSources(s1)
                sourceSignaling.reset(s2)
                sourceSignaling.update().shouldBeEmpty()
            }

            context("Adding a single source") {
                sourceSignaling.addSources(s1)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Add
                    it[0].sources.toMap() shouldBe s1.toMap()
                }
            }

            context("Adding multiple sources") {
                // Consecutive source-adds should be merged.
                sourceSignaling.addSources(s1)
                sourceSignaling.addSources(s2)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Add
                    it[0].sources.toMap() shouldBe (s1 + s2).toMap()
                }
            }

            context("Adding multiple sources in multiple API calls") {
                // Consecutive source-adds should be merged.
                sourceSignaling.addSources(s1)
                sourceSignaling.addSources(s2)
                sourceSignaling.addSources(s2new)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Add
                    it[0].sources.toMap() shouldBe (s1 + s2 + s2new).toMap()
                }
            }

            context("Adding and removing sources") {
                // A source-remove after a series of source-adds should be a new entry.
                sourceSignaling.addSources(s1)
                sourceSignaling.addSources(s2)
                sourceSignaling.addSources(s2new)
                sourceSignaling.removeSources(s2new)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Add
                    it[0].sources.toMap() shouldBe (s1 + s2).toMap()
                }
            }

            context("Adding, removing, then adding again") {
                // A source-add following source-remove should be a new entry.
                sourceSignaling.addSources(s1)
                sourceSignaling.addSources(s2)
                sourceSignaling.addSources(s2new)
                sourceSignaling.removeSources(s2new)
                sourceSignaling.addSources(s3)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Add
                    it[0].sources.toMap() shouldBe (s1 + s2 + s3).toMap()
                }
            }

            context("Adding and removing the same source") {
                sourceSignaling.addSources(s1)
                sourceSignaling.removeSources(s1)
                sourceSignaling.update().shouldBeEmpty()
            }

            sourceSignaling.debugState.shouldBeValidJson()
        }
        listOf(true, false).forEach { supportsReceivingMultipleStreams ->
            context("Filtering (supportsReceivingMultipleStreams=$supportsReceivingMultipleStreams)") {
                val sourceSignaling = SourceSignaling(audio = true, video = false, stripSimulcast = true)

                sourceSignaling.reset(s1 + s2).shouldBe(
                    ConferenceSourceMap(
                        jid1 to EndpointSourceSet(e1a),
                        jid2 to EndpointSourceSet(e2a)
                    )
                )

                sourceSignaling.addSources(s2new)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Add
                    it[0].sources shouldBe ConferenceSourceMap(jid2 to EndpointSourceSet(e2a2))
                }

                sourceSignaling.removeSources(s1)
                sourceSignaling.update().let {
                    it.size shouldBe 1
                    it[0].action shouldBe Remove
                    it[0].sources shouldBe ConferenceSourceMap(jid1 to EndpointSourceSet(e1a))
                }

                sourceSignaling.addSources(s1)
                sourceSignaling.removeSources(s1)
                sourceSignaling.update().shouldBeEmpty()

                sourceSignaling.removeSources(s2)
                sourceSignaling.addSources(s2)
                sourceSignaling.update().shouldBeEmpty()
            }
        }
        context("Filtering with no support for multiple streams") {
            val sourceSignaling = SourceSignaling(
                audio = true,
                video = true,
                stripSimulcast = true,
                supportsReceivingMultipleStreams = false
            )

            sourceSignaling.addSources(s1)
            sourceSignaling.update().let {
                it.size shouldBe 1
                it[0].action shouldBe Add
                it[0].sources shouldBe s1
            }

            // When camera and SS are added together, it should only add SS
            sourceSignaling.addSources(s4audio + s4video + s4ss)
            sourceSignaling.update().let {
                it.size shouldBe 1
                it[0].action shouldBe Add
                it[0].sources shouldBe (s4audio + s4ss).stripSimulcast()
            }

            // It should only remove the sources that were added (SS)
            sourceSignaling.removeSources(s4audio + s4video + s4ss)
            sourceSignaling.update().let {
                it.size shouldBe 1
                it[0].action shouldBe Remove
                it[0].sources shouldBe (s4audio + s4ss).stripSimulcast()
            }

            sourceSignaling.addSources(s4audio + s4ss)
            sourceSignaling.update().let {
                it.size shouldBe 1
                it[0].action shouldBe Add
                it[0].sources shouldBe (s4audio + s4ss).stripSimulcast()
            }

            // SS exists, camera should not be added.
            sourceSignaling.addSources(s4video)
            sourceSignaling.update().shouldBeEmpty()

            // When SS is removed and only camera is left, camera should be added
            sourceSignaling.removeSources(s4ss)
            sourceSignaling.update().let { sourceUpdates ->
                sourceUpdates.size shouldBe 2

                val remove = sourceUpdates.find { it.action == Remove }
                remove shouldNotBe null
                remove!!.sources shouldBe s4ss.stripSimulcast()

                val add = sourceUpdates.find { it.action == Add }
                add shouldNotBe null
                add!!.sources shouldBe s4video.stripSimulcast()
            }

            // When SS is added and camera exists, it should be replaced.
            sourceSignaling.addSources(s4ss)
            sourceSignaling.update().let { sourceUpdates ->
                sourceUpdates.size shouldBe 2

                val remove = sourceUpdates.find { it.action == Remove }
                remove shouldNotBe null
                remove!!.sources shouldBe s4video.stripSimulcast()

                val add = sourceUpdates.find { it.action == Add }
                add shouldNotBe null
                add!!.sources shouldBe s4ss.stripSimulcast()
            }
        }
    }
}

fun JSONObject.shouldBeValidJson() = JSONParser().parse(this.toJSONString())
