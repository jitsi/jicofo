/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

import org.jitsi.metaconfig.config
import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.optionalconfig
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.time.Duration

class XmppConfig {
    val serviceConnectionConfig = ServiceConnectionConfig()

    val clientConnectionConfig = ClientConnectionConfig()

    companion object {
        @JvmField
        val xmppConfig = XmppConfig()
    }
}

class ServiceConnectionConfig {
    private val enabled: Boolean by config {
        // If the legacy host is set to anything, the connection is enabled.
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_HOST".from(legacyConfig).convertFrom<String> { true }
        "jicofo.xmpp.service.enabled".from(newConfig)
    }
    fun enabled() = enabled

    val hostname: String by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_HOST".from(legacyConfig)
        "jicofo.xmpp.service.hostname".from(newConfig)
    }

    val port: Int by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_PORT".from(legacyConfig)
        "jicofo.xmpp.service.port".from(newConfig)
    }

    val domain: DomainBareJid by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER_DOMAIN".from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        "jicofo.xmpp.service.domain".from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
    }

    val username: Resourcepart by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER".from(legacyConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
        "jicofo.xmpp.service.username".from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
    }

    val password: String by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER_PASS".from(legacyConfig)
        "jicofo.xmpp.service.password".from(newConfig)
    }

    val replyTimeout: Duration by config {
        "jicofo.xmpp.service.reply-timeout".from(newConfig)
    }
}

class ClientConnectionConfig {
    private val enabled: Boolean by config {
        // If the legacy host is set to anything, the connection is enabled.
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyHostnamePropertyName.from(newConfig)
        legacyHostnamePropertyName.from(legacyConfig)
        "jicofo.xmpp.client.enabled".from(newConfig)
    }
    fun enabled() = enabled

    val hostname: String by config {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyHostnamePropertyName.from(newConfig)
        legacyHostnamePropertyName.from(legacyConfig)
        "jicofo.xmpp.client.hostname".from(newConfig)
    }

    val port: Int by config {
        "org.jitsi.jicofo.XMPP_PORT".from(legacyConfig)
        "jicofo.xmpp.client.port".from(newConfig)
    }

    /**
     * This is the domain used for login. Not necessarily the root XMPP domain.
     */
    val domain: DomainBareJid by config {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyDomainPropertyName.from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        legacyDomainPropertyName.from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        "jicofo.xmpp.client.domain".from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
    }

    val username: Resourcepart by config {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyUsernamePropertyName.from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
        legacyUsernamePropertyName.from(legacyConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
        "jicofo.xmpp.client.username".from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
    }

    val password: String? by optionalconfig {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyPasswordPropertyName.from(newConfig)
        legacyPasswordPropertyName.from(legacyConfig)
        "jicofo.xmpp.client.password".from(newConfig)
    }

    companion object {
        const val legacyHostnamePropertyName = "org.jitsi.jicofo.HOSTNAME"
        const val legacyDomainPropertyName = "org.jitsi.jicofo.FOCUS_USER_DOMAIN"
        const val legacyUsernamePropertyName = "org.jitsi.jicofo.FOCUS_USER_NAME"
        const val legacyPasswordPropertyName = "org.jitsi.jicofo.FOCUS_USER_PASSWORD"
    }
}
