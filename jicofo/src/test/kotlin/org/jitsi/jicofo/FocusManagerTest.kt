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
package org.jitsi.jicofo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.conference.ConferenceMetrics
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.impl.JidCreate

class FocusManagerTest : ShouldSpec() {
    init {
        context("Conference which fails to join the room") {
            val joinError = Exception("resource-constraint")
            val chatRoom: ChatRoom = mockk(relaxed = true) {
                every { join() } throws joinError
            }
            val jicofoServices: JicofoServices = mockk(relaxed = true) {
                every { xmppServices.clientConnection.registered } returns true
                every { xmppServices.clientConnection.createRoom(any(), any()) } returns chatRoom
            }
            val focusManager = FocusManager(jicofoServices = jicofoServices)
            val conferenceCount = ConferenceMetrics.conferenceCount.get()

            val thrown = shouldThrow<Exception> {
                focusManager.conferenceRequest(JidCreate.entityBareFrom("conference@example.com"), emptyMap())
            }

            should("propagate the join error") {
                thrown shouldBe joinError
            }
            should("be removed") {
                focusManager.getConferences().shouldBeEmpty()
            }
            should("not be counted as a running conference") {
                ConferenceMetrics.conferenceCount.get() shouldBe conferenceCount
            }
        }
        context("Conference which the MUC refuses to create") {
            // This is how mod_time_restricted refuses to create a room again after it reached its time limit.
            val joinError = XMPPException.XMPPErrorException(
                StanzaBuilder.buildPresence().ofType(Presence.Type.error).build(),
                StanzaError.getBuilder(StanzaError.Condition.resource_constraint)
                    .setType(StanzaError.Type.CANCEL)
                    .build()
            )
            val chatRoom: ChatRoom = mockk(relaxed = true) {
                every { join() } throws joinError
            }
            val jicofoServices: JicofoServices = mockk(relaxed = true) {
                every { xmppServices.clientConnection.registered } returns true
                every { xmppServices.clientConnection.createRoom(any(), any()) } returns chatRoom
            }
            val focusManager = FocusManager(jicofoServices = jicofoServices)

            val thrown = shouldThrow<Exception> {
                focusManager.conferenceRequest(JidCreate.entityBareFrom("conference@example.com"), emptyMap())
            }

            should("propagate the join error") {
                thrown shouldBe joinError
            }
            should("be removed") {
                focusManager.getConferences().shouldBeEmpty()
            }
        }
    }
}
