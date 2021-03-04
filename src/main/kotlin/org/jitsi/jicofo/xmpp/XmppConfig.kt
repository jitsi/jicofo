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

import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.time.Duration

class XmppConfig {
    companion object {
        @JvmField
        val service = XmppServiceConnectionConfig()

        @JvmField
        val client = XmppClientConnectionConfig()

        @JvmField
        val config = XmppConfig()
    }
}

interface XmppConnectionConfig {
    val enabled: Boolean
    val hostname: String
    val port: Int
    val domain: DomainBareJid
    val username: Resourcepart
    val password: String?
    val replyTimeout: Duration
    val disableCertificateVerification: Boolean
    val useTls: Boolean
    val name: String
}

class XmppServiceConnectionConfig : XmppConnectionConfig {
    override val enabled: Boolean by config {
        // If the legacy host is set to anything, the connection is enabled.
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_HOST".from(legacyConfig).convertFrom<String> { true }
        "jicofo.xmpp.service.enabled".from(newConfig)
    }

    override val hostname: String by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_HOST".from(legacyConfig)
        "jicofo.xmpp.service.hostname".from(newConfig)
    }

    override val port: Int by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_PORT".from(legacyConfig)
        "jicofo.xmpp.service.port".from(newConfig)
    }

    override val domain: DomainBareJid by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER_DOMAIN".from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        "jicofo.xmpp.service.domain".from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
    }

    override val username: Resourcepart by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER".from(legacyConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
        "jicofo.xmpp.service.username".from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
    }

    override val password: String? by optionalconfig {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER_PASS".from(legacyConfig)
        "jicofo.xmpp.service.password".from(newConfig)
    }

    override val replyTimeout: Duration by config {
        "jicofo.xmpp.service.reply-timeout".from(newConfig)
    }

    override val disableCertificateVerification: Boolean by config {
        "org.jitsi.jicofo.ALWAYS_TRUST_MODE_ENABLED".from(legacyConfig)
        "jicofo.xmpp.service.disable-certificate-verification".from(newConfig)
    }

    override val useTls: Boolean by config {
        "jicofo.xmpp.service.use-tls".from(newConfig)
    }

    override fun toString(): String = "XmppServiceConnectionConfig[hostname=$hostname, port=$port, username=$username]"

    override val name = "service"
}

class XmppClientConnectionConfig : XmppConnectionConfig {
    override val enabled: Boolean by config {
        // If the legacy host is set to anything, the connection is enabled.
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyHostnamePropertyName.from(newConfig).convertFrom<String> { true }
        legacyHostnamePropertyName.from(legacyConfig).convertFrom<String> { true }
        "jicofo.xmpp.client.enabled".from(newConfig)
    }

    override val hostname: String by config {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyHostnamePropertyName.from(newConfig)
        legacyHostnamePropertyName.from(legacyConfig)
        "jicofo.xmpp.client.hostname".from(newConfig)
    }

    override val port: Int by config {
        "org.jitsi.jicofo.XMPP_PORT".from(legacyConfig)
        "jicofo.xmpp.client.port".from(newConfig)
    }

    /**
     * This is the domain used for login. Not necessarily the root XMPP domain.
     */
    override val domain: DomainBareJid by config {
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

    override val username: Resourcepart by config {
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

    override val password: String? by optionalconfig {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyPasswordPropertyName.from(newConfig)
        legacyPasswordPropertyName.from(legacyConfig)
        "jicofo.xmpp.client.password".from(newConfig)
    }

    /**
     * This is the top-level domain hosted by the XMPP server (not necessarily the one used for login).
     */
    val xmppDomain: DomainBareJid by config {
        // The legacy name may be set as a system property in which case it the property is available via newConfig
        legacyXmppDomainPropertyName.from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        legacyXmppDomainPropertyName.from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
    }

    val conferenceMucJid: DomainBareJid by config {
        "org.jitsi.jicofo.XMPP_MUC_COMPONENT_PREFIX".from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom("$it.$xmppDomain")
        }
        "jicofo.xmpp.client.conference-muc-jid".from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        "default" { JidCreate.domainBareFrom("conference.$xmppDomain") }
    }

    override val replyTimeout: Duration by config {
        "jicofo.xmpp.client.reply-timeout".from(newConfig)
    }

    override val disableCertificateVerification: Boolean by config {
        "org.jitsi.jicofo.ALWAYS_TRUST_MODE_ENABLED".from(legacyConfig)
        "jicofo.xmpp.client.disable-certificate-verification".from(newConfig)
    }

    override val useTls: Boolean by config {
        "jicofo.xmpp.client.use-tls".from(newConfig)
    }

    val clientProxy: DomainBareJid? by optionalconfig {
        "jicofo.xmpp.client.client-proxy".from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
    }

    override fun toString(): String = "XmppClientConnectionConfig[hostname=$hostname, port=$port, username=$username]"

    override val name = "client"

    companion object {
        const val legacyHostnamePropertyName = "org.jitsi.jicofo.HOSTNAME"
        const val legacyDomainPropertyName = "org.jitsi.jicofo.FOCUS_USER_DOMAIN"
        const val legacyUsernamePropertyName = "org.jitsi.jicofo.FOCUS_USER_NAME"
        const val legacyPasswordPropertyName = "org.jitsi.jicofo.FOCUS_USER_PASSWORD"
        const val legacyXmppDomainPropertyName = "org.jitsi.jicofo.XMPP_DOMAIN"
    }
}
