/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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
package org.jitsi.jicofo.jibri

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.jicofo.xmpp.XmppConnectionEnum

class JibriConfigTest : ShouldSpec() {
    init {
        context("xmpp-connection-name") {
            should("default to Client for both jibri and SIP jibri") {
                JibriConfig.config.xmppConnectionName shouldBe XmppConnectionEnum.Client
                JibriConfig.config.sipXmppConnectionName shouldBe XmppConnectionEnum.Client
            }
            should("use the Service connection for SIP jibri when configured") {
                withNewConfig("jicofo.jibri-sip.xmpp-connection-name = Service") {
                    JibriConfig.config.sipXmppConnectionName shouldBe XmppConnectionEnum.Service
                    JibriConfig.config.xmppConnectionName shouldBe XmppConnectionEnum.Client
                }
            }
            should("configure the SIP jibri connection independently of the jibri connection") {
                withNewConfig("jicofo.jibri.xmpp-connection-name = Service") {
                    JibriConfig.config.xmppConnectionName shouldBe XmppConnectionEnum.Service
                    JibriConfig.config.sipXmppConnectionName shouldBe XmppConnectionEnum.Client
                }
            }
        }
    }
}
