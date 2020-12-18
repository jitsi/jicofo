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
package org.jitsi.jicofo

import org.apache.commons.lang3.StringUtils
import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.servlet.ServletContainer
import org.jitsi.impl.reservation.rest.RESTReservations
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.auth.AuthConfig
import org.jitsi.jicofo.auth.ExternalJWTAuthority
import org.jitsi.jicofo.auth.ShibbolethAuthAuthority
import org.jitsi.jicofo.auth.XMPPDomainAuthAuthority
import org.jitsi.jicofo.health.Health
import org.jitsi.jicofo.health.HealthConfig
import org.jitsi.jicofo.rest.Application
import org.jitsi.jicofo.xmpp.AuthenticationIqHandler
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.jicofo.xmpp.FocusComponent
import org.jitsi.jicofo.xmpp.IqHandler
import org.jitsi.jicofo.xmpp.XmppComponentConfig
import org.jitsi.jicofo.xmpp.XmppConfig
import org.jitsi.osgi.ServiceUtils2
import org.jitsi.protocol.xmpp.XmppConnection
import org.jitsi.rest.JettyBundleActivatorConfig
import org.jitsi.rest.createServer
import org.jitsi.rest.isEnabled
import org.jitsi.rest.servletContextHandler
import org.jitsi.service.configuration.ConfigurationService
import org.jitsi.utils.logging2.createLogger
import org.jxmpp.jid.impl.JidCreate
import org.osgi.framework.BundleContext
import org.jitsi.impl.reservation.rest.ReservationConfig.Companion.config as reservationConfig
import org.jitsi.jicofo.auth.AuthConfig.Companion.config as authConfig

/**
 * Start/stop jicofo-specific services outside OSGi.
 */
open class JicofoServices(
    /**
     * The [BundleContext] into which required OSGi services have been started.
     */
    val bundleContext: BundleContext
) {
    private val logger = createLogger()

    /**
     * Expose for testing.
     */
    private val focusComponent: FocusComponent?
    private val focusManager: FocusManager = ServiceUtils2.getService(bundleContext, FocusManager::class.java)
    private val reservationSystem: RESTReservations?
    private val health: Health?
    // TODO: initialize the auth authority here
    val authenticationAuthority: AbstractAuthAuthority? = createAuthenticationAuthority()?.apply {
        start()
        focusManager.addFocusAllocationListener(this)
    }
    val iqHandler: IqHandler

    init {
        reservationSystem = if (reservationConfig.enabled) {
            logger.info("Starting reservation system with base URL=${reservationConfig.baseUrl}.")
            RESTReservations(reservationConfig.baseUrl) { name, reason ->
                focusManager.destroyConference(name, reason)
            }.apply {
                focusManager.addFocusAllocationListener(this)
                start()
            }
        } else null

        val httpServerConfig = JettyBundleActivatorConfig("org.jitsi.jicofo.auth", "jicofo.rest")
        if (httpServerConfig.isEnabled()) {
            logger.info("Starting HTTP server with config: $httpServerConfig.")
            val restApp = Application(bundleContext, authenticationAuthority as? ShibbolethAuthAuthority)
            createServer(httpServerConfig).also {
                it.servletContextHandler.addServlet(
                    ServletHolder(ServletContainer(restApp)),
                    "/*"
                )
                it.start()
            }
        }

        iqHandler = createIqHandler()

        focusComponent = if (XmppComponentConfig.config.enabled) {
            FocusComponent(
                XmppComponentConfig.config,
                iqHandler
            ).apply {
                val configService = ServiceUtils2.getService(bundleContext, ConfigurationService::class.java)
                loadConfig(configService, "org.jitsi.jicofo")
            }
        } else {
            null
        }
        focusComponent?.connect()

        health = if (HealthConfig.config.enabled) {
            Health(HealthConfig.config, focusManager, focusComponent).apply {
                // The health service needs to register a [HealthCheckService] in OSGi to be used by jetty.
                start(bundleContext)
                focusManager.setHealth(this)
            }
        } else null
    }

    fun stop() {
        reservationSystem?.let {
            focusManager.removeFocusAllocationListener(it)
            it.stop()
        }
        authenticationAuthority?.let {
            focusManager.removeFocusAllocationListener(it)
            it.stop()
        }
        iqHandler.stop()
        focusComponent?.disconnect()
        health?.stop(bundleContext)
    }

    private fun createAuthenticationAuthority(): AbstractAuthAuthority? {
        return if (AuthConfig.config.enabled) {
            logger.info("Starting authentication service with config=$authConfig.")
            val authAuthority = when (authConfig.type) {
                AuthConfig.Type.XMPP -> XMPPDomainAuthAuthority(
                    authConfig.enableAutoLogin,
                    authConfig.authenticationLifetime,
                    JidCreate.domainBareFrom(authConfig.loginUrl)
                )
                AuthConfig.Type.JWT -> ExternalJWTAuthority(
                    JidCreate.domainBareFrom(authConfig.loginUrl)
                )
                AuthConfig.Type.SHIBBOLETH -> ShibbolethAuthAuthority(
                    authConfig.enableAutoLogin,
                    authConfig.authenticationLifetime,
                    authConfig.loginUrl,
                    AuthConfig.config.logoutUrl
                )
            }
            authAuthority
        }
        else {
            logger.info("Authentication service disabled.")
            null
        }
    }

    private fun createIqHandler(): IqHandler {
        val authenticationIqHandler = authenticationAuthority?.let { AuthenticationIqHandler(it) }
        val conferenceIqHandler = ConferenceIqHandler(
            focusManager = focusManager,
            focusAuthJid = "${XmppConfig.client.username}@${XmppConfig.client.domain}",
            isFocusAnonymous = StringUtils.isBlank(XmppConfig.client.password),
            authAuthority = authenticationAuthority,
            reservationSystem = reservationSystem
        )

        return IqHandler(focusManager, conferenceIqHandler, authenticationIqHandler).apply {
            focusManager.addXmppConnectionListener(object : FocusManager.XmppConnectionListener {
                    override fun xmppConnectionInitialized(xmppConnection: XmppConnection) {
                        init(xmppConnection)
                    }
            })
        }
    }

    companion object {
        @JvmField
        var jicofoServicesSingleton: JicofoServices? = null
    }
}