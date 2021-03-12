/*
 * Copyright @ 2020 - present 8x8, Inc.
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
import io.kotest.matchers.ints.shouldBeLessThan
import io.mockk.every
import io.mockk.mockk

class BridgeTest : ShouldSpec({
    context("when comparing two bridges") {
        should("the bridge that is operational should have higher priority (should compare lower)") {
            val bridge1: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { stress } returns 10.0
            }

            val bridge2: Bridge = mockk {
                every { isOperational } returns false
                every { isInGracefulShutdown } returns true
                every { stress } returns .1
            }
            Bridge.compare(bridge1, bridge2) shouldBeLessThan 0
        }

        should("the bridge that is in graceful shutdown mode should have lower priority (should compare higher)") {
            val bridge1: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { stress } returns 10.0
            }

            val bridge2: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns true
                every { stress } returns .1
            }
            Bridge.compare(bridge1, bridge2) shouldBeLessThan 0
        }

        should("the bridge that is stressed should have lower priority (should compare higher)") {
            val bridge1: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { stress } returns 10.0
            }

            val bridge2: Bridge = mockk {
                every { isOperational } returns true
                every { isInGracefulShutdown } returns false
                every { stress } returns .1
            }
            Bridge.compare(bridge2, bridge1) shouldBeLessThan 0
        }
    }
})
