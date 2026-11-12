/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo.conference.translation

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.bridge.colibri.ColibriSessionManager
import org.jitsi.jicofo.conference.SyntheticSsrcMinter
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.ValidatingConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl

/**
 * Tests that [ConferenceTranslationManager] gives the synthetic sources new SSRCs when another bridge starts to
 * inject them: the single translator connect moved to another session, or a sender is re-invited in the per-source
 * mode.
 */
class ConferenceTranslationManagerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val sender = "aaaaaaaa"
    private val otherSender = "bbbbbbbb"
    private val conferenceSources = ValidatingConferenceSourceMap(20, 20).apply {
        add(sender, EndpointSourceSet(Source(1, MediaType.AUDIO, name = "$sender-a0")))
        add(otherSender, EndpointSourceSet(Source(2, MediaType.AUDIO, name = "$otherSender-a0")))
    }

    /** What the session manager reports as [ColibriSessionManager.translatorConnectMoves]. */
    private var moves = 0

    /** Whether the connect moves during the next `setTranslator`, as when its bridge fails during an apply. */
    private var moveDuringSetTranslator = false
    private val colibriSessionManager: ColibriSessionManager = mockk(relaxed = true) {
        every { translatorConnectMoves } answers { moves }
        every { setTranslator(any(), any(), any()) } answers {
            if (moveDuringSetTranslator) {
                moveDuringSetTranslator = false
                moves++
            }
        }
    }

    private val manager =
        ConferenceTranslationManager(conferenceSources, SyntheticSsrcMinter(conferenceSources), LoggerImpl("test"))

    private fun syntheticSource(id: String = sender): Source = conferenceSources[id]!!.sources.single { it.synthetic }

    private fun setRequests() = manager.setRequests(
        mapOf(sender to listOf("en"), otherSender to listOf("en")),
        null,
        colibriSessionManager,
        "meeting"
    )

    private fun reapply(): Source {
        manager.reapply(colibriSessionManager, "meeting")
        return syntheticSource()
    }

    init {
        context("The synthetic source of a sender") {
            setRequests()
            val first = syntheticSource()

            should("have the name of the base source and the language") {
                first.name shouldBe "$sender-a0.en"
            }
            should("keep its SSRC while the translator connect does not move") {
                reapply() shouldBe first
            }
            context("when the translator connect moved to another session") {
                moves++
                val second = reapply()

                should("get a new SSRC under the same name") {
                    second.ssrc shouldNotBe first.ssrc
                    second.copy(ssrc = first.ssrc) shouldBe first
                }
                should("be signaled to the bridges with the new SSRC, and no longer with the old SSRC") {
                    verify {
                        colibriSessionManager.updateParticipant(
                            sender,
                            any(),
                            match { it.sources.contains(second) && !it.sources.contains(first) },
                            any(),
                            any()
                        )
                    }
                }
                should("keep the new SSRC until the next move") {
                    reapply() shouldBe second
                    moves++
                    reapply() shouldNotBe second
                }
            }
            context("when the translator connect moves while the requests are applied") {
                moveDuringSetTranslator = true
                val duringMove = reapply()

                should("keep its SSRC in that apply, which read the count of moves too early") {
                    duringMove shouldBe first
                }
                should("get a new SSRC in the apply that the event of the move causes") {
                    reapply().ssrc shouldNotBe first.ssrc
                }
            }
            context("when the connect moved while there were no requests") {
                manager.setRequests(emptyMap(), null, colibriSessionManager, "meeting")
                moves++
                setRequests()
                val second = syntheticSource()

                should("not change again in the next apply") {
                    reapply() shouldBe second
                }
            }
        }
        context("A sender that is re-invited and keeps its sources") {
            context("in the per-source mode") {
                setRequests()
                val first = syntheticSource()
                val firstOfOther = syntheticSource(otherSender)
                manager.senderReinvited(sender, colibriSessionManager, "meeting")

                should("get a new SSRC for its synthetic source under the same name") {
                    syntheticSource().ssrc shouldNotBe first.ssrc
                    syntheticSource().copy(ssrc = first.ssrc) shouldBe first
                }
                should("leave the synthetic sources of the other senders alone") {
                    syntheticSource(otherSender) shouldBe firstOfOther
                }
            }
            context("in the per-source mode, with no synthetic source") {
                manager.senderReinvited(sender, colibriSessionManager, "meeting")

                should("signal nothing") {
                    verify(exactly = 0) { colibriSessionManager.setTranslator(any(), any(), any()) }
                }
            }
            context("in the single-bridge mode") {
                withNewConfig("jicofo.translation.mode=single-bridge") {
                    setRequests()
                    val first = syntheticSource()
                    manager.senderReinvited(sender, colibriSessionManager, "meeting")

                    should("keep the SSRC: the bridge that injects the source did not change") {
                        syntheticSource() shouldBe first
                    }
                }
            }
        }
    }
}
