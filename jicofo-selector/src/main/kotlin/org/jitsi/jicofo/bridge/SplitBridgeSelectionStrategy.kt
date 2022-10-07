/*
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
package org.jitsi.jicofo.bridge

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl

/**
 * Implements a [BridgeSelectionStrategy] which "splits" a conference to as many bridges as possible (always
 * selects a bridge not in the conference or with the fewest endpoints from that conference). For testing purposes only.
 */
class SplitBridgeSelectionStrategy : BridgeSelectionStrategy() {
    /**
     * {@inheritDoc}
     *
     * Always selects the bridge already used by the conference.
     */
    override fun doSelect(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties
    ): Bridge? {
        // If there's any bridge not yet in this conference, use that; otherwise
        // find the bridge with the fewest participants
        val bridgeNotYetInConf = bridges.firstOrNull { !conferenceBridges.containsKey(it) }

        return bridgeNotYetInConf ?: conferenceBridges.entries
            .filter { bridges.contains(it.key) }
            .minByOrNull { it.value.participantCount }?.key
    }

    override fun select(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties,
        allowMultiBridge: Boolean
    ): Bridge? {
        if (!allowMultiBridge) {
            logger.warn(
                "Force-enabling octo for SplitBridgeSelectionStrategy. To suppress this warning, enable octo" +
                    " in jicofo.conf."
            )
        }
        return super.select(bridges, conferenceBridges, participantProperties, true)
    }

    companion object {
        private val logger: Logger = LoggerImpl(SplitBridgeSelectionStrategy::class.java.name)
    }
}
