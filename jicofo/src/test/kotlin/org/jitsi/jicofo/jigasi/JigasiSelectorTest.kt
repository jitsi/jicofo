/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate

class JigasiSelectorTest : ShouldSpec() {
    init {
        val r1 = "region1"
        val r2 = "region2"
        val r3 = "region3"
        val selector = JigasiTestDetector(localRegion = r2)
        var numberOfInstances = 1
        fun nextJid() = JidCreate.entityFullFrom("brewery@example.com/jigasi-${numberOfInstances++}")

        context("Jigasi selection") {
            selector.selectSipJigasi() shouldBe null
            selector.selectTranscriber() shouldBe null
            val sipJigasi1 = nextJid().also { selector.updateStats(it, participants = 1) }
            val sipJigasi2 = nextJid().also { selector.updateStats(it, participants = 2) }
            selector.selectTranscriber() shouldBe null
            selector.selectSipJigasi() shouldNotBe null
            val transcriber1 = nextJid().also {
                selector.updateStats(it, participants = 1, region = r1, sip = false, transcriber = true)
            }
            val transcriber2 = nextJid().also {
                selector.updateStats(it, participants = 2, region = r2, sip = false, transcriber = true)
            }
            selector.selectTranscriber() shouldNotBe null

            // graceful shutdown
            selector.updateStats(sipJigasi1, participants = 1, inGracefulShutdown = true)
            selector.selectSipJigasi() shouldBe sipJigasi2

            // select by preferred regions
            // should select based on participant as no region reported by instances
            selector.updateStats(sipJigasi1, participants = 1)
            selector.updateStats(sipJigasi2, participants = 2)
            selector.selectSipJigasi(preferredRegions = listOf(r2, r3)) shouldBe sipJigasi1

            selector.updateStats(sipJigasi1, participants = 1, region = r1)
            selector.updateStats(sipJigasi2, participants = 2, region = r2)

            // Select from a preferred region
            selector.selectSipJigasi(preferredRegions = listOf(r2, r3)) shouldBe sipJigasi2
            // With no jigasi in the preferred region, select from the local region
            selector.selectSipJigasi(preferredRegions = listOf(r3)) shouldBe sipJigasi2
            // With no jigasi in the preferred region, select based on participant count
            selector.updateStats(sipJigasi1, participants = 1, region = r2)
            selector.selectSipJigasi(preferredRegions = listOf(r3)) shouldBe sipJigasi1

            selector.updateStats(sipJigasi1, participants = 1, region = r1)

            // select by local region
            // no matching region, selects based on local region
            selector.selectSipJigasi(preferredRegions = listOf(r3)) shouldBe sipJigasi2
            selector.selectSipJigasi() shouldBe sipJigasi2

            // filter
            // should select from region2, but that is filtered so will select
            // based on participants -> jid1
            selector.selectSipJigasi(
                exclude = listOf(sipJigasi2),
                preferredRegions = listOf(r2, r3)
            ) shouldBe sipJigasi1

            // Both sip and transcriber should be selected based on local region over participant count
            selector.selectSipJigasi() shouldBe sipJigasi2
            selector.selectTranscriber() shouldBe transcriber2
            selector.selectTranscriber(preferredRegions = listOf(r2, r3)) shouldBe transcriber2

            // Move jid3 to the local region
            selector.updateStats(transcriber1, participants = 1, region = r2, sip = false, transcriber = true)
            selector.selectTranscriber() shouldBe transcriber1
            selector.selectTranscriber(preferredRegions = listOf(r2, r3)) shouldBe transcriber1

            // transcriber no matching region, select based on participants
            selector.selectTranscriber(preferredRegions = listOf(r3)) shouldBe transcriber1

            // transcriber no matching region, select based on participants, but
            // with filtered jid3(which has lowest number of participants)
            selector.selectTranscriber(
                exclude = listOf(transcriber1),
                preferredRegions = listOf(r3)
            ) shouldBe transcriber2
        }
    }
}

private class JigasiTestDetector(localRegion: String) : JigasiDetector(
    mockk(relaxed = true),
    mockk(relaxed = true),
    localRegion
) {
    /** Update stats for the instance with [jid]. Creates a new instance if necessary */
    fun updateStats(
        jid: EntityFullJid,
        participants: Int? = null,
        region: String? = null,
        inGracefulShutdown: Boolean? = null,
        transcriber: Boolean = false,
        sip: Boolean = true
    ) = processInstanceStatusChanged(
        jid,
        ColibriStatsExtension().apply {
            participants?.let {
                addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.PARTICIPANTS, it))
            }
            region?.let {
                addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.REGION, it))
            }
            inGracefulShutdown?.let {
                addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.SHUTDOWN_IN_PROGRESS, it))
            }
            if (transcriber) {
                addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.SUPPORTS_TRANSCRIPTION, true))
            }
            if (sip) {
                addStat(ColibriStatsExtension.Stat(ColibriStatsExtension.SUPPORTS_SIP, true))
            }
        }
    )
}
