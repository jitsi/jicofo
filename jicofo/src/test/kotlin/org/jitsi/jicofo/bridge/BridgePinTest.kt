/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022-Present 8x8, Inc.
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
package org.jitsi.jicofo.bridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.jicofo.FocusManager
import org.jitsi.utils.time.FakeClock
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration

/**
 * Test conference pin operations.
 */
class BridgePinTest : ShouldSpec() {

    private val conf1 = JidCreate.entityBareFrom("test@conference.meet.jit.si")
    private val conf2 = JidCreate.entityBareFrom("hope@conference.meet.jit.si")
    private val conf3 = JidCreate.entityBareFrom("pray@conference.meet.jit.si")
    private val v1 = "1.1.1"
    private val v2 = "2.2.2"
    private val v3 = "3.3.3"

    init {
        context("basic functionality") {
            val clock = FakeClock()
            val focusManager = FocusManager(mockk(), clock)

            focusManager.pinConference(conf1, v1, Duration.ofMinutes(10))
            focusManager.pinConference(conf2, v2, Duration.ofMinutes(12))
            focusManager.pinConference(conf3, v3, Duration.ofMinutes(14))

            should("pin correctly") {
                focusManager.getPinnedConferences().size shouldBe 3
                focusManager.getBridgeVersionForConference(conf1) shouldBe v1
                focusManager.getBridgeVersionForConference(conf2) shouldBe v2
                focusManager.getBridgeVersionForConference(conf3) shouldBe v3
            }

            should("expire") {
                clock.elapse(Duration.ofMinutes(11))
                focusManager.getPinnedConferences().size shouldBe 2
                focusManager.getBridgeVersionForConference(conf1) shouldBe null
                focusManager.getBridgeVersionForConference(conf2) shouldBe v2
                focusManager.getBridgeVersionForConference(conf3) shouldBe v3

                clock.elapse(Duration.ofMinutes(2))
                focusManager.getPinnedConferences().size shouldBe 1
                focusManager.getBridgeVersionForConference(conf1) shouldBe null
                focusManager.getBridgeVersionForConference(conf2) shouldBe null
                focusManager.getBridgeVersionForConference(conf3) shouldBe v3

                clock.elapse(Duration.ofMinutes(2))
                focusManager.getPinnedConferences().size shouldBe 0
                focusManager.getBridgeVersionForConference(conf1) shouldBe null
                focusManager.getBridgeVersionForConference(conf2) shouldBe null
                focusManager.getBridgeVersionForConference(conf3) shouldBe null
            }
        }
        context("modifications") {
            val clock = FakeClock()
            val focusManager = FocusManager(mockk(), clock)

            focusManager.pinConference(conf1, v1, Duration.ofMinutes(10))
            focusManager.pinConference(conf2, v2, Duration.ofMinutes(12))
            focusManager.pinConference(conf3, v3, Duration.ofMinutes(14))

            should("unpin") {
                focusManager.unpinConference(conf3)
                focusManager.getPinnedConferences().size shouldBe 2
                focusManager.getBridgeVersionForConference(conf1) shouldBe v1
                focusManager.getBridgeVersionForConference(conf2) shouldBe v2
                focusManager.getBridgeVersionForConference(conf3) shouldBe null
            }

            should("modify version and timeout") {
                clock.elapse(Duration.ofMinutes(4))
                focusManager.pinConference(conf1, v3, Duration.ofMinutes(10))
                focusManager.getPinnedConferences().size shouldBe 2
                focusManager.getBridgeVersionForConference(conf1) shouldBe v3
                focusManager.getBridgeVersionForConference(conf2) shouldBe v2
                focusManager.getBridgeVersionForConference(conf3) shouldBe null

                clock.elapse(Duration.ofMinutes(9))
                focusManager.getPinnedConferences().size shouldBe 1
                focusManager.getBridgeVersionForConference(conf1) shouldBe v3
                focusManager.getBridgeVersionForConference(conf2) shouldBe null
                focusManager.getBridgeVersionForConference(conf3) shouldBe null

                clock.elapse(Duration.ofMinutes(2))
                focusManager.getPinnedConferences().size shouldBe 0
                focusManager.getBridgeVersionForConference(conf1) shouldBe null
                focusManager.getBridgeVersionForConference(conf2) shouldBe null
                focusManager.getBridgeVersionForConference(conf3) shouldBe null
            }
        }
    }
}
