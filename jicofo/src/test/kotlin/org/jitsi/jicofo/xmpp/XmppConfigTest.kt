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
package org.jitsi.jicofo.xmpp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.config.withLegacyConfig
import org.jitsi.config.withNewConfig
import org.jitsi.metaconfig.ConfigException
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart

class XmppConfigTest : ShouldSpec() {
    init {
        context("XMPP client connection config") {
            context("Default values") {
                val config = XmppClientConnectionConfig()
                config.enabled shouldBe true
                config.hostname shouldBe "localhost"
                config.port shouldBe 5222
                shouldThrow<ConfigException> {
                    config.domain
                }
                config.username shouldBe Resourcepart.from("focus")
                config.password shouldBe null
            }
            context("Legacy config") {
                withLegacyConfig(
                    """
                    org.jitsi.jicofo.HOSTNAME=hostname
                    org.jitsi.jicofo.XMPP_PORT=5223
                    org.jitsi.jicofo.FOCUS_USER_DOMAIN=domain
                    org.jitsi.jicofo.FOCUS_USER_NAME=user
                    org.jitsi.jicofo.FOCUS_USER_PASSWORD=pass
                """
                ) {
                    val config = XmppClientConnectionConfig()
                    config.enabled shouldBe true
                    config.hostname shouldBe "hostname"
                    config.port shouldBe 5223
                    config.domain shouldBe JidCreate.domainBareFrom("domain")
                    config.username shouldBe Resourcepart.from("user")
                    config.password shouldBe "pass"
                }
            }
            context("New config") {
                withNewConfig(
                    """
                    jicofo.xmpp.client {
                        hostname = "hostname2"
                        port = 5224
                        domain = "domain2"
                        username = "user2"
                        password = "pass2"
                    }
                """
                ) {
                    val config = XmppClientConnectionConfig()
                    config.enabled shouldBe true
                    config.hostname shouldBe "hostname2"
                    config.port shouldBe 5224
                    config.domain shouldBe JidCreate.domainBareFrom("domain2")
                    config.username shouldBe Resourcepart.from("user2")
                    config.password shouldBe "pass2"
                }
            }
        }
        context("XMPP service connection config") {
            context("Default values") {
                val config = XmppServiceConnectionConfig()
                config.enabled shouldBe false
                config.hostname shouldBe "localhost"
                config.port shouldBe 6222
                shouldThrow<ConfigException> {
                    config.domain
                }
                config.username shouldBe Resourcepart.from("focus")
                config.password shouldBe null
            }
            context("Legacy config") {
                withLegacyConfig(
                    """
                    org.jitsi.jicofo.BRIDGE_MUC_XMPP_HOST=hostname
                    org.jitsi.jicofo.BRIDGE_MUC_XMPP_PORT=6223
                    org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER_DOMAIN=domain
                    org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER=user
                    org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER_PASS=pass
                """
                ) {
                    val config = XmppServiceConnectionConfig()
                    config.enabled shouldBe true
                    config.hostname shouldBe "hostname"
                    config.port shouldBe 6223
                    config.domain shouldBe JidCreate.domainBareFrom("domain")
                    config.username shouldBe Resourcepart.from("user")
                    config.password shouldBe "pass"
                }
            }
            context("New config") {
                withNewConfig(
                    """
                    jicofo.xmpp.service {
                        enabled = true
                        hostname = "hostname2"
                        port = 6224
                        domain = "domain2"
                        username = "user2"
                        password = "pass2"
                    }
                """
                ) {
                    val config = XmppServiceConnectionConfig()
                    config.enabled shouldBe true
                    config.hostname shouldBe "hostname2"
                    config.port shouldBe 6224
                    config.domain shouldBe JidCreate.domainBareFrom("domain2")
                    config.username shouldBe Resourcepart.from("user2")
                    config.password shouldBe "pass2"
                }
            }
        }
        context("Visitor connections config") {
            context("Default") {
                XmppConfig.visitors.shouldBeEmpty()
            }
            context("With visitors configured") {
                withNewConfig(
                    """
                    jicofo.xmpp.visitors {
                        v1 {
                            hostname = "hostname1"
                            port = 5223
                            domain = "domain1"
                            username = "user1"
                            password = "pass1"
                            conference-service = conference.v1.example.com
                        }
                        v2 {
                            hostname = "hostname2"
                            port = 5224
                            domain = "domain2"
                            username = "user2"
                            password = "pass2"
                            conference-service = conference.v2.example.com
                        }
                    }
                """
                ) {
                    XmppConfig.visitors.size shouldBe 2
                    XmppConfig.visitors.keys shouldBe setOf("v1", "v2")
                    XmppConfig.visitors["v1"].let {
                        it shouldNotBe null
                        it!!.hostname shouldBe "hostname1"
                        it.port shouldBe 5223
                        it.domain shouldBe "domain1"
                        it.username shouldBe Resourcepart.from("user1")
                        it.password shouldBe "pass1"
                        it.conferenceService shouldBe JidCreate.domainBareFrom("conference.v1.example.com")
                    }
                    XmppConfig.visitors["v2"].let {
                        it shouldNotBe null
                        it!!.hostname shouldBe "hostname2"
                        it.port shouldBe 5224
                        it.domain shouldBe "domain2"
                        it.username shouldBe Resourcepart.from("user2")
                        it.password shouldBe "pass2"
                        it.conferenceService shouldBe JidCreate.domainBareFrom("conference.v2.example.com")
                    }
                }
            }
        }
    }
}
