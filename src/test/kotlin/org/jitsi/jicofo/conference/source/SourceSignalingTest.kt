package org.jitsi.jicofo.conference.source

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.AddOrRemove.Add
import org.jitsi.jicofo.conference.SourceSignaling
import org.jitsi.utils.MediaType
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.impl.JidCreate

class SourceSignalingTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val jid1 = JidCreate.from("jid1")
        val s1 = ConferenceSourceMap(jid1 to EndpointSourceSet(Source(1, MediaType.AUDIO))).unmodifiable

        val jid2 = JidCreate.from("jid2")
        val s2 = ConferenceSourceMap(jid2 to EndpointSourceSet(Source(2, MediaType.AUDIO))).unmodifiable
        val s2new = ConferenceSourceMap(jid2 to EndpointSourceSet(setOf(Source(222, MediaType.AUDIO)))).unmodifiable

        val jid3 = JidCreate.from("jid3")
        val s3 = ConferenceSourceMap(jid3 to EndpointSourceSet(Source(3, MediaType.AUDIO))).unmodifiable

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
    }
}
fun JSONObject.shouldBeValidJson() = JSONParser().parse(this.toJSONString())
