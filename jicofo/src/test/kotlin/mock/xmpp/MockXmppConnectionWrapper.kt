/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017-Present 8x8, Inc.
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
package mock.xmpp

import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid
import java.lang.RuntimeException

class MockXmppConnectionWrapper {
    private val xmppConnections: MutableMap<Jid, MockXmppConnection> = mutableMapOf()

    fun sendIqAndGetResponse(iq: IQ): IQ? =
        if (iq.from == null) throw RuntimeException("Can not send IQ with no 'from'.")
        else xmppConnections.computeIfAbsent(iq.from) { MockXmppConnection(it) }.sendIqAndGetResponse(iq)

    fun shutdown() = xmppConnections.values.forEach { it.disconnect() }
}
