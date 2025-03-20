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
import org.jitsi.jicofo.auth.AbstractAuthAuthority
import org.jitsi.jicofo.auth.AuthConfig
import org.jitsi.jicofo.auth.ExternalJWTAuthority
import org.jitsi.jicofo.auth.XMPPDomainAuthAuthority
import org.jitsi.jicofo.bridge.BridgeConfig
import org.jitsi.jicofo.bridge.BridgeMucDetector
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.bridge.JvbDoctor
import org.jitsi.jicofo.bridgeload.LoadRedistributor
import org.jitsi.jicofo.health.HealthConfig
import org.jitsi.jicofo.health.JicofoHealthChecker
import org.jitsi.jicofo.jibri.JibriConfig
import org.jitsi.jicofo.jibri.JibriDetector
import org.jitsi.jicofo.jibri.JibriDetectorMetrics
import org.jitsi.jicofo.ktor.RestConfig
import org.jitsi.jicofo.metrics.GlobalMetrics
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.util.SynchronizedDelegate
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.jicofo.xmpp.XmppServices
import org.jitsi.jicofo.xmpp.initializeSmack
import org.jitsi.jicofo.xmpp.jingle.JingleStats
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
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
        // TODO do not use FocusManager directly
        focusManager = focusManager,
        authenticationAuthority = authenticationAuthority
    ).also {
        it.clientConnection.addListener(focusManager)
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

    private val loadRedistributor = LoadRedistributor(focusManager, bridgeSelector)

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

    init {
        if (jibriDetector != null || sipJibriDetector != null) {
            JicofoMetricsContainer.instance.metricsUpdater.addUpdateTask {
                JibriDetectorMetrics.updateMetrics(jibriDetector = jibriDetector, sipJibriDetector = sipJibriDetector)
            }
        }
    }

    private val healthChecker: JicofoHealthChecker? = if (HealthConfig.config.enabled) {
        JicofoHealthChecker(
            HealthConfig.config,
            focusManager,
            bridgeSelector,
            setOf(xmppServices.clientConnection)
        ).apply {
            start()
        }
    } else {
        null
    }

    private val ktor = if (RestConfig.config.enabled) {
        org.jitsi.jicofo.ktor.Application(
            healthChecker,
            xmppServices.conferenceIqHandler,
            focusManager,
            loadRedistributor,
            { getStats() }
        ) { full, confId ->
            if (confId == null) {
                getDebugState(full)
            } else {
                getConferenceDebugState(confId)
            }
        }
    } else {
        logger.info("Rest interface disabled.")
        null
    }

    init {
        logger.info("Registering GlobalMetrics periodic updates.")
        JicofoMetricsContainer.instance.metricsUpdater.addUpdateTask { GlobalMetrics.update() }
    }

    fun shutdown() {
        authenticationAuthority?.let {
            focusManager.removeListener(it)
            it.shutdown()
        }
        healthChecker?.shutdown()
        JicofoMetricsContainer.instance.metricsUpdater.stop()
        ktor?.stop()
        jvbDoctor?.let {
            bridgeSelector.removeHandler(it)
            it.shutdown()
        }
        loadRedistributor.shutdown()
        bridgeDetector?.shutdown()
        jibriDetector?.shutdown()
        sipJibriDetector?.shutdown()
        xmppServices.clientConnection.removeListener(focusManager)
        xmppServices.shutdown()
    }

    private fun createAuthenticationAuthority(): AbstractAuthAuthority? {
        return if (AuthConfig.config.type != AuthConfig.Type.NONE) {
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
                AuthConfig.Type.NONE -> null
            }
            authAuthority
        } else {
            logger.info("Authentication service disabled.")
            null
        }
    }

    /** Gets statistics for the /stats HTTP interface. */
    private fun getStats(): OrderedJsonObject = OrderedJsonObject().apply {
        // Update the metrics that are usually updated periodically so we read the current values.
        JicofoMetricsContainer.instance.metricsUpdater.updateMetrics()
        // We want to avoid exposing unnecessary hierarchy levels in the stats,
        // so we merge the FocusManager and ColibriConference stats in the root object.
        putAll(focusManager.stats)

        put("bridge_selector", bridgeSelector.stats)
        JibriDetectorMetrics.appendStats(this)
        xmppServices.jigasiDetector?.let { put("jigasi_detector", it.stats) }
        put("jigasi", xmppServices.jigasiStats)
        put("threads", GlobalMetrics.threadCount.get())
        put("jingle", JingleStats.toJson())
        put("version", CurrentVersionImpl.VERSION.toString())
        healthChecker?.let {
            val result = it.result
            put("slow_health_check", it.totalSlowHealthChecks)
            put("healthy", result.success)
            put(
                "health",
                JSONObject().apply {
                    put("success", result.success)
                    put("hardFailure", result.hardFailure)
                    put("responseCode", result.responseCode)
                    put("sticky", result.sticky)
                    put("message", result.message)
                }
            )
        }
    }

    private fun getDebugState(full: Boolean) = OrderedJsonObject().apply {
        put("focus_manager", focusManager.getDebugState(full))
        put("bridge_selector", bridgeSelector.debugState)
        put("jibri_detector", jibriDetector?.debugState ?: "null")
        put("sip_jibri_detector", sipJibriDetector?.debugState ?: "null")
        put("jigasi_detector", xmppServices.jigasiDetector?.debugState ?: "null")
        put("av_moderation", xmppServices.avModerationHandler.debugState)
        put("conference_iq_handler", xmppServices.conferenceIqHandler.debugState)
    }

    private fun getConferenceDebugState(conferenceId: EntityBareJid) = OrderedJsonObject().apply {
        val conference = focusManager.getConference(JidCreate.entityBareFrom(conferenceId))
        return conference?.debugState ?: OrderedJsonObject()
    }

    companion object {
        @JvmStatic
        val jicofoServicesSingletonSyncRoot = Any()

        @JvmStatic
        var jicofoServicesSingleton: JicofoServices? by SynchronizedDelegate(null, jicofoServicesSingletonSyncRoot)

        @JvmField
        val versionMetric = JicofoMetricsContainer.instance.registerInfo(
            "version",
            "Application version",
            CurrentVersionImpl.VERSION.toString()
        )
    }
}
