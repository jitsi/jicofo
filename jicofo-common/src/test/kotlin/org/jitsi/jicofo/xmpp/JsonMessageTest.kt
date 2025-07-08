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
package org.jitsi.jicofo.xmpp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.jicofo.MediaType

class JsonMessageTest : ShouldSpec() {
    init {
        context("RoomMetadata") {
            context("Valid") {
                context("With visitors.live set") {
                    val parsed = JsonMessage.parse(
                        """
                            {
                                "type": "room_metadata",
                                "metadata": {
                                    "visitors": {
                                        "live": true,
                                        "anotherField": 123
                                    }
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

                    val parsed = JsonMessage.parse(
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
                    JsonMessage.parse(
                        """
                            {
                                "metadata": {
                                    "transcriberType": "EGHT_WHISPER",
                                    "visitors": {
                                        "live": true
                                    },
                                    "moderators": [
                                        "user_id_1",
                                        "user_id_2"
                                    ],
                                    "participants": [
                                        "user_id_3",
                                        "user_id_4"
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
                            moderators shouldBe listOf("user_id_1", "user_id_2")
                            participants shouldBe listOf("user_id_3", "user_id_4")
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
                        JsonMessage.parse(
                            """
                                { "key": 123 }
                            """.trimIndent()
                        )
                    }
                }
                context("Invalid JSON") {
                    shouldThrow<Exception> {
                        JsonMessage.parse("{")
                    }
                }
            }
        }

        context("AvModerationMessage") {
            context("Valid") {
                context("With minimal fields") {
                    val parsed = JsonMessage.parse(
                        """
                        {
                            "type": "av_moderation",
                            "room": "room1@conference.example.com"
                        }
                        """.trimIndent()
                    )
                    parsed.shouldBeInstanceOf<AvModerationMessage>()
                    parsed.room shouldBe "room1@conference.example.com"
                    parsed.enabled shouldBe null
                    parsed.mediaType shouldBe null
                    parsed.actor shouldBe null
                    parsed.whitelists shouldBe null
                }

                context("With all fields") {
                    val parsed = JsonMessage.parse(
                        """
                        {
                            "type": "av_moderation",
                            "room": "room1@conference.example.com",
                            "enabled": true,
                            "mediaType": "AUDIO",
                            "actor": "user1@example.com",
                            "whitelists": {
                                "AUDIO": ["user2@example.com", "user3@example.com"],
                                "VIDEO": ["user2@example.com"]
                            }
                        }
                        """.trimIndent()
                    )
                    parsed.shouldBeInstanceOf<AvModerationMessage>()
                    parsed.room shouldBe "room1@conference.example.com"
                    parsed.enabled shouldBe true
                    parsed.mediaType shouldBe MediaType.AUDIO
                    parsed.actor shouldBe "user1@example.com"
                    parsed.whitelists.shouldNotBeNull()
                    parsed.whitelists!![MediaType.AUDIO] shouldBe listOf("user2@example.com", "user3@example.com")
                    parsed.whitelists!![MediaType.VIDEO] shouldBe listOf("user2@example.com")
                    // parsed.whitelists!![MediaType.DESKTOP] shouldBe null
                }

                context("With lowercase enum values") {
                    val parsed = JsonMessage.parse(
                        """
                        {
                            "type": "av_moderation",
                            "room": "room1@conference.example.com",
                            "mediaType": "video",
                            "whitelists": {
                                "audio": ["user1@example.com"]
                            }
                        }
                        """.trimIndent()
                    )
                    parsed.shouldBeInstanceOf<AvModerationMessage>()
                    parsed.mediaType shouldBe MediaType.VIDEO
                    parsed.whitelists.shouldNotBeNull()
                    parsed.whitelists!![MediaType.AUDIO] shouldBe listOf("user1@example.com")
                    // parsed.whitelists!![AvModerationMessage.MediaType.DESKTOP] shouldBe listOf("user2@example.com")
                }

                context("With mixed case enum values") {
                    val parsed = JsonMessage.parse(
                        """
                        {
                            "type": "av_moderation",
                            "room": "room1@conference.example.com",
                            "mediaType": "vIdEo",
                            "whitelists": {
                                "AuDiO": ["user1@example.com"],
                                "ViDeO": ["user2@example.com"]
                            }
                        }
                        """.trimIndent()
                    )
                    parsed.shouldBeInstanceOf<AvModerationMessage>()
                    parsed.mediaType shouldBe MediaType.VIDEO
                    parsed.whitelists.shouldNotBeNull()
                    parsed.whitelists!![MediaType.AUDIO] shouldBe listOf("user1@example.com")
                    parsed.whitelists!![MediaType.VIDEO] shouldBe listOf("user2@example.com")
                }
            }

            context("Invalid") {
                context("Invalid media type") {
                    shouldThrow<Exception> {
                        JsonMessage.parse(
                            """
                            {
                                "type": "av_moderation",
                                "room": "room1@conference.example.com",
                                "mediaType": "INVALID_TYPE"
                            }
                            """.trimIndent()
                        )
                    }
                }
            }
        }
    }
}
