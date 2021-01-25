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
package org.jitsi.jicofo.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.ConfigTest
import java.time.Duration

class AuthConfigTest : ConfigTest() {
    init {
        context("Default values") {
            val config = AuthConfig()
            config.enabled shouldBe false
            config.enableAutoLogin shouldBe true
            shouldThrow<Exception> {
                config.loginUrl
            }
            config.logoutUrl shouldBe null
            config.type shouldBe AuthConfig.Type.SHIBBOLETH
            config.authenticationLifetime shouldBe Duration.ofHours(24)
        }
        context("With legacy config") {
            withLegacyConfig(
                """
                org.jitsi.jicofo.auth.URL=XMPP:test@example.com
                org.jitsi.jicofo.auth.LOGOUT_URL=logout
                org.jitsi.jicofo.auth.DISABLE_AUTOLOGIN=true
                org.jitsi.jicofo.auth.AUTH_LIFETIME=60000
                """
            ) {
                val config = AuthConfig()
                config.enabled shouldBe true
                config.enableAutoLogin shouldBe false
                config.loginUrl shouldBe "test@example.com"
                config.logoutUrl shouldBe "logout"
                config.type shouldBe AuthConfig.Type.XMPP
                config.authenticationLifetime shouldBe Duration.ofMillis(60000)
            }
        }
        context("With new config") {
            withNewConfig(
                """
                jicofo.authentication {
                  enabled = false
                  type = JWT
                  login-url = login
                  logout-url = logout
                  enable-auto-login = false
                  authentication-lifetime = 5 minutes
                }
            """
            ) {
                val config = AuthConfig()

                config.enabled shouldBe false
                config.enableAutoLogin shouldBe false
                config.loginUrl shouldBe "login"
                config.logoutUrl shouldBe "logout"
                config.type shouldBe AuthConfig.Type.JWT
                config.authenticationLifetime shouldBe Duration.ofMinutes(5)
            }
        }
    }
}
