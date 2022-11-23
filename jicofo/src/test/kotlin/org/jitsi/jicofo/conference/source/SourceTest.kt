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
package org.jitsi.jicofo.conference.source

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension

class SourceTest : ShouldSpec() {
    init {
        context("From XML") {
            val packetExtension = SourcePacketExtension().apply {
                ssrc = 1
                name = "name-1"
                videoType = "camera"
                addChildExtension(ParameterPacketExtension("msid", "msid"))
            }

            Source(MediaType.VIDEO, packetExtension) shouldBe
                Source(1, MediaType.VIDEO, name = "name-1", msid = "msid", videoType = VideoType.Camera)
        }
        context("To XML") {
            val msidValue = "msid-value"
            val nameValue = "source-name-value"
            val videoType = VideoType.Desktop
            val source = Source(1, MediaType.VIDEO, name = nameValue, msid = msidValue, videoType = videoType)
            val owner = "abcdabcd"
            val extension = source.toPacketExtension(owner = owner)

            extension.ssrc shouldBe 1
            extension.name shouldBe nameValue
            extension.videoType shouldBe videoType.toString()
            val parameters = extension.getChildExtensionsOfType(ParameterPacketExtension::class.java)
            parameters.filter { it.name == "msid" && it.value == msidValue }.size shouldBe 1

            val ssrcInfo = extension.getFirstChildOfType(SSRCInfoPacketExtension::class.java)
            ssrcInfo shouldNotBe null
            ssrcInfo.owner.toString() shouldBe owner
        }
        context("Compact JSON") {
            Source(1, MediaType.VIDEO, name = "test-name", msid = "msid").compactJson shouldBe
                """
                {"s":1,"n":"test-name","m":"msid"}
                """.trimIndent()
            Source(
                1,
                MediaType.VIDEO,
                videoType = VideoType.Desktop,
                name = "test-name",
                msid = "msid"
            ).compactJson shouldBe
                """
                {"s":1,"n":"test-name","m":"msid","v":"d"}
                """.trimIndent()
            Source(1, MediaType.AUDIO).compactJson shouldBe """
            {"s":1}
            """.trimIndent()
        }
    }
}
