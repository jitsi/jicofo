/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import java.time.Duration

class AuthConfig private constructor() {
    private val enabled: Boolean by config {
        // Enabled if the URL is set to anything
        LEGACY_LOGIN_URL_PROPERTY_NAME.from(legacyConfig).convertFrom<String> { true }
        // Read the legacy key from newConfig in case it was set as a System property.
        LEGACY_LOGIN_URL_PROPERTY_NAME.from(newConfig).convertFrom<String> { true }
        "jicofo.authentication.enabled".from(newConfig)
    }

    val loginUrl: String by config {
        LEGACY_LOGIN_URL_PROPERTY_NAME.from(legacyConfig).convertFrom<String> { it.stripType() }
        // Read the legacy key from newConfig in case it was set as a System property.
        LEGACY_LOGIN_URL_PROPERTY_NAME.from(newConfig).convertFrom<String> { it.stripType() }
        "jicofo.authentication.login-url".from(newConfig)
    }

    val authenticationLifetime: Duration by config {
        "org.jitsi.jicofo.auth.AUTH_LIFETIME".from(legacyConfig).convertFrom<Long> {
            Duration.ofMillis(it)
        }
        "jicofo.authentication.authentication-lifetime".from(newConfig)
    }

    val enableAutoLogin: Boolean by config {
        "org.jitsi.jicofo.auth.DISABLE_AUTOLOGIN".from(legacyConfig).transformedBy { !it }
        "jicofo.authentication.enable-auto-login".from(newConfig)
    }

    val type: Type
        get() = if (enabled) typeProperty else Type.NONE

    private val typeProperty: Type by config {
        "org.jitsi.jicofo.auth.URL".from(legacyConfig).convertFrom<String> {
            when {
                it.startsWith("XMPP:") -> Type.XMPP
                it.startsWith("EXT_JWT:") -> Type.JWT
                else -> Type.NONE
            }
        }
        "jicofo.authentication.type".from(newConfig)
    }

    override fun toString(): String {
        return "AuthConfig[enabled=$enabled, type=$type, loginUrl=$loginUrl, " +
            "authenticationLifetime=$authenticationLifetime, enableAutoLogin=$enableAutoLogin]"
    }

    companion object {
        @JvmField
        val config = AuthConfig()

        const val LEGACY_LOGIN_URL_PROPERTY_NAME = "org.jitsi.jicofo.auth.URL"
    }

    enum class Type {
        XMPP,
        JWT,
        NONE
    }
}

/**
 * Strip the type encoded in the login URL in legacy properties.
 */
private fun String.stripType(): String = when {
    this.startsWith("XMPP:") -> this.substring("XMPP:".length)
    this.startsWith("EXT_JWT:") -> this.substring("EXT_JWT:".length)
    else -> this
}
