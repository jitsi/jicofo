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
import io.kotest.matchers.shouldNotBe
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate

class ConferenceRoomFilterTest : ShouldSpec() {
    private val conferenceMucJid = JidCreate.domainBareFrom("conference.example.com")
    private val brewery = JidCreate.entityBareFrom("jibribrewery@internal.auth.example.com")

    private fun ConferenceRoomFilter.allows(room: String) = rejectionReason(JidCreate.entityBareFrom(room)) == null

    private fun filter(
        conferenceMucJid: DomainBareJid = this.conferenceMucJid,
        enabled: Boolean = true,
        discoveryPending: Boolean = false,
        additionalDomains: List<DomainBareJid> = emptyList()
    ) = ConferenceRoomFilter(
        conferenceMucJid,
        reservedRooms = setOf(brewery),
        discoveryPending = { discoveryPending },
        enabled = enabled,
        additionalDomains = additionalDomains
    )

    init {
        context("With the default configuration") {
            val filter = filter()

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
                filter.rejectionReason(brewery) shouldNotBe null
            }
            should("accept the breakout rooms domain once it is discovered") {
                filter.allows("room@breakout.example.com") shouldBe false
                filter.breakoutAddress = JidCreate.domainBareFrom("breakout.example.com")
                filter.allows("room@breakout.example.com") shouldBe true
                filter.allows("room@breakout.tenant.example.com") shouldBe true
                filter.allows("room@breakout.other.com") shouldBe false
                filter.allows("room@breakoutx.example.com") shouldBe false
            }
        }
        context("While the discovery of components is pending") {
            val filter = filter(discoveryPending = true)
            should("accept any domain, because the breakout rooms domain is not known yet") {
                filter.allows("room@breakout.example.com") shouldBe true
                filter.allows("room@something.else.com") shouldBe true
            }
            should("still reject the reserved rooms") {
                filter.rejectionReason(brewery) shouldNotBe null
            }
            should("apply the breakout rooms domain once it is discovered") {
                filter.breakoutAddress = JidCreate.domainBareFrom("breakout.example.com")
                filter.allows("room@breakout.example.com") shouldBe true
                filter.allows("room@something.else.com") shouldBe false
            }
        }
        context("With the domains used in production") {
            // The conference MUC is conference.<domain>. Tenant rooms use conference.<tenant>.<domain>. Breakout rooms
            // use breakout.<domain> for all tenants.
            val filter = filter(conferenceMucJid = JidCreate.domainBareFrom("conference.8x8.vc"))
            filter.breakoutAddress = JidCreate.domainBareFrom("breakout.8x8.vc")

            filter.allows("room@conference.8x8.vc") shouldBe true
            filter.allows("room@conference.vpaas-magic-cookie-2f407ed6f702472e838c75bcd30e527f.8x8.vc") shouldBe true
            filter.allows("6d056a94-c125-4b4f-896c-8069e4558d5a@breakout.8x8.vc") shouldBe true
            filter.allows("room@breakout.vpaas-magic-cookie-2f407ed6f702472e838c75bcd30e527f.8x8.vc") shouldBe true
            filter.allows("jibribrewery@internal.auth.8x8.vc") shouldBe false
            filter.allows("room@brewery.8x8.vc") shouldBe false
            filter.allows("focus@auth.8x8.vc") shouldBe false
        }
        context("With additional domains") {
            val filter = filter(additionalDomains = listOf(JidCreate.domainBareFrom("muc.other.com")))
            filter.allows("room@muc.other.com") shouldBe true
            filter.allows("room@other.com") shouldBe false
            filter.allows("room@conference.example.com") shouldBe true
        }
        context("Disabled") {
            val filter = filter(enabled = false)
            should("accept any domain") {
                filter.allows("room@conference.example.com") shouldBe true
                filter.allows("room@internal.auth.example.com") shouldBe true
            }
            should("still reject the reserved rooms") {
                filter.rejectionReason(brewery) shouldNotBe null
            }
        }
        context("With a conference MUC domain without a dot") {
            val filter = filter(conferenceMucJid = JidCreate.domainBareFrom("conference"))
            filter.allows("room@conference") shouldBe true
            filter.allows("room@conference.tenant") shouldBe false
        }
    }
}
