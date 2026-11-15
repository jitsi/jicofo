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
package org.jitsi.jicofo.conference

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.codec.JingleOfferFactory
import org.jitsi.jicofo.codec.OfferOptions
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension

class ConferenceUtilTest : ShouldSpec() {
    private fun PayloadTypePacketExtension.attributes() = attributeNames.map { it to getAttribute(it)?.toString() }

    private fun PayloadTypePacketExtension.parameters() = parameters.map { it.name to it.value }

    private fun PayloadTypePacketExtension.rtcpFb() =
        rtcpFeedbackTypeList.map { listOfNotNull(it.feedbackType, it.feedbackSubtype).joinToString(" ") }

    private fun PayloadTypePacketExtension.hasCcfb() =
        rtcpFeedbackTypeList.any { it.feedbackType == "ack" && it.feedbackSubtype == "ccfb" }

    /** The media descriptions sent to the bridge for an offer with the given options, and the offer. */
    private fun mediaFor(options: OfferOptions) = JingleOfferFactory.INSTANCE.createOffer(options).let { offer ->
        offer.filter { it.name == "audio" || it.name == "video" }.map { it.toMedia().shouldNotBeNull() } to offer
    }

    init {
        context("The media described to the bridge for an offer with RFC 8888 feedback") {
            val (medias, offer) = mediaFor(OfferOptions(ccfb = true, tcc = false))

            should("signal it with every payload type, including RTX") {
                medias.size shouldBe 2
                medias.forEach { media: Media ->
                    media.payloadTypes.shouldNotBeEmpty()
                    media.payloadTypes.filterNot { it.hasCcfb() }.map { it.name }.shouldBeEmpty()
                }
                medias.flatMap { it.payloadTypes }.map { it.name } shouldBe
                    offer.flatMap { it.getChildExtensionsOfType(RtpDescriptionPacketExtension::class.java) }
                        .flatMap { it.payloadTypes }.map { it.name }
                medias.flatMap { it.payloadTypes }.any { it.name == "rtx" } shouldBe true
            }
            should("change nothing else about the payload types") {
                val offered = offer.flatMap { it.getChildExtensionsOfType(RtpDescriptionPacketExtension::class.java) }
                    .flatMap { it.payloadTypes }
                val described = medias.flatMap { it.payloadTypes }

                described.size shouldBe offered.size
                described.zip(offered).forEach { (toBridge, inOffer) ->
                    toBridge.attributes() shouldBe inOffer.attributes()
                    toBridge.parameters() shouldBe inOffer.parameters()
                    toBridge.rtcpFb() shouldBe inOffer.rtcpFb() + "ack ccfb"
                }
                // What the bridge can not do without: RTX is tied to its codec, and video keeps its feedback.
                described.filter { it.name == "rtx" }.shouldNotBeEmpty()
                described.filter {
                    it.name == "rtx"
                }.forEach { it.parameters().map { p -> p.first } shouldContain "apt" }
                described.first { it.name == "VP8" }.rtcpFb() shouldContain "nack pli"
            }
            should("leave the payload types of the offer as they were") {
                offer.flatMap { it.getChildExtensionsOfType(RtpDescriptionPacketExtension::class.java) }
                    .flatMap { it.payloadTypes }.filter { it.hasCcfb() }.shouldBeEmpty()
            }
        }
        context("The media described to the bridge for an offer without RFC 8888 feedback") {
            val (medias, _) = mediaFor(OfferOptions())

            should("not signal it") {
                medias.size shouldBe 2
                medias.flatMap { it.payloadTypes }.filter { it.hasCcfb() }.shouldBeEmpty()
            }
        }
    }
}
