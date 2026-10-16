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

import org.jitsi.jicofo.ConferenceConfig
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.EntityBareJid

/**
 * Decides which rooms a conference request is accepted for.
 *
 * A conference request makes jicofo join the room as the focus. Jicofo must only do that for rooms that are
 * conference rooms. In particular it must never join a room that one of its own components uses, such as a
 * brewery room. Joining such a room a second time breaks the component that uses it.
 *
 * A room is accepted when:
 *  - its domain is the conference MUC domain ([conferenceMucJid]),
 *  - its domain is a tenant sub-domain of the conference MUC domain, that is `conference.<tenant>.example.com` for a
 *    conference MUC domain `conference.example.com`,
 *  - its domain is the breakout rooms domain ([breakoutAddress], discovered from the XMPP server) or a tenant
 *    sub-domain of it, or
 *  - its domain is listed in the configuration (`jicofo.conference.room-domain-filter.additional-domains`).
 *
 * While the discovery of the breakout rooms domain is still pending ([discoveryPending]), all rooms are accepted.
 * Otherwise breakout rooms would be rejected in the short window after jicofo connects.
 *
 * The domain checks can be disabled in the configuration. A room that is listed in [reservedRooms] (the brewery rooms)
 * is always rejected.
 */
class ConferenceRoomFilter(
    private val conferenceMucJid: DomainBareJid,
    /** Rooms that are used internally (brewery rooms). They are rejected regardless of the configuration. */
    private val reservedRooms: Set<EntityBareJid> = emptySet(),
    /** Whether the discovery of XMPP components (which provides the breakout rooms domain) is still pending. */
    private val discoveryPending: () -> Boolean = { false },
    private val enabled: Boolean = ConferenceConfig.config.roomDomainFilterEnabled,
    private val additionalDomains: List<DomainBareJid> = ConferenceConfig.config.roomDomainFilterAdditionalDomains
) {
    /** The domain of the breakout rooms component, if one was discovered. */
    @Volatile
    var breakoutAddress: DomainBareJid? = null

    /**
     * The labels of the conference MUC domain, e.g. ["conference", "example", "com"]. The domain part is already
     * normalized by jxmpp, so "." is the label separator and no further string preparation is needed.
     */
    private val mucLabels = conferenceMucJid.labels()

    /**
     * Check whether a conference request for [room] is accepted.
     * @return `null` if the room is accepted, or a short reason if it is rejected.
     */
    fun rejectionReason(room: EntityBareJid): String? {
        if (reservedRooms.contains(room)) {
            return "the room is reserved for internal use"
        }
        if (!enabled) {
            return null
        }

        val domain = room.asDomainBareJid()
        if (domain == conferenceMucJid || additionalDomains.contains(domain)) {
            return null
        }
        if (isTenantSubdomain(domain.labels(), mucLabels)) {
            return null
        }
        val breakoutAddress = this.breakoutAddress
        if (breakoutAddress == null) {
            if (discoveryPending()) {
                // The breakout rooms domain is not known yet, so this may be a breakout room.
                return null
            }
        } else if (domain == breakoutAddress || isTenantSubdomain(domain.labels(), breakoutAddress.labels())) {
            return null
        }
        return "the domain is not a conference MUC domain"
    }

    private fun DomainBareJid.labels() = toString().split('.')

    /**
     * Whether [labels] is `<first label>.<tenant labels>.<rest>` for a base domain `<first label>.<rest>` with labels
     * [baseLabels], with at least one tenant label.
     */
    private fun isTenantSubdomain(labels: List<String>, baseLabels: List<String>): Boolean = baseLabels.size >= 2 &&
        labels.size > baseLabels.size &&
        labels.first() == baseLabels.first() &&
        labels.takeLast(baseLabels.size - 1) == baseLabels.drop(1)
}
