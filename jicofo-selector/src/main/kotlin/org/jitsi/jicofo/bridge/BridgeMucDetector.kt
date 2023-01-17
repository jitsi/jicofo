/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018-Present 8x8, Inc.
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

import org.jitsi.jicofo.xmpp.BaseBrewery
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid

/**
 * Detects jitsi-videobridge instances through a MUC.
 *
 * @author Boris Grozev
 */
class BridgeMucDetector(
    xmppProvider: XmppProvider,
    /**
     * The [BridgeSelector] instance which will be notified when new jitsi-videobridge instances are detected, or when
     * they update their status.
     */
    private val bridgeSelector: BridgeSelector,
    breweryJid: EntityBareJid
) : BaseBrewery<ColibriStatsExtension?>(
    xmppProvider,
    breweryJid,
    ColibriStatsExtension.ELEMENT,
    ColibriStatsExtension.NAMESPACE,
    LoggerImpl(BridgeMucDetector::class.simpleName).apply { addContext("type", "bridge") }
) {

    override fun onInstanceStatusChanged(jid: EntityFullJid, stats: ColibriStatsExtension) {
        bridgeSelector.addJvbAddress(jid, stats)
    }

    override fun notifyInstanceOffline(jid: Jid) = bridgeSelector.removeJvbAddress(jid)
}
