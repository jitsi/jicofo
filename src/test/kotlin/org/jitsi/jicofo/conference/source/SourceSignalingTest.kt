package org.jitsi.jicofo.conference.source

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
        context("Filtering") {
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
}

fun JSONObject.shouldBeValidJson() = JSONParser().parse(this.toJSONString())
