/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.codec

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.jicofo.codec.JingleOfferFactory.INSTANCE as jingleOfferFactory

class JingleOfferFactoryTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.SingleInstance
    init {
        context("With default options") {
            val offer = jingleOfferFactory.createOffer(OfferOptions())

            offer.find { it.name == "audio" } shouldNotBe null
            offer.find { it.name == "data" } shouldNotBe null

            val videoContent = offer.find { it.name == "video" }
            videoContent shouldNotBe null
            videoContent!!.containsRtx() shouldBe true
        }
        context("Without audio and data") {
            val offer = jingleOfferFactory.createOffer(OfferOptions(audio = false, sctp = false))

            offer.find { it.name == "audio" } shouldBe null
            offer.find { it.name == "video" } shouldNotBe null
            offer.find { it.name == "data" } shouldBe null
        }
        context("Without RTX") {
            val offer = jingleOfferFactory.createOffer(OfferOptions(rtx = false))
            val videoContent = offer.find { it.name == "video" }

            videoContent shouldNotBe null
            videoContent!!.containsRtx() shouldBe false
        }
    }

    private fun ContentPacketExtension.containsRtx() =
        getChildExtensionsOfType(RtpDescriptionPacketExtension::class.java).any {
            it.getChildExtensionsOfType(PayloadTypePacketExtension::class.java).any { it.name == "rtx" }
        }
}
