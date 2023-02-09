/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi.jicofo.health

import org.jitsi.health.HealthCheckService
import org.jitsi.health.HealthChecker
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.xmpp.XmppConfig
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.utils.logging2.createLogger
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.ping.packet.Ping
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart
import org.jxmpp.stringprep.XmppStringprepException
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * Checks the health of Jicofo.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
class JicofoHealthChecker(
        config: HealthConfig,
        private val focusManager: FocusManager,
        private val bridgeSelector: BridgeSelector,
        private val xmppProviders: Collection<XmppProvider>
) : HealthCheckService {
    private val logger = createLogger()

    /** Counts how many health checks took too long. */
    var totalSlowHealthChecks: Long = 0
        private set

    private val healthChecker = HealthChecker(
        config.interval,
        config.timeout,
        config.maxCheckDuration,
        false,
        Duration.ofMinutes(5),
        { performCheck() },
        Clock.systemUTC())

    fun start() = healthChecker.start()

    fun shutdown() {
        try {
            healthChecker.stop()
        } catch (e: Exception) {
            logger.warn("Failed to stop.", e)
        }
    }

    private fun performCheck() {
        Objects.requireNonNull(focusManager, "FocusManager is not set.")
        val start = System.currentTimeMillis()
        check()
        val duration = System.currentTimeMillis() - start
        if (duration > HealthConfig.config.maxCheckDuration.toMillis()) {
            logger.error("Health check took too long: " + duration + "ms")
            totalSlowHealthChecks++
        }
    }

    override fun getResult(): Exception? {
        return healthChecker.result
    }

    /**
     * Checks the health (status) of a specific [FocusManager].
     *
     * @throws Exception if an error occurs while checking the health (status)
     * of `focusManager` or the check determines that `focusManager`
     * is not healthy
     */
    private fun check() {
        if (bridgeSelector.operationalBridgeCount <= 0) {
            throw RuntimeException(
                    "No operational bridges available (total bridge count: "
                            + BridgeSelector.bridgeCount.get() + ")")
        }

        // Generate a pseudo-random room name. Minimize the risk of clashing with existing conferences.
        var roomName: EntityBareJid
        do {
            roomName = JidCreate.entityBareFrom(
                    generateRoomName(),
                    XmppConfig.client.conferenceMucJid
            )
        } while (focusManager.getConference(roomName) != null)

        // Create a conference with the generated room name.
        try {
            if (!focusManager.conferenceRequest(
                            roomName,
                            emptyMap(),
                            Level.WARNING /* conference logging level */,
                            false /* don't include in statistics */)) {
                throw RuntimeException("Failed to create conference with room name $roomName")
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to create conference with room name " + roomName + ":" + e.message)
        }
        try {
            xmppProviders.forEach { pingXmppProvider(it) }
        } catch (e: Exception) {
            throw RuntimeException("XMPP ping failed: ${e.message}")
        }
    }

    /**
     * The check focusManager.conferenceRequest uses Smack's collectors for the response which is executed
     * in its Reader thread. This ping test use a sync stanza listener which is executed in a single thread
     * and if something is blocking it healthcheck will fail.
     *
     * @throws Exception if the check fails or some other error occurs
     */
    @Throws(Exception::class)
    private fun pingXmppProvider(xmppProvider: XmppProvider) {
        val pingResponseWait = CountDownLatch(1)
        val xmppDomain = xmppProvider.config.xmppDomain
        if (xmppDomain == null) {
            logger.debug("Not pinging ${xmppProvider.config.name}, domain not configured.")
            return
        }

        val p = Ping(JidCreate.bareFrom(xmppDomain))
        val listener = StanzaListener { packet: Stanza? -> pingResponseWait.countDown() }
        try {
            xmppProvider.xmppConnection.addSyncStanzaListener(listener) {
                stanza: Stanza -> stanza.stanzaId != null && stanza.stanzaId == p.stanzaId
            }
            xmppProvider.xmppConnection.sendStanza(p)

            // Wait for 5 seconds to receive a response.
            if (!pingResponseWait.await(5, TimeUnit.SECONDS)) {
                throw RuntimeException("did not receive xmpp ping response for (${xmppProvider.config.name})")
            }
        } finally {
            xmppProvider.xmppConnection.removeSyncStanzaListener(listener)
        }
    }

    companion object {
        /**
         * The pseudo-random generator used to generate random input for
         * [FocusManager] such as room names.
         */
        private val RANDOM = Random()


        /**
         * Generates a pseudo-random room name which is not guaranteed to be unique.
         *
         * @return a pseudo-random room name which is not guaranteed to be unique
         */
        private fun generateRoomName(): Localpart? {
            return try {
                Localpart.from(HealthConfig.config.roomNamePrefix
                        + "-"
                        + java.lang.Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong()))
            } catch (e: XmppStringprepException) {
                // ignore, cannot happen
                null
            }
        }
    }
}