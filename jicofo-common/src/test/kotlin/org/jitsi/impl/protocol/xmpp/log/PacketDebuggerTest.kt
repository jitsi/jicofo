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
package org.jitsi.impl.protocol.xmpp.log

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jitsi.xmpp.util.RedactColibri.Companion.redactToken
import org.jxmpp.jid.impl.JidCreate

class PacketDebuggerTest : ShouldSpec({
    should("redact the token attribute of a logged ConferenceIq, as done by PacketDebugger") {
        val token = "eyJhbGciOiJIUzI1NiJ9.super-secret-jwt"
        val iq = ConferenceIq().apply {
            room = JidCreate.entityBareFrom("room@conference.example.com")
            this.token = token
            stanzaId = "id1"
        }

        val redacted = redactToken(iq.toXML().toString())

        redacted shouldNotContain token
        redacted shouldContain "room=\"room@conference.example.com\""
        redacted shouldContain "token=\"[redacted]\""
    }
})
