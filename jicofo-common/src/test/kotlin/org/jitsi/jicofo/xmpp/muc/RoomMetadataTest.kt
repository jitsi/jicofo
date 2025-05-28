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
            context("With visitors.live set") {
                val parsed = RoomMetadata.parse(
                    """
                {
                    "type": "room_metadata",
                     "metadata": {
                        "visitors": {
                            "live": true,
                            "anotherField": 123
                        },
                        "anotherField": {}
                     }
                }
                    """.trimIndent()
                )
                parsed.shouldBeInstanceOf<RoomMetadata>()
                parsed.metadata!!.visitors!!.live shouldBe true
            }
            context("With no visitors included") {

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
                parsed.metadata!!.visitors shouldBe null
            }
            context("With visitors, mainMeetingParticipants, and startMuted") {
                RoomMetadata.parse(
                    """
                    {
                        "metadata": {
                            "transcriberType": "EGHT_WHISPER",
                            "visitors": {
                                "live": true
                            },
                            "mainMeetingParticipants": [
                                "user_id_1",
                                "user_id_2"
                            ],
                            "startMuted": {
                                "audio": true
                            }
                        },
                        "type": "room_metadata"
                    }
                    """.trimIndent()
                ).apply {
                    shouldBeInstanceOf<RoomMetadata>()
                    metadata.apply {
                        shouldNotBeNull()
                        visitors.apply {
                            shouldNotBeNull()
                            live shouldBe true
                        }
                        mainMeetingParticipants shouldBe listOf("user_id_1", "user_id_2")
                        startMuted.apply {
                            shouldNotBeNull()
                            audio shouldBe true
                            video shouldBe null
                        }
                    }
                    type shouldBe "room_metadata"
                }
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
