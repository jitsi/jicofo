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
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.health.Health
import org.jitsi.jicofo.health.HealthConfig
import org.jitsi.jicofo.reservation.ReservationSystem
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
    protected open var focusComponent: FocusComponent? = null

    private var health: Health? = null

    init {
        val authAuthority = ServiceUtils2.getService(bundleContext, AuthenticationAuthority::class.java)
        val focusManager = ServiceUtils2.getService(bundleContext, FocusManager::class.java)
        val reservationSystem = ServiceUtils2.getService(bundleContext, ReservationSystem::class.java)
        val configService = ServiceUtils2.getService(bundleContext, ConfigurationService::class.java)

        val anonymous = StringUtils.isBlank(XmppConfig.client.password)
        val focusJid = XmppConfig.client.username.toString() + "@" + XmppConfig.client.domain.toString()
        focusComponent = FocusComponent(XmppComponentConfig.config, anonymous, focusJid).apply {
            loadConfig(configService, "org.jitsi.jicofo")
            this.setAuthAuthority(authAuthority)
            this.setFocusManager(focusManager)
            this.setReservationSystem(reservationSystem)
        }
        startFocusComponent()

        if (HealthConfig.config.enabled) {
            health = Health(HealthConfig.config, focusManager, focusComponent).apply {
                // The health service needs to register a [HealthCheckService] in OSGi to be used by jetty.
                start(bundleContext)
                focusManager.setHealth(this)
            }
        }
    }

    open fun startFocusComponent() {
        focusComponent?.connect()
    }

    open fun stopFocusComponent() {
        focusComponent?.disconnect()
    }


    fun stop() {
        stopFocusComponent()
        health?.stop(bundleContext)
    }
}