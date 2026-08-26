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
package org.jitsi.jicofo.xmpp

import org.jitsi.jicofo.TaskPools
import org.jitsi.utils.logging2.createLogger
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.XMPPConnection

/**
 * Monitors the XMPP connections to the visitor nodes.
 *
 * A visitor node keeps the state of the MUCs that jicofo joined on it. This state is lost when the XMPP stream is
 * not resumed, for example because the visitor node restarted. Jicofo is not an occupant of these MUCs anymore, so
 * it does not receive presence from them. The state that jicofo keeps for them is stale and it must be discarded.
 *
 * Smack does not re-join the MUCs and it does not change its own state, so [onConnectionReset] is the only
 * notification that this happened.
 */
class VisitorConnectionMonitor(
    visitorConnections: List<XmppProvider>,
    /** Called with the name of a visitor node whose XMPP stream was re-established without being resumed. */
    private val onConnectionReset: (String) -> Unit
) {
    private val logger = createLogger()

    private val connectionListeners: List<Pair<XmppProvider, ConnectionListener>> = visitorConnections.map { provider ->
        val name = provider.config.name
        val listener = object : ConnectionListener {
            override fun authenticated(connection: XMPPConnection?, resumed: Boolean) {
                if (resumed) {
                    // The visitor node kept our session, so the MUCs that we joined on it are still joined.
                    return
                }
                logger.info("The XMPP stream to visitor node $name was not resumed.")
                // Do not do the work in Smack's thread.
                TaskPools.ioPool.submit {
                    try {
                        onConnectionReset(name)
                    } catch (e: Throwable) {
                        logger.error("Failed to handle a connection reset for visitor node $name", e)
                    }
                }
            }
        }
        provider.xmppConnection.addConnectionListener(listener)
        provider to listener
    }

    fun shutdown() = connectionListeners.forEach { (provider, listener) ->
        provider.xmppConnection.removeConnectionListener(listener)
    }
}
