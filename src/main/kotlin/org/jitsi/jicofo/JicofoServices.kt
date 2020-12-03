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
import org.jitsi.impl.reservation.rest.RESTReservations
import org.jitsi.impl.reservation.rest.ReservationConfig.Companion.config as reservationConfig
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.health.Health
import org.jitsi.jicofo.health.HealthConfig
import org.jitsi.jicofo.xmpp.FocusComponent
import org.jitsi.jicofo.xmpp.XmppComponentConfig
import org.jitsi.jicofo.xmpp.XmppConfig
import org.jitsi.osgi.ServiceUtils2
import org.jitsi.service.configuration.ConfigurationService
import org.osgi.framework.BundleContext

/**
 * Start/stop jicofo-specific services outside OSGi.
 */
open class JicofoServices(
    /**
     * The [BundleContext] into which required OSGi services have been started.
     */
    val bundleContext: BundleContext
) {
    /**
     * Expose for testing.
     */
    protected val focusComponent: FocusComponent
    private val focusManager: FocusManager
    private val reservationSystem: RESTReservations?
    private val health: Health?

    init {
        val authAuthority = ServiceUtils2.getService(bundleContext, AuthenticationAuthority::class.java)
        focusManager = ServiceUtils2.getService(bundleContext, FocusManager::class.java)
        reservationSystem = if (reservationConfig.enabled) {
            RESTReservations(reservationConfig.baseUrl) { name, reason ->
                focusManager.destroyConference(name, reason)
            }.apply {
                focusManager.addFocusAllocationListener(this)
                start()
            }
        } else null

        val configService = ServiceUtils2.getService(bundleContext, ConfigurationService::class.java)

        val anonymous = StringUtils.isBlank(XmppConfig.client.password)
        val focusJid = XmppConfig.client.username.toString() + "@" + XmppConfig.client.domain.toString()
        focusComponent = FocusComponent(XmppComponentConfig.config, anonymous, focusJid).apply {
            loadConfig(configService, "org.jitsi.jicofo")
            authAuthority?.let { setAuthAuthority(authAuthority) }
            setFocusManager(focusManager)
            reservationSystem?.let { setReservationSystem(reservationSystem) }
        }
        startFocusComponent()

        health = if (HealthConfig.config.enabled) {
            Health(HealthConfig.config, focusManager, focusComponent).apply {
                // The health service needs to register a [HealthCheckService] in OSGi to be used by jetty.
                start(bundleContext)
                focusManager.setHealth(this)
            }
        } else null
    }

    /**
     * Expose for testing.
     */
    open fun startFocusComponent() {
        focusComponent.connect()
    }

    /**
     * Expose for testing.
     */
    open fun stopFocusComponent() {
        focusComponent.disconnect()
    }


    fun stop() {
        reservationSystem?.let {
            focusManager.removeFocusAllocationListener(it)
            stop()
        }
        stopFocusComponent()
        health?.stop(bundleContext)
    }
}