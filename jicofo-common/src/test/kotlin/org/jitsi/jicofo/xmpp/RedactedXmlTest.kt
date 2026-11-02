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
package org.jitsi.jicofo.xmpp

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Connect
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jxmpp.jid.impl.JidCreate
import java.net.URI

class RedactedXmlTest : ShouldSpec({
    fun connect(create: Boolean = false, expire: Boolean = false) = Connect(
        id = "agent-1",
        url = URI("wss://relay.example.com/agent"),
        protocol = Connect.Protocols.MEDIAJSON,
        type = Connect.Types.AGENT,
        audio = true,
        create = create,
        expire = expire
    ).apply { addHttpHeader("X-Agent-Token", "s3cret-token") }

    fun request(connect: Connect) = ConferenceModifyIQ.builder("id").setMeetingId("m").addConnect(connect).build()

    context("connect header values") {
        listOf(
            "a create connect" to connect(create = true),
            "an update connect" to connect(),
            "an expire connect" to connect(expire = true)
        ).forEach { (name, connect) ->
            should("be redacted in $name, keeping the rest of the request") {
                val xml = request(connect).toRedactedXml()

                xml shouldNotContain "s3cret-token"
                xml shouldContain "name=\"X-Agent-Token\""
                xml shouldContain "value=\"[redacted]\""
                xml shouldContain "wss://relay.example.com/agent"
            }
        }
    }

    should("leave a request without connects exactly as serialized") {
        val iq = ConferenceModifyIQ.builder("id").setMeetingId("m").setCreate(true).build()

        iq.toRedactedXml() shouldBe iq.toXML().toString()
    }

    should("redact a conference request's token") {
        val iq = ConferenceIq().apply {
            room = JidCreate.entityBareFrom("room@conference.example.com")
            token = "eyJhbGciOiJIUzI1NiJ9.super-secret-jwt"
            stanzaId = "id1"
        }

        val xml = iq.toRedactedXml()

        xml shouldNotContain "super-secret-jwt"
        xml shouldContain "token=\"[redacted]\""
    }

    should("return a placeholder rather than throw or echo the input when the XML cannot be transformed") {
        val result = redactXml("<iq xmlns='jabber:client'><http-header value='s3cret-token'", "iq id1")

        result shouldStartWith "[iq id1 could not be redacted: "
        result shouldNotContain "s3cret-token"
    }

    should("name the stanza in the placeholder when its XML has a character XML does not allow") {
        val iq = ConferenceModifyIQ.builder("id1").setMeetingId("m\u0001").addConnect(connect()).build()

        val result = iq.toRedactedXml()

        result shouldBe "[iq id1 could not be redacted: TransformerException]"
    }
})
