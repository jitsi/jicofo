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
package org.jitsi.jicofo

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration

class SsrcValidationPerfTest : ShouldSpec() {
    init {
        val numEndpoints = 500
        xcontext("ValidatingConferenceSourceMap") {
            context("Add/Remove a source to a large conference") {
                val conferenceSources = createConferenceSourceMap(numEndpoints)
                conferenceSources.size shouldBe numEndpoints

                val newEndpointSourceSet = createEndpointSourceSet("new-endpoint", ssrcCount)
                val newEndpointId = "new-endpoint"
                ssrcCount += newEndpointSourceSet.sources.size

                var added: EndpointSourceSet? = null
                measureAndLog("Single add") {
                    added = conferenceSources.tryToAdd(newEndpointId, newEndpointSourceSet)
                }
                added shouldBe newEndpointSourceSet
                conferenceSources.size shouldBe numEndpoints + 1

                var removed: EndpointSourceSet? = null
                measureAndLog("Single remove") {
                    removed = conferenceSources.tryToRemove(newEndpointId, newEndpointSourceSet)
                }
                conferenceSources.size shouldBe numEndpoints
                removed shouldBe newEndpointSourceSet
            }
            context("Sequential add/remove") {
                val conferenceSources = ValidatingConferenceSourceMap(
                    ConferenceConfig.config.maxSsrcsPerUser,
                    ConferenceConfig.config.maxSsrcGroupsPerUser
                )
                measureAndLog("Adding all endpoints") {
                    for (i in 0 until numEndpoints) {
                        val endpointId = "endpoint-$i"
                        val sourceSet = createEndpointSourceSet(endpointId, ssrcCount)
                        ssrcCount += sourceSet.sources.size
                        measureAndLog("Adding endpoint $i") {
                            conferenceSources.tryToAdd(endpointId, sourceSet)
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

    private fun createConferenceSourceMap(numEndpoints: Int) = ValidatingConferenceSourceMap(
        ConferenceConfig.config.maxSsrcsPerUser,
        ConferenceConfig.config.maxSsrcGroupsPerUser
    ).apply {
        for (i in 0 until numEndpoints) {
            val endpointSourceSet = createEndpointSourceSet(i.toString(), ssrcCount)
            ssrcCount += endpointSourceSet.sources.size
            add("endpoint$i", endpointSourceSet)
        }
    }

    private val logger = createLogger()
    private fun measureAndLog(name: String, clock: Clock = Clock.systemUTC(), block: () -> Unit) {
        logger.info("$name took ${measure(clock, block).toMillis()}")
    }
}

fun measure(clock: Clock = Clock.systemUTC(), block: () -> Unit): Duration {
    val start = clock.instant()
    block()
    return Duration.between(start, clock.instant())
}
