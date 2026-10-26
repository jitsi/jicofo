/*
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
package org.jitsi.jicofo.jibri

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jxmpp.jid.impl.JidCreate

class BackendSafeRoomNameTest : ShouldSpec({
    context("isBackendSafeRoomName") {
        should("accept names in the form jitsi-meet uses") {
            listOf(
                "roomname",
                "my%20room",
                "caf%c3%a9",
                "a%3fb",
                "vpaas-magic-cookie-0123456789abcdef",
                "a-b_c.d!e~f*g'h(i)j"
            ).forEach {
                isBackendSafeRoomName(it) shouldBe true
            }
        }
        should("accept names which differ only in case") {
            listOf("RoomName", "My%20Room", "CAF%C3%A9", "A%3Fb").forEach {
                isBackendSafeRoomName(it) shouldBe true
            }
        }
        should("reject names in a different form") {
            listOf(
                "",
                "lockedvictim?x",
                "victim#config.startsilent=true",
                "%61",
                "a%2",
                "room+x",
                "caf%c3%89",
                "a%ff",
                "a b",
                "a/b",
                "tenant?x"
            ).forEach {
                isBackendSafeRoomName(it) shouldBe false
            }
        }
    }
    context("isBackendSafeRoomJid") {
        fun safe(jid: String) = isBackendSafeRoomJid(JidCreate.entityBareFrom(jid))

        should("accept rooms in the form jitsi-meet uses") {
            safe("roomname@conference.example.com") shouldBe true
            safe("my%20room@conference.example.com") shouldBe true
            safe("caf%c3%a9@conference.example.com") shouldBe true
            safe("a%3fb@conference.example.com") shouldBe true
        }
        should("accept tenant rooms in both forms") {
            safe("room@conference.vpaas-magic-cookie-0123456789abcdef.example.com") shouldBe true
            safe("[vpaas-magic-cookie-0123456789abcdef]room@conference.example.com") shouldBe true
        }
        should("reject rooms in a different form") {
            safe("lockedvictim?x@conference.example.com") shouldBe false
            safe("victim#config.startsilent=true@conference.example.com") shouldBe false
            safe("%61@conference.example.com") shouldBe false
            safe("room+x@conference.example.com") shouldBe false
        }
        should("reject a tenant in a different form") {
            safe("room@conference.tenant?x.example.com") shouldBe false
            safe("[tenant?x]room@conference.example.com") shouldBe false
            safe("[tenant]room?x@conference.example.com") shouldBe false
        }
    }
})
