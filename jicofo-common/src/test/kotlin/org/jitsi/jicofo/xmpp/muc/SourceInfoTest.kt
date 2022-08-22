/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp.muc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.conference.source.VideoType
import org.json.simple.parser.ParseException

class SourceInfoTest : ShouldSpec() {
    init {
        context("Parsing invalid strings") {
            shouldThrow<ParseException> {
                parseSourceInfoJson("")
            }
            shouldThrow<ParseException> {
                parseSourceInfoJson("{")
            }
            shouldThrow<IllegalArgumentException> {
                parseSourceInfoJson(
                    """
                        { "a" : { "videoType": 5 } }
                    """.trimIndent()
                )
            }
            shouldThrow<IllegalArgumentException> {
                parseSourceInfoJson(
                    """
                        { "a" : { "videoType": "x" } }
                    """.trimIndent()
                )
            }
        }
        context("Parsing valid strings") {
            parseSourceInfoJson("{}") shouldBe emptySet()

            parseSourceInfoJson(
                """
                {
                  "3b554cf4-a0": {
                    "muted": false
                  },
                  "3b554cf4-v0": {
                  },
                  "3b554cf4-v1": {
                    "muted": true,
                    "videoType": "desktop"
                  }
                }
                """.trimIndent()
            ).shouldBe(
                setOf(
                    SourceInfo("3b554cf4-a0", false, null),
                    SourceInfo("3b554cf4-v0", true, null),
                    SourceInfo("3b554cf4-v1", true, VideoType.Desktop),
                )
            )
        }
    }
}
