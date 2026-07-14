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
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Nonza
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This tests the bridge between Smack and our [AbstractIqHandler]. [AbstractXMPPConnection] uses its own executors
 * to process stanzas, and since it's impossible to mock them we're forced to just wait.
 *
 * The intention is to leave this as the only test that requires blindly waiting for other threads, while the rest
 * of the tests can assume that the Smack layer works and can pass IQs directly and synchronously to the intended
 * IQ handler.
 */
class AbstractIqHandlerTest : ShouldSpec() {
    init {
        context("Receiving IQs of both types, on both connections") {
            val jid1 = JidCreate.from("jid1@example.com")
            val jid2 = JidCreate.from("jid2@example.com")
            val connection1 = TestXmppConnection().apply { connect() }
            val connection2 = TestXmppConnection().apply { connect() }

            val senderJid = JidCreate.from("sender@example.com")

            // Pre-construct the IQs we'll send
            val requests = buildList<IQ> {
                listOf(IQ.Type.get, IQ.Type.set).forEach { type ->
                    listOf(jid1, jid2).forEach { to ->
                        add(
                            DummyIq().apply {
                                this.from = senderJid
                                this.to = to
                                this.type = type
                            }
                        )
                    }
                }
            }

            // We expect to receive each request from each of the 2 connections
            val latch = CountDownLatch(2 * requests.size)
            val handler = DummyIqHandler(setOf(connection1, connection2), latch)
            requests.forEach {
                connection1.mockProcessStanza(it)
                connection2.mockProcessStanza(it)
            }

            should("Receive all requests") {
                // This is a very simple test and should complete quickly unless something is completely broken. Use
                // a very long timeout to prevent false-positives coming from e.g. the test machines not being scheduled
                // enough CPU cycles.
                latch.await(1, TimeUnit.MINUTES) shouldBe true
            }

            handler.shutdown()
        }
    }

    private class DummyIq : IQ(ELEMENT, NAMESPACE) {
        override fun getIQChildElementBuilder(p0: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder =
            mockk()
        companion object {
            const val ELEMENT = "dummy-name"
            const val NAMESPACE = "dummy-namespace"
        }
    }

    private class DummyIqHandler(
        connections: Set<AbstractXMPPConnection>,
        private val latch: CountDownLatch
    ) : AbstractIqHandler<DummyIq>(connections, DummyIq.ELEMENT, DummyIq.NAMESPACE) {

        override fun handleRequest(request: IqRequest<DummyIq>): IqProcessingResult =
            IqProcessingResult.AcceptedWithNoResponse().also { latch.countDown() }
    }
}

private class TestXmppConnection : AbstractXMPPConnection(mockk(relaxed = true)) {
    override fun isSecureConnection(): Boolean = true
    override fun isUsingCompression(): Boolean = false
    override fun sendNonza(p0: Nonza?) { }
    override fun sendStanzaInternal(p0: Stanza?) { }
    override fun connectInternal() {
        connected = true
    }
    override fun loginInternal(p0: String?, p1: String?, p2: Resourcepart?) { }
    override fun instantShutdown() { }
    override fun shutdown() { }

    /** Expose as public. */
    fun mockProcessStanza(stanza: Stanza) = invokeStanzaCollectorsAndNotifyRecvListeners(stanza)
}
