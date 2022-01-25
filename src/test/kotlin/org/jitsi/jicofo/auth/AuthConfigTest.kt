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
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withLegacyConfig
import org.jitsi.config.withNewConfig
import java.time.Duration

class AuthConfigTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        context("Default values") {
            AuthConfig.config.apply {
                enabled shouldBe false
                enableAutoLogin shouldBe true
                shouldThrow<Exception> { loginUrl }
                logoutUrl shouldBe null
                type shouldBe AuthConfig.Type.SHIBBOLETH
                authenticationLifetime shouldBe Duration.ofHours(24)
            }
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
                AuthConfig.config.apply {
                    enabled shouldBe true
                    enableAutoLogin shouldBe false
                    loginUrl shouldBe "test@example.com"
                    logoutUrl shouldBe "logout"
                    type shouldBe AuthConfig.Type.XMPP
                    authenticationLifetime shouldBe Duration.ofMillis(60000)
                }
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
                AuthConfig.config.apply {
                    enabled shouldBe false
                    enableAutoLogin shouldBe false
                    loginUrl shouldBe "login"
                    logoutUrl shouldBe "logout"
                    type shouldBe AuthConfig.Type.JWT
                    authenticationLifetime shouldBe Duration.ofMinutes(5)
                }
            }
        }
    }
}
