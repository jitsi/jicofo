/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jxmpp.jid.impl.JidCreate

class ConferenceRoomFilterTest : ShouldSpec() {
    private val conferenceMucJid = JidCreate.domainBareFrom("conference.example.com")
    private val brewery = JidCreate.entityBareFrom("jibribrewery@internal.auth.example.com")

    private fun ConferenceRoomFilter.allows(room: String) = isAllowed(JidCreate.entityBareFrom(room))

    init {
        context("With the default configuration") {
            val filter = ConferenceRoomFilter(
                conferenceMucJid,
                setOf(brewery),
                enabled = true,
                additionalDomains = emptyList()
            )

            should("accept rooms in the conference MUC domain") {
                filter.allows("room@conference.example.com") shouldBe true
            }
            should("accept rooms in tenant sub-domains of the conference MUC domain") {
                filter.allows("room@conference.tenant.example.com") shouldBe true
                filter.allows("room@conference.a.b.example.com") shouldBe true
            }
            should("reject rooms in other domains") {
                filter.allows("room@example.com") shouldBe false
                filter.allows("room@internal.auth.example.com") shouldBe false
                filter.allows("room@auth.example.com") shouldBe false
                filter.allows("room@127.0.0.1") shouldBe false
                filter.allows("room@conference.example.com.evil.net") shouldBe false
                filter.allows("room@conferencex.example.com") shouldBe false
                filter.allows("room@xconference.example.com") shouldBe false
                filter.allows("room@conference.xexample.com") shouldBe false
                filter.allows("room@breakout.example.com") shouldBe false
            }
            should("reject the reserved rooms") {
                filter.isAllowed(brewery) shouldBe false
            }
            should("accept the breakout rooms domain once it is discovered") {
                filter.allows("room@breakout.example.com") shouldBe false
                filter.breakoutAddress = JidCreate.domainBareFrom("breakout.example.com")
                filter.allows("room@breakout.example.com") shouldBe true
                filter.allows("room@breakout.other.com") shouldBe false
            }
        }
        context("With additional domains") {
            val filter = ConferenceRoomFilter(
                conferenceMucJid,
                setOf(brewery),
                enabled = true,
                additionalDomains = listOf(JidCreate.domainBareFrom("muc.other.com"))
            )
            filter.allows("room@muc.other.com") shouldBe true
            filter.allows("room@other.com") shouldBe false
            filter.allows("room@conference.example.com") shouldBe true
        }
        context("Disabled") {
            val filter = ConferenceRoomFilter(
                conferenceMucJid,
                setOf(brewery),
                enabled = false,
                additionalDomains = emptyList()
            )
            should("accept any domain") {
                filter.allows("room@conference.example.com") shouldBe true
                filter.allows("room@internal.auth.example.com") shouldBe true
            }
            should("still reject the reserved rooms") {
                filter.isAllowed(brewery) shouldBe false
            }
        }
        context("With a conference MUC domain without a dot") {
            val filter = ConferenceRoomFilter(
                JidCreate.domainBareFrom("conference"),
                enabled = true,
                additionalDomains = emptyList()
            )
            filter.allows("room@conference") shouldBe true
            filter.allows("room@conference.tenant") shouldBe false
        }
    }
}
