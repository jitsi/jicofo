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

import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.ConfigException.UnableToRetrieve.NotFound
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.utils.secs
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.time.Duration

class XmppConfig private constructor() {
    val trustedDomains: List<DomainBareJid> by config {
        "jicofo.xmpp.trusted-domains".from(newConfig)
            .convertFrom<List<String>> { l -> l.map { JidCreate.domainBareFrom(it) } }
    }

    val useJitsiJidValidation: Boolean by config {
        "jicofo.xmpp.use-jitsi-jid-validation".from(newConfig)
    }

    companion object {
        @JvmField
        val service = XmppServiceConnectionConfig()

        @JvmField
        val client = XmppClientConnectionConfig()

        @JvmStatic
        val visitors: Map<String, XmppVisitorConnectionConfig> by config {
            "jicofo.xmpp.visitors".from(newConfig)
                .convertFrom<ConfigObject> { cfg ->
                    cfg.entries.associate {
                        val xmppConfig = it.toXmppVisitorConnectionConfig()
                        Pair(xmppConfig.name, xmppConfig)
                    }
                }
        }

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
    val resource: Resourcepart
    val password: String?
    val replyTimeout: Duration
    val disableCertificateVerification: Boolean
    val useTls: Boolean
    val name: String
    val xmppDomain: DomainBareJid?
    val jid
        get() = "$username@$domain"
}

class XmppVisitorConnectionConfig(
    override val enabled: Boolean,
    override val hostname: String,
    override val port: Int,
    override val domain: DomainBareJid,
    override val username: Resourcepart,
    override val resource: Resourcepart,
    override val password: String?,
    override val replyTimeout: Duration,
    override val disableCertificateVerification: Boolean,
    override val useTls: Boolean,
    override val name: String,
    override val xmppDomain: DomainBareJid?,
    val conferenceService: DomainBareJid
) : XmppConnectionConfig

private fun MutableMap.MutableEntry<String, ConfigValue>.toXmppVisitorConnectionConfig(): XmppVisitorConnectionConfig {
    val name = this.key
    val c = value as? ConfigObject ?: throw ConfigException.UnsupportedType("visitors config must be an object")
    val hostname = c["hostname"]?.unwrapped()?.toString()
        ?: throw NotFound("hostname required for visitors config $name")
    val domain = c["domain"]?.unwrapped()?.toString() ?: hostname
    val xmppDomain = c["xmpp-domain"]?.unwrapped()?.toString()
    val username = c["username"]?.unwrapped()?.toString() ?: "focus"
    val conferenceService = c["conference-service"]?.unwrapped()?.toString()
        ?: throw NotFound("conference-service required for visitors config $name")

    return XmppVisitorConnectionConfig(
        enabled = c["enabled"]?.let { it.unwrapped().toString().toBoolean() } ?: true,
        hostname = c["hostname"]?.unwrapped()?.toString()
            ?: throw NotFound("hostname required for visitors config $name"),
        port = c["port"]?.unwrapped()?.toString()?.toInt() ?: 5222,
        domain = JidCreate.domainBareFrom(domain),
        username = Resourcepart.from(username),
        resource = Resourcepart.from(c["resource"]?.unwrapped()?.toString() ?: username),
        password = c["password"]?.unwrapped()?.toString(),
        replyTimeout = c["reply-timeout"]?.unwrapped() as? Duration ?: 15.secs,
        disableCertificateVerification = c["disable-certificate-verification"]?.unwrapped()?.toString()?.toBoolean()
            ?: false,
        useTls = c["use-tls"]?.unwrapped()?.toString()?.toBoolean() ?: true,
        name = name,
        conferenceService = JidCreate.domainBareFrom(conferenceService),
        xmppDomain = if (xmppDomain != null) JidCreate.domainBareFrom(xmppDomain) else null
    )
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

    override val xmppDomain: DomainBareJid? by optionalconfig {
        "jicofo.xmpp.service.xmpp-domain".from(newConfig)
    }

    override val username: Resourcepart by config {
        "org.jitsi.jicofo.BRIDGE_MUC_XMPP_USER".from(legacyConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
        "jicofo.xmpp.service.username".from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
    }

    override val resource: Resourcepart by config {
        "jicofo.xmpp.service.resource".from(newConfig).convertFrom<String> {
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
        LEGACY_HOSTNAME_PROPERTY_NAME.from(legacyConfig).convertFrom<String> { true }
        "jicofo.xmpp.client.enabled".from(newConfig)
    }

    override val hostname: String by config {
        LEGACY_HOSTNAME_PROPERTY_NAME.from(legacyConfig)
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
        LEGACY_DOMAIN_PROPERTY_NAME.from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        "jicofo.xmpp.client.domain".from(newConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
    }

    override val username: Resourcepart by config {
        LEGACY_USERNAME_PROPERTY_NAME.from(legacyConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
        "jicofo.xmpp.client.username".from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
    }

    override val resource: Resourcepart by config {
        "jicofo.xmpp.client.resource".from(newConfig).convertFrom<String> {
            Resourcepart.from(it)
        }
    }

    override val password: String? by optionalconfig {
        LEGACY_PASSWORD_PROPERTY_NAME.from(legacyConfig)
        "jicofo.xmpp.client.password".from(newConfig)
    }

    /**
     * This is the top-level domain hosted by the XMPP server (not necessarily the one used for login).
     */
    override val xmppDomain: DomainBareJid by config {
        LEGACY_XMPP_DOMAIN_PROPERTY_NAME.from(legacyConfig).convertFrom<String> {
            JidCreate.domainBareFrom(it)
        }
        "jicofo.xmpp.client.xmpp-domain".from(newConfig).convertFrom<String> {
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
        const val LEGACY_HOSTNAME_PROPERTY_NAME = "org.jitsi.jicofo.HOSTNAME"
        const val LEGACY_DOMAIN_PROPERTY_NAME = "org.jitsi.jicofo.FOCUS_USER_DOMAIN"
        const val LEGACY_USERNAME_PROPERTY_NAME = "org.jitsi.jicofo.FOCUS_USER_NAME"
        const val LEGACY_PASSWORD_PROPERTY_NAME = "org.jitsi.jicofo.FOCUS_USER_PASSWORD"
        const val LEGACY_XMPP_DOMAIN_PROPERTY_NAME = "org.jitsi.jicofo.XMPP_DOMAIN"
    }
}
