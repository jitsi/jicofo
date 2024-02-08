/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024-Present 8x8, Inc.
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RoomMetadataTest : ShouldSpec() {
    init {
        context("Valid") {
            context("With isTranscribingEnabled set") {
                val parsed = RoomMetadata.parse(
                    """
                {
                    "type": "room_metadata",
                     "metadata": {
                        "recording": {
                            "isTranscribingEnabled": true,
                            "anotherField": 123
                        },
                        "anotherField": {}
                     }
                }
                    """.trimIndent()
                )
                parsed.shouldBeInstanceOf<RoomMetadata>()
                parsed.metadata!!.recording!!.isTranscribingEnabled shouldBe true
            }
            context("With no recording included") {

                val parsed = RoomMetadata.parse(
                    """
                {
                    "type": "room_metadata",
                     "metadata": {
                        "key": {
                            "key2": "value2"
                        },
                        "anotherField": {}
                     }
                }
                    """.trimIndent()
                )
                parsed.shouldBeInstanceOf<RoomMetadata>()
                parsed.metadata.shouldNotBeNull()
                parsed.metadata?.recording shouldBe null
            }
        }
        context("Invalid") {
            context("Missing type") {
                shouldThrow<Exception> {
                    RoomMetadata.parse(
                        """
                        { "key": 123 }
                        """.trimIndent()
                    )
                }
            }
            context("Invalid JSON") {
                shouldThrow<Exception> {
                    RoomMetadata.parse("{")
                }
            }
        }
    }
}
