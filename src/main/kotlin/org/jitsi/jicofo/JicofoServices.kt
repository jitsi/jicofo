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

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.servlet.ServletContainer
import org.jitsi.impl.protocol.xmpp.XmppProtocolProvider
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.impl.protocol.xmpp.colibri.ColibriConferenceImpl
import org.jitsi.impl.reservation.rest.RESTReservations
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.auth.AuthConfig
import org.jitsi.jicofo.auth.ExternalJWTAuthority
import org.jitsi.jicofo.auth.ShibbolethAuthAuthority
import org.jitsi.jicofo.auth.XMPPDomainAuthAuthority
import org.jitsi.jicofo.bridge.BridgeConfig
import org.jitsi.jicofo.bridge.BridgeMucDetector
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.health.HealthConfig
import org.jitsi.jicofo.health.JicofoHealthChecker
import org.jitsi.jicofo.jibri.JibriConfig
import org.jitsi.jicofo.jigasi.JigasiConfig
import org.jitsi.jicofo.jigasi.JigasiDetector
import org.jitsi.jicofo.recording.jibri.JibriDetector
import org.jitsi.jicofo.rest.Application
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.jicofo.xmpp.IqHandler
import org.jitsi.jicofo.xmpp.XmppConnectionConfig
import org.jitsi.jicofo.xmpp.XmppProviderFactory
import org.jitsi.jicofo.xmpp.XmppServices
import org.jitsi.jicofo.xmpp.initializeSmack
import org.jitsi.rest.JettyBundleActivatorConfig
import org.jitsi.rest.createServer
import org.jitsi.rest.isEnabled
import org.jitsi.rest.servletContextHandler
import org.jitsi.utils.concurrent.CustomizableThreadFactory
import org.jitsi.utils.logging2.createLogger
import org.json.simple.JSONObject
import org.jxmpp.jid.impl.JidCreate
import java.lang.management.ManagementFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.jitsi.impl.reservation.rest.ReservationConfig.Companion.config as reservationConfig
import org.jitsi.jicofo.auth.AuthConfig.Companion.config as authConfig

/**
 * Start/stop jicofo-specific services.
 */
open class JicofoServices {
    private val logger = createLogger()

    open fun createXmppProviderFactory(): XmppProviderFactory {
        // Init smack shit
        initializeSmack()
        return object : XmppProviderFactory {
            override fun createXmppProvider(config: XmppConnectionConfig): XmppProvider {
                return XmppProtocolProvider(config)
            }
        }
    }
    private val xmppProviderFactory: XmppProviderFactory = createXmppProviderFactory()

    /**
     * Pool of cached threads used for colibri channel allocation.
     *
     * The overall thread model of jicofo is not obvious, and should be improved, at which point this should probably
     * be moved or at least renamed. For the time being, use a specific name to document how it's used.
     */
    var channelAllocationExecutor: ExecutorService = ThreadPoolExecutor(
        0, 1500,
        60L, TimeUnit.SECONDS,
        SynchronousQueue(),
        CustomizableThreadFactory("ColibriChannelAllocationPool", true)
    )

    val scheduledPool: ScheduledExecutorService = Executors.newScheduledThreadPool(
        200, CustomizableThreadFactory("Jicofo Scheduled", true)
    )

    val xmppServices = XmppServices(scheduledPool, xmppProviderFactory)

    val bridgeSelector = BridgeSelector(scheduledPool)
    private val bridgeDetector = if (BridgeConfig.config.breweryEnabled())
        BridgeMucDetector(xmppServices.serviceConnection, bridgeSelector).apply { init() }
        else null
    val jibriDetector = if (JibriConfig.config.breweryEnabled()) {
        logger.info("Using a Jibri detector with MUC: " + JibriConfig.config.breweryJid)
        JibriDetector(xmppServices.clientConnection, JibriConfig.config.breweryJid, false).apply{ init() }
    } else null
    val sipJibriDetector = if (JibriConfig.config.sipBreweryEnabled()) {
        logger.info("Using a SIP Jibri detector with MUC: " + JibriConfig.config.sipBreweryJid)
        JibriDetector(xmppServices.clientConnection, JibriConfig.config.sipBreweryJid, true).apply { init() }
    } else null
    val jigasiDetector = if (JigasiConfig.config.breweryEnabled()) {
        logger.info("Using a Jigasi detector with MUC: " + JigasiConfig.config.breweryJid)
        JigasiDetector(xmppServices.clientConnection, JigasiConfig.config.breweryJid).apply { init() }
    } else null

    val focusManager: FocusManager = FocusManager().also {
        logger.info("Starting FocusManager.")
        it.start(xmppServices.clientConnection, xmppServices.serviceConnection)
    }

    private val reservationSystem: RESTReservations?
    private val healthChecker: JicofoHealthChecker?
    val authenticationAuthority: AbstractAuthAuthority? = createAuthenticationAuthority()?.apply {
        start()
        focusManager.addFocusAllocationListener(this)
    }
    private val jettyServer: Server?

    val iqHandler: IqHandler
        // This is always non-null after init()
        get() = xmppServices.iqHandler!!

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

        xmppServices.init(
            authenticationAuthority = authenticationAuthority,
            focusManager = focusManager,
            reservationSystem = reservationSystem,
            jigasiEnabled = jigasiDetector != null
        )

        healthChecker = if (HealthConfig.config.enabled) {
            JicofoHealthChecker(HealthConfig.config, focusManager).apply {
                start()
                focusManager.setHealth(this)
            }
        } else null

        val httpServerConfig = JettyBundleActivatorConfig("org.jitsi.jicofo.auth", "jicofo.rest")
        jettyServer = if (httpServerConfig.isEnabled()) {
            logger.info("Starting HTTP server with config: $httpServerConfig.")
            val restApp = Application(
                authenticationAuthority as? ShibbolethAuthAuthority,
                CurrentVersionImpl.VERSION,
                healthChecker
            )
            createServer(httpServerConfig).also {
                it.servletContextHandler.addServlet(
                    ServletHolder(ServletContainer(restApp)),
                    "/*"
                )
                it.start()
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
        healthChecker?.stop()
        channelAllocationExecutor.shutdownNow()
        scheduledPool.shutdownNow()
        jettyServer?.stop()
        xmppServices.stop()
        bridgeSelector.stop()
        bridgeDetector?.dispose()
        jibriDetector?.dispose()
        sipJibriDetector?.dispose()
        jigasiDetector?.dispose()
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

    fun getStats(): JSONObject = JSONObject().apply {
        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we merge the FocusManager and ColibriConference stats in the root object.
        putAll(focusManager.stats)
        put("bridge_selector", bridgeSelector.stats)
        jibriDetector?.let { put("jibri_detector", it.stats) }
        sipJibriDetector?.let { put("sip_jibri_detector", it.stats) }
        jigasiDetector?.let { put("jigasi_detector", it.stats) }
        putAll(ColibriConferenceImpl.stats.toJson())
        put("threads", ManagementFactory.getThreadMXBean().threadCount)
    }

    companion object {
        @JvmField
        var jicofoServicesSingleton: JicofoServices? = null
    }
}
