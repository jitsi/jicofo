/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import mock.xmpp.MockXmppConnection
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithResponse
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.impl.JidCreate

class AbstractIqHandlerTest : ShouldSpec() {
    init {
        context("Receiving IQs of both types, on both connections") {
            val jid1 = JidCreate.from("jid1@example.com")
            val jid2 = JidCreate.from("jid2@example.com")
            val connection1 = MockXmppConnection(jid1)
            val connection2 = MockXmppConnection(jid2)
            val handler = DummyIqHandler(setOf(connection1, connection2))

            val senderJid = JidCreate.from("sender@example.com")
            val senderConnection = MockXmppConnection(senderJid)

            listOf(IQ.Type.get, IQ.Type.set).forEach { type ->
                listOf(jid1, jid2).forEach { to ->
                    val dummyIq = DummyIq().apply {
                        this.from = senderJid
                        this.to = to
                        this.type = type
                    }

                    senderConnection.sendIqAndGetResponse(dummyIq).shouldBeInstanceOf<DummyIq>()
                }
            }

            handler.shutdown()
            connection1.disconnect()
            connection2.disconnect()
            senderConnection.disconnect()
        }
    }

    private class DummyIq : IQ(ELEMENT, NAMESPACE) {
        override fun getIQChildElementBuilder(p0: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder =
            mockk()
        companion object {
            const val ELEMENT = "dummy-namespace"
            const val NAMESPACE = "dummy-namespace"
        }
    }

    private class DummyIqHandler(connections: Set<AbstractXMPPConnection>) :
        AbstractIqHandler<DummyIq>(connections, DummyIq.ELEMENT, DummyIq.NAMESPACE) {

        override fun handleRequest(request: IqRequest<DummyIq>) = AcceptedWithResponse(
            request.iq.apply {
                type = IQ.Type.result
                to = from.also { from = to }
            }
        )
    }
}
