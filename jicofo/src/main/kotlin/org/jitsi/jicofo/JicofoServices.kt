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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.servlet.ServletContainer
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.auth.AuthConfig
import org.jitsi.jicofo.auth.ExternalJWTAuthority
import org.jitsi.jicofo.auth.ShibbolethAuthAuthority
import org.jitsi.jicofo.auth.XMPPDomainAuthAuthority
import org.jitsi.jicofo.bridge.BridgeConfig
import org.jitsi.jicofo.bridge.BridgeMucDetector
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.JvbDoctor
import org.jitsi.jicofo.health.HealthConfig
import org.jitsi.jicofo.health.JicofoHealthChecker
import org.jitsi.jicofo.jibri.JibriConfig
import org.jitsi.jicofo.jibri.JibriDetector
import org.jitsi.jicofo.rest.Application
import org.jitsi.jicofo.rest.ConferenceRequest
import org.jitsi.jicofo.rest.RestConfig
import org.jitsi.jicofo.util.SynchronizedDelegate
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.jicofo.xmpp.XmppServices
import org.jitsi.jicofo.xmpp.initializeSmack
import org.jitsi.jicofo.xmpp.jingle.JingleStats
import org.jitsi.rest.createServer
import org.jitsi.rest.servletContextHandler
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.json.simple.JSONObject
import org.jxmpp.jid.impl.JidCreate
import java.lang.management.ManagementFactory
import org.jitsi.jicofo.auth.AuthConfig.Companion.config as authConfig

/**
 * Start/stop jicofo-specific services.
 */
@SuppressFBWarnings("MS_CANNOT_BE_FINAL")
class JicofoServices {
    private val logger = createLogger()

    init {
        // Init smack shit
        initializeSmack()
    }

    val focusManager: FocusManager = FocusManager(this).apply { start() }
    val authenticationAuthority: AbstractAuthAuthority? = createAuthenticationAuthority()?.apply {
        start()
        focusManager.addListener(this)
    }

    val xmppServices = XmppServices(
        conferenceStore = focusManager,
        focusManager = focusManager, // TODO do not use FocusManager directly
        authenticationAuthority = authenticationAuthority
    ).also {
        it.clientConnection.addRegistrationListener(focusManager)
    }

    val bridgeSelector = BridgeSelector()
    private val jvbDoctor = if (BridgeConfig.config.healthChecksEnabled) {
        JvbDoctor(bridgeSelector, xmppServices.serviceConnection).apply {
            bridgeSelector.addHandler(this)
        }
    } else {
        logger.warn("JVB health-checks disabled")
        null
    }

    private val bridgeDetector: BridgeMucDetector? = BridgeConfig.config.breweryJid?.let { breweryJid ->
        BridgeMucDetector(
            xmppServices.getXmppConnectionByName(BridgeConfig.config.xmppConnectionName),
            bridgeSelector,
            breweryJid
        ).apply { init() }
    } ?: run {
        logger.error("No bridge detector configured.")
        null
    }
    val jibriDetector = JibriConfig.config.breweryJid?.let { breweryJid ->
        JibriDetector(
            xmppServices.getXmppConnectionByName(JibriConfig.config.xmppConnectionName),
            breweryJid,
            false
        ).apply {
            init()
        }
    } ?: run {
        logger.info("No Jibri detector configured.")
        null
    }
    val sipJibriDetector = JibriConfig.config.sipBreweryJid?.let { breweryJid ->
        JibriDetector(xmppServices.clientConnection, breweryJid, true).apply { init() }
    } ?: run {
        logger.info("No SIP Jibri detector configured.")
        null
    }

    private val healthChecker: JicofoHealthChecker? = if (HealthConfig.config.enabled) {
        JicofoHealthChecker(HealthConfig.config, focusManager).apply {
            start()
        }
    } else null

    private val jettyServer: Server?

    init {
        jettyServer = if (RestConfig.config.enabled) {
            logger.info("Starting HTTP server with config: ${RestConfig.config.httpServerConfig}.")
            val restApp = Application(
                authenticationAuthority as? ShibbolethAuthAuthority,
                CurrentVersionImpl.VERSION,
                healthChecker,
                if (RestConfig.config.enableConferenceRequest) {
                    ConferenceRequest(xmppServices.conferenceIqHandler)
                } else null
            )
            createServer(RestConfig.config.httpServerConfig).also {
                it.servletContextHandler.addServlet(
                    ServletHolder(ServletContainer(restApp)),
                    "/*"
                )
                it.start()
            }
        } else null
    }

    fun shutdown() {
        authenticationAuthority?.let {
            focusManager.removeListener(it)
            it.shutdown()
        }
        healthChecker?.shutdown()
        jettyServer?.stop()
        jvbDoctor?.let {
            bridgeSelector.removeHandler(it)
            it.shutdown()
        }
        bridgeDetector?.shutdown()
        jibriDetector?.shutdown()
        sipJibriDetector?.shutdown()
        xmppServices.clientConnection.removeRegistrationListener(focusManager)
        focusManager.stop()
        xmppServices.shutdown()
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
        } else {
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
        xmppServices.jigasiDetector?.let { put("jigasi_detector", it.stats) }
        put("jigasi", xmppServices.jigasiStats)
        put("threads", ManagementFactory.getThreadMXBean().threadCount)
        put("jingle", JingleStats.toJson())
        healthChecker?.let {
            put("slow_health_check", it.totalSlowHealthChecks)
            put("healthy", it.result == null)
        }
    }

    fun getDebugState(full: Boolean) = OrderedJsonObject().apply {
        put("focus_manager", focusManager.getDebugState(full))
        put("bridge_selector", bridgeSelector.debugState)
        put("jibri_detector", jibriDetector?.debugState ?: "null")
        put("sip_jibri_detector", sipJibriDetector?.debugState ?: "null")
        put("jigasi_detector", xmppServices.jigasiDetector?.debugState ?: "null")
    }

    fun getConferenceDebugState(conferenceId: String) = OrderedJsonObject().apply {
        val conference = focusManager.getConference(JidCreate.entityBareFrom(conferenceId))
        return conference?.debugState ?: OrderedJsonObject()
    }

    companion object {
        @JvmStatic
        val jicofoServicesSingletonSyncRoot = Any()

        @JvmStatic
        var jicofoServicesSingleton: JicofoServices? by SynchronizedDelegate(null, jicofoServicesSingletonSyncRoot)
    }
}
