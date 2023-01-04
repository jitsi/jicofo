/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.jicofo

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class ConferenceRequestTest : ShouldSpec() {
    init {
        val conferenceRequest = ConferenceRequest(
            room = "r@example.com",
            ready = true,
            sessionId = "s",
            identity = "i",
            machineUid = "m",
            vnode = "v",
            focusJid = "f",
            properties = mutableMapOf("k1" to "v1", "k2" to "v2")
        )

        fun ConferenceRequest.shouldEqual(other: ConferenceRequest) {
            room shouldBe other.room
            ready shouldBe other.ready
            sessionId shouldBe other.sessionId
            identity shouldBe other.identity
            machineUid shouldBe other.machineUid
            vnode shouldBe other.vnode
            focusJid shouldBe other.focusJid
            properties.toMap() shouldBe other.properties.toMap()
        }

        context("To JSON") {
            ConferenceRequest(room = "r@example.com").toJson() shouldBe "{\"room\":\"r@example.com\"}"
            ConferenceRequest(room = "r@example.com", ready = false).toJson() shouldBe
                "{\"room\":\"r@example.com\",\"ready\":false}"

            val parsed = ConferenceRequest.parseJson(conferenceRequest.toJson())
            parsed.shouldEqual(conferenceRequest)
        }
        context("Parse JSON") {
            val parsed = ConferenceRequest.parseJson(
                """
                {
                    "room": "r@example.com",
                    "ready": true,
                    "sessionId": "s",
                    "identity": "i",
                    "machineUid": "m",
                    "vnode": "v",
                    "focusJid": "f",
                    "properties": { "k1": "v1", "k2": "v2" }
                }
                """.trimIndent()
            )
            parsed.shouldEqual(conferenceRequest)
        }
        context("To ConferenceIq") {
            conferenceRequest.toConferenceIq().let {
                it.room.toString() shouldBe "r@example.com"
                it.isReady shouldBe true
                it.focusJid shouldBe "f"
                it.identity shouldBe "i"
                it.machineUID shouldBe "m"
                it.sessionId shouldBe "s"
                it.vnode shouldBe "v"
                it.properties.associate { Pair(it.name, it.value) } shouldBe mapOf("k1" to "v1", "k2" to "v2")
            }
        }
        context("From ConferenceIq") {
            val conferenceIq = conferenceRequest.toConferenceIq()
            ConferenceRequest.fromConferenceIq(conferenceIq).shouldEqual(conferenceRequest)
        }
    }
}
