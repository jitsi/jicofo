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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.MediaType
import org.jitsi.jicofo.conference.JitsiMeetConference
import org.jitsi.jicofo.conference.MuteResult
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.util.ListConferenceStore
import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithNoResponse
import org.jitsi.jicofo.xmpp.IqProcessingResult.RejectedWithError
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.xmpp.extensions.jitsimeet.MuteIq
import org.jitsi.xmpp.extensions.jitsimeet.MuteVideoIq
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class MuteIqHandlerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val roomJid = JidCreate.entityBareFrom("conf1@conference.example.com")
    private val muter: Jid = JidCreate.from("$roomJid/muter")
    private val mutee: Jid = JidCreate.from("$roomJid/mutee")

    /** Stanzas sent by the handler (results, errors, and mute notifications). */
    private val sentStanzas = mutableListOf<Stanza>()
    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? = null.also { sentStanzas.add(iq) }
    }

    private var muteResult = MuteResult.SUCCESS
    private val chatRoom: ChatRoom = mockk(relaxed = true) {
        every { queueXmppTask(any()) } answers { firstArg<() -> Unit>().invoke() }
    }
    private val conference: JitsiMeetConference = mockk(relaxed = true) {
        every { roomName } returns roomJid
        every { chatRoom } returns this@MuteIqHandlerTest.chatRoom
        every { handleMuteRequest(any(), any(), any(), any()) } answers { muteResult }
    }
    private val conferenceStore = ListConferenceStore().apply { add(conference) }

    private val handler = AudioMuteIqHandler(setOf(xmppConnection.xmppConnection), conferenceStore)

    private fun request(jidToMute: Jid? = mutee, doMute: Boolean? = true, from: Jid = muter): IqProcessingResult {
        val iq = MuteIq().apply {
            this.from = from
            this.type = IQ.Type.set
            jidToMute?.let { this.jid = it }
            doMute?.let { this.mute = it }
        }
        return handler.handleRequest(IqRequest(iq, xmppConnection.xmppConnection))
    }

    init {
        context("Missing fields") {
            should("reject a request without a jid") {
                request(jidToMute = null).shouldBeInstanceOf<RejectedWithError>().response.error.condition shouldBe
                    StanzaError.Condition.bad_request
            }
            should("reject a request without mute") {
                request(doMute = null).shouldBeInstanceOf<RejectedWithError>().response.error.condition shouldBe
                    StanzaError.Condition.bad_request
            }
        }
        context("Unknown conference") {
            request(from = JidCreate.from("other@conference.example.com/muter"))
                .shouldBeInstanceOf<RejectedWithError>().response.error.condition shouldBe
                StanzaError.Condition.item_not_found
        }
        context("A successful remote mute") {
            request().shouldBeInstanceOf<AcceptedWithNoResponse>()

            sentStanzas.size shouldBe 2
            (sentStanzas[0] as IQ).type shouldBe IQ.Type.result

            // The muted participant should be notified, with the actor set.
            val muteIq = sentStanzas[1].shouldBeInstanceOf<MuteIq>()
            muteIq.to shouldBe mutee
            muteIq.mute shouldBe true
            muteIq.actor shouldBe muter
        }
        context("A successful self mute") {
            request(jidToMute = muter, from = muter).shouldBeInstanceOf<AcceptedWithNoResponse>()

            // No notification should be sent for a self-mute, just the result.
            sentStanzas.size shouldBe 1
            (sentStanzas[0] as IQ).type shouldBe IQ.Type.result
        }
        context("A mute that is not allowed") {
            muteResult = MuteResult.NOT_ALLOWED
            request().shouldBeInstanceOf<AcceptedWithNoResponse>()

            sentStanzas.size shouldBe 1
            val response = sentStanzas[0].shouldBeInstanceOf<IQ>()
            response.type shouldBe IQ.Type.error
            response.error.condition shouldBe StanzaError.Condition.not_allowed
        }
        context("A mute that fails") {
            muteResult = MuteResult.ERROR
            request().shouldBeInstanceOf<AcceptedWithNoResponse>()

            sentStanzas.size shouldBe 1
            val response = sentStanzas[0].shouldBeInstanceOf<IQ>()
            response.type shouldBe IQ.Type.error
            response.error.condition shouldBe StanzaError.Condition.internal_server_error
        }
        context("Video mute") {
            val videoHandler = VideoMuteIqHandler(setOf(xmppConnection.xmppConnection), conferenceStore)
            val iq = MuteVideoIq().apply {
                from = muter
                type = IQ.Type.set
                jid = mutee
                mute = true
            }
            videoHandler.handleRequest(IqRequest(iq, xmppConnection.xmppConnection))
                .shouldBeInstanceOf<AcceptedWithNoResponse>()

            io.mockk.verify { conference.handleMuteRequest(muter, mutee, true, MediaType.VIDEO) }
            val muteIq = sentStanzas[1].shouldBeInstanceOf<MuteVideoIq>()
            muteIq.to shouldBe mutee
        }
    }
}
