/*
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.jicofo.mock

import io.mockk.every
import io.mockk.mockk
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaFactory
import org.jivesoftware.smack.packet.id.StanzaIdSource

open class MockXmppConnection {
    val xmppConnection: AbstractXMPPConnection = mockk(relaxed = true) {
        every { createStanzaCollectorAndSend(any()) } answers {
            val request = arg<IQ>(0)
            mockk(relaxed = true) {
                every { nextResult<IQ>() } answers { handleIq(request) }
            }
        }

        every { trySendStanza(any()) } answers {
            val request = arg<Stanza>(0)
            if (request is IQ) handleIq(request)
            true
        }

        every { sendStanza(any()) } answers {
            val request = arg<Stanza>(0)
            if (request is IQ) handleIq(request)
        }

        val stanzaIdSource = object : StanzaIdSource {
            private var stanzaId = 0
            override fun getNewStanzaId() = "${stanzaId++}"
        }
        every { stanzaFactory } returns StanzaFactory(stanzaIdSource)
    }

    open fun handleIq(iq: IQ): IQ? = null
}
