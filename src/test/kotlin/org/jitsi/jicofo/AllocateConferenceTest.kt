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
package org.jitsi.jicofo

import io.kotest.core.spec.Spec
import io.kotest.matchers.types.shouldBeInstanceOf
import mock.xmpp.MockXmppConnection
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.impl.JidCreate

class AllocateConferenceTest : JicofoHarnessTest() {
    private val from = JidCreate.bareFrom("from@example.com")
    private val xmppConnection = MockXmppConnection(from)

    override fun afterSpec(spec: Spec) = super.afterSpec(spec).also { xmppConnection.disconnect() }

    init {
        context("Handling a ConferenceIQ") {
            val conferenceIq = ConferenceIq().apply {
                this.from = from
                room = JidCreate.entityBareFrom("testRoom@example.com")
                to = harness.jicofoServices.jicofoJid
                type = IQ.Type.set
            }

            xmppConnection.sendIqAndGetResponse(conferenceIq).shouldBeInstanceOf<ConferenceIq>()
        }
    }
}
