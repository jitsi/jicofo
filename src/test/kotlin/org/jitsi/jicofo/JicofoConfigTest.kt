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
package org.jitsi.jicofo

import io.kotlintest.shouldBe
import org.jitsi.jicofo.JicofoConfig.Companion.config

class JicofoConfigTest : ConfigTest() {
    init {
        "Local region config" {
            "With no config" {
                config.localRegion shouldBe null
            }
            "With legacy config" {
                withLegacyConfig("org.jitsi.jicofo.BridgeSelector.LOCAL_REGION=legacy") {
                    config.localRegion shouldBe "legacy"
                }
            }
            "With new config" {
                withNewConfig("jicofo { local-region=new }") {
                    config.localRegion shouldBe "new"
                }
            }
            "With both new and legacy" {
                withNewConfig("jicofo { local-region=new }") {
                    withLegacyConfig("org.jitsi.jicofo.BridgeSelector.LOCAL_REGION=legacy") {
                        config.localRegion shouldBe "legacy"
                    }
                }
            }
        }

        "SCTP" {
            "Should be enabled by default" {
                config.enableSctp shouldBe true
            }
            "Should be disabled with new config" {
                withNewConfig("jicofo { sctp { enabled=false } }") {
                    config.enableSctp shouldBe false
                }
            }
        }
    }
}
