/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026-Present 8x8, Inc.
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
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StanzaError

class UtilTest : ShouldSpec() {
    init {
        context("findStanzaError") {
            val stanzaError = StanzaError.getBuilder(StanzaError.Condition.resource_constraint).build()
            val xmppError = XMPPException.XMPPErrorException(
                StanzaBuilder.buildPresence().ofType(Presence.Type.error).build(),
                stanzaError
            )

            should("find the error of an XMPPErrorException") {
                xmppError.findStanzaError() shouldBe stanzaError
            }
            should("find the error in the cause chain") {
                Exception("outer", RuntimeException("inner", xmppError)).findStanzaError() shouldBe stanzaError
            }
            should("return null when there is no XMPPErrorException") {
                Exception("outer", RuntimeException("inner")).findStanzaError() shouldBe null
            }
        }
        context("isRoomCreationRefused") {
            fun xmppError(condition: StanzaError.Condition, type: StanzaError.Type) = XMPPException.XMPPErrorException(
                StanzaBuilder.buildPresence().ofType(Presence.Type.error).build(),
                StanzaError.getBuilder(condition).setType(type).build()
            )

            should("match a cancel resource-constraint error") {
                xmppError(StanzaError.Condition.resource_constraint, StanzaError.Type.CANCEL)
                    .isRoomCreationRefused() shouldBe true
            }
            should("match a cancel resource-constraint error in the cause chain") {
                Exception(
                    "outer",
                    xmppError(StanzaError.Condition.resource_constraint, StanzaError.Type.CANCEL)
                ).isRoomCreationRefused() shouldBe true
            }
            should("not match a wait resource-constraint error") {
                xmppError(StanzaError.Condition.resource_constraint, StanzaError.Type.WAIT)
                    .isRoomCreationRefused() shouldBe false
            }
            should("not match a different condition") {
                xmppError(StanzaError.Condition.forbidden, StanzaError.Type.CANCEL)
                    .isRoomCreationRefused() shouldBe false
            }
            should("not match an exception without an XMPP error") {
                RuntimeException("test").isRoomCreationRefused() shouldBe false
            }
        }
    }
}
