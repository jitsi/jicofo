/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.jibri

import org.jitsi.jicofo.xmpp.BaseBrewery
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.concurrent.CustomizableThreadFactory
import org.jitsi.utils.event.AsyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * <tt>JibriDetector</tt> manages the pool of Jibri instances by joining a "brewery" room where Jibris connect to and
 * publish their status in MUC presence.
 *
 * @author Pawel Domas
 */
class JibriDetector(
    xmppProvider: XmppProvider,
    private val breweryJid: EntityBareJid,
    private val isSip: Boolean,
    val clock: Clock = Clock.systemUTC()
) : BaseBrewery<JibriStatusPacketExt>(
    xmppProvider,
    breweryJid,
    JibriStatusPacketExt.ELEMENT,
    JibriStatusPacketExt.NAMESPACE,
    createLogger().apply { addContext("type", if (isSip) "sip_jibri" else "jibri") }
) {
    private val eventEmitter = AsyncEventEmitter<EventHandler>(eventEmitterExecutor)

    val logger = createLogger()

    val xmppConnection = xmppProvider.xmppConnection

    /**
     * Selects a Jibri to be used for a recording session.
     *
     * Selects from the jibris which have advertised themselves as healthy and idle, which haven't been
     * selected in the last [SELECT_TIMEOUT], and which have not been reported failed in the last [FAILURE_TIMEOUT].
     * If multiple instances match, selects the one which has failed least recently (or hasn't failed).
     *
     * @return the XMPP address of the selected instance.
     */
    fun selectJibri(): Jid? {
        val now = clock.instant()
        val oldest = jibriInstances.values.filter {
            it.reportsAvailable && Duration.between(it.lastSelected, now) >= SELECT_TIMEOUT
        }.minByOrNull { it.lastFailed } ?: return null

        return if (Duration.between(oldest.lastFailed, now) >= FAILURE_TIMEOUT) {
            oldest.lastSelected = now
            oldest.jid
        } else {
            null
        }
    }

    /**
     * Notify [JibriDetector] that the instance with JID [jid] failed a request (it returned an error or a request
     * timed out). Failed instances are not selected for [FAILURE_TIMEOUT], and after this timeout they are prioritized
     * according to the time of failure.
     */
    fun instanceFailed(jid: Jid) {
        jibriInstances[jid]?.let {
            logger.info("Instance failed: $jid. Will not be selected for the next $FAILURE_TIMEOUT")
            it.lastFailed = clock.instant()
        }
    }

    /** The jibri instances to select from */
    private val jibriInstances: MutableMap<Jid, JibriInstance> = ConcurrentHashMap()

    override fun onInstanceStatusChanged(jid: EntityFullJid, presenceExt: JibriStatusPacketExt) {
        if (!jibriInstances.containsKey(jid)) {
            logger.info("Creating a new instance for $jid, available = ${presenceExt.isAvailable}")
            jibriInstances[jid] = JibriInstance(jid, presenceExt.isAvailable)
        }

        val jibriInstance = jibriInstances[jid] ?: let {
            logger.error("Instance was removed. Thread safety issues?")
            return
        }

        jibriInstance.reportsAvailable = presenceExt.isAvailable
        if (jibriInstance.reportsAvailable) {
            // If we receive a new presence indicating the instance is available, override any failures we've detected.
            // This is because jibri does NOT periodically advertise presence in the MUC and only updates it when
            // something actually changes.
            if (jibriInstance.lastFailed != NEVER) {
                logger.info("Resetting failure state for $jid")
                jibriInstance.lastFailed = NEVER
            }
        }
    }

    override fun notifyInstanceOffline(jid: Jid) {
        // Remove from jibriInstances
        logger.info("Removing instance $jid")
        jibriInstances.remove(jid)

        eventEmitter.fireEvent { instanceOffline(jid) }
    }

    fun addHandler(eventHandler: EventHandler) = eventEmitter.addHandler(eventHandler)
    fun removeHandler(eventHandler: EventHandler) = eventEmitter.removeHandler(eventHandler)

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().also { debugState ->
            debugState["is_sip"] = isSip
            debugState["brewery_jid"] = breweryJid.toString()
            instances.forEach { instance ->
                val instanceJson = OrderedJsonObject().apply {
                    this["health_status"] = instance.status.healthStatus?.status.toString()
                    this["busy_status"] = instance.status.busyStatus?.status.toString()
                    jibriInstances[instance.jid]?.let {
                        this["reports_available"] = it.reportsAvailable
                        this["last_failed"] = it.lastFailed.toString()
                        this["last_selected"] = it.lastSelected.toString()
                    }
                }
                debugState[instance.jid.resourcepart.toString()] = instanceJson
            }
        }

    interface EventHandler {
        fun instanceOffline(jid: Jid) {}
    }

    /**
     * The companion object is necessary for the implicit call to this.createLogger() in the super constructor!
     */
    companion object {
        /**
         * TODO: Refactor to use a common executor.
         */
        private val eventEmitterExecutor = Executors.newSingleThreadExecutor(
            CustomizableThreadFactory("JibriDetector-AsyncEventEmitter", false)
        )

        private val NEVER = Instant.MIN

        /**
         * The length of the failure timeout. See [selectJibri] and [instanceFailed].
         */
        val FAILURE_TIMEOUT: Duration = Duration.ofMinutes(1)

        /**
         * The length of the select timeout. See [selectJibri].
         */
        val SELECT_TIMEOUT: Duration = Duration.ofMillis(200)
    }

    /**
     * Contains the relevant state for a Jibri instance.
     */
    private class JibriInstance(
        /** The jid of the instance */
        val jid: Jid,
        /** Whether the jibri reported itself available (healthy and idle) in presence. */
        var reportsAvailable: Boolean
    ) {
        /** The last time (if any) at which this instance was reported failed via [instanceFailed]. */
        var lastFailed: Instant = NEVER

        /** The last time this instance was selected via [selectJibri]. */
        var lastSelected: Instant = NEVER
    }
}
