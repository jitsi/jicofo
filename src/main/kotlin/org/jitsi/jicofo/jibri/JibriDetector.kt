/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.jibri

import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.BaseBrewery
import org.jitsi.utils.concurrent.CustomizableThreadFactory
import org.jitsi.utils.event.AsyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.Jid
import java.util.concurrent.Executors

/**
 * <tt>JibriDetector</tt> manages the pool of Jibri instances by joining a "brewery" room where Jibris connect to and
 * publish their status in MUC presence.
 *
 * @author Pawel Domas
 */
class JibriDetector(
    xmppProvider: XmppProvider,
    breweryJid: EntityBareJid,
    isSip: Boolean
) : BaseBrewery<JibriStatusPacketExt>(
    xmppProvider,
    breweryJid,
    JibriStatusPacketExt.ELEMENT_NAME,
    JibriStatusPacketExt.NAMESPACE,
    createLogger().apply { addContext("type", if (isSip) "sip_jibri" else "jibri") }
) {
    private val eventEmitter = AsyncEventEmitter<EventHandler>(eventEmitterExecutor)

    /**
     * Selects first idle Jibri which can be used to start recording.
     *
     * @return XMPP address of idle Jibri instance or <tt>null</tt> if there are
     * no Jibris available currently.
     */
    fun selectJibri(): Jid? {
        return instances.stream()
            .filter { it.status.isAvailable }
            .map { it.jid }
            .findFirst()
            .orElse(null)
    }

    override fun onInstanceStatusChanged(jid: Jid, presenceExt: JibriStatusPacketExt) {
        if (!presenceExt.isAvailable) {
            if (presenceExt.busyStatus == null || presenceExt.healthStatus == null) {
                notifyInstanceOffline(jid)
            }
        }
    }

    override fun notifyInstanceOffline(jid: Jid) = eventEmitter.fireEventAsync { instanceOffline(jid) }

    fun addHandler(eventHandler: EventHandler) = eventEmitter.addHandler(eventHandler)
    fun removeHandler(eventHandler: EventHandler) = eventEmitter.removeHandler(eventHandler)

    val stats: JSONObject
        get() = JSONObject().apply {
            this["count"] = instanceCount
            this["available"] = getInstanceCount { it.status.isAvailable }
        }

    interface EventHandler {
        fun instanceOffline(jid: Jid) {}
    }

    /**
     * The companion object is necessary for the implicit call to this.createLogger() in the super constructor!
     */
    companion object {
        /**
         * TODO: Refactor to use a common executor.
         */
        private val eventEmitterExecutor = Executors.newSingleThreadExecutor(
            CustomizableThreadFactory("JibriDetector-AsyncEventEmitter", false)
        )
    }
}
