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
package org.jitsi.jicofo.xmpp

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
                "a%2bb",
                "vpaas-magic-cookie-0123456789abcdef",
                "a-b_c.d~e"
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
                "myroom?x",
                "myroom#config.startsilent=true",
                "%61",
                "a%2",
                "room+x",
                "caf%c3%89",
                "a%ff",
                "a b",
                "a/b",
                "tenant?x",
                ".",
                "..",
                "cafe%cc%81",
                "%ef%bd%81",
                // jitsi-meet removes these from the URL path before it decodes the name, so "myroom*" joins
                // "myroom". encodeURIComponent keeps them, so a round trip alone does not detect them.
                "myroom*",
                "myroom!",
                "myroom()",
                "myroom%2a",
                "myroom%21",
                // a web server can decode "%2f" before it splits the path
                "a%2fb",
                "a%2Fb"
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
        should("accept a non-ASCII tenant, which the MUC domain keeps raw") {
            safe("room@conference.caf\u00e9.example.com") shouldBe true
            safe("[caf\u00e9]room@conference.example.com") shouldBe true
        }
        should("reject rooms in a different form") {
            safe("myroom?x@conference.example.com") shouldBe false
            safe("myroom#config.startsilent=true@conference.example.com") shouldBe false
            safe("%61@conference.example.com") shouldBe false
            safe("room+x@conference.example.com") shouldBe false
            safe("myroom*@conference.example.com") shouldBe false
        }
        should("reject a tenant in a different form") {
            safe("room@conference.tenant?x.example.com") shouldBe false
            safe("[tenant?x]room@conference.example.com") shouldBe false
            safe("[tenant]room?x@conference.example.com") shouldBe false
            safe("[myroom%2fx]room@conference.example.com") shouldBe false
            safe("room@conference.myroom%2fx.example.com") shouldBe false
            safe("[a.b]room@conference.example.com") shouldBe false
            safe("[caf%c3%a9]room@conference.example.com") shouldBe false
            safe("[tenant*]room@conference.example.com") shouldBe false
        }
        should("reject an empty tenant or room name") {
            safe("[]room@conference.example.com") shouldBe false
            safe("[tenant]@conference.example.com") shouldBe false
        }
    }
})
