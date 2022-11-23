/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
package org.jitsi.jicofo.jigasi

import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.JicofoConfig
import org.jitsi.jicofo.xmpp.BaseBrewery
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid

/**
 * [JigasiDetector] manages the pool of Jigasi instances by joining a "brewery" room that Jigasis connect to
 * and publish their status in MUC presence.
 * @author Damian Minkov
 */
open class JigasiDetector(
    xmppProvider: XmppProvider,
    breweryJid: EntityBareJid,
    private val localRegion: String? = JicofoConfig.config.localRegion
) : BaseBrewery<ColibriStatsExtension>(
    xmppProvider,
    breweryJid,
    ColibriStatsExtension.ELEMENT,
    ColibriStatsExtension.NAMESPACE,
    createLogger().apply { addContext("type", "jigasi") }
) {
    val xmppConnection = xmppProvider.xmppConnection

    override fun onInstanceStatusChanged(jid: EntityFullJid, status: ColibriStatsExtension) {}
    override fun notifyInstanceOffline(jid: Jid) {}

    /**
     * Selects a jigasi instance which supports transcription.
     * @param exclude a list of excluded instances.
     * @param preferredRegions a list of preferred regions.
     * @return the JID of the selected instance, or  `null` if there are no available jigasis that satisfy the
     * constraints (are not excluded and support transcription).
     */
    fun selectTranscriber(exclude: List<Jid> = emptyList(), preferredRegions: Collection<String> = emptySet()): Jid? =
        selectJigasi(instances, exclude, preferredRegions, localRegion, transcriber = true)

    /**
     * Selects a jigasi instance which supports SIP.
     * @param exclude a list of excluded instances.
     * @param preferredRegions a list of preferred regions.
     * @return the JID of the selected instance, or  `null` if there are no available jigasis that satisfy the
     * constraints (are not excluded and support SIP).
     */
    fun selectSipJigasi(exclude: List<Jid> = emptyList(), preferredRegions: Collection<String> = emptySet()): Jid? =
        selectJigasi(instances, exclude, preferredRegions, localRegion, transcriber = false)

    val stats: JSONObject
        get() = JSONObject().apply {
            this["sip_count"] = getInstanceCount { it.supportsSip() }
            this["sip_in_graceful_shutdown_count"] = getInstanceCount { it.supportsSip() && it.isInGracefulShutdown() }
            this["transcriber_count"] = getInstanceCount { it.supportsTranscription() }
        }

    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().also { debugState ->
            instances.forEach { instance ->
                val instanceJson = OrderedJsonObject().apply {
                    this["supports_sip"] = instance.supportsSip()
                    this["supports_transcription"] = instance.supportsTranscription()
                    this["is_in_graceful_shutdown"] = instance.isInGracefulShutdown()
                    this["participants"] = instance.getParticipantCount()
                }
                debugState[instance.jid.resourceOrEmpty.toString()] = instanceJson
            }
        }

    /**
     * The companion object is necessary for the implicit call to this.createLogger() in the super constructor!
     */
    companion object {
        @JvmStatic
        private fun selectJigasi(
            instances: List<BaseBrewery<ColibriStatsExtension>.BrewInstance>,
            exclude: List<Jid> = emptyList(),
            preferredRegions: Collection<String> = emptyList(),
            localRegion: String? = null,
            transcriber: Boolean = false
        ): Jid? {

            val availableInstances = instances
                .filter { !exclude.contains(it.jid) }
                .filter { !it.isInGracefulShutdown() }
                .filter { if (transcriber) it.supportsTranscription() else it.supportsSip() }

            // Try to match the preferred region.
            availableInstances.filter { it.isInRegion(*preferredRegions.toTypedArray()) }.let {
                if (it.isNotEmpty()) return it.leastLoaded()?.jid
            }
            // Otherwise try to match the local region.
            availableInstances.filter { it.isInRegion(localRegion) }.let {
                if (it.isNotEmpty()) return it.leastLoaded()?.jid
            }

            return availableInstances.leastLoaded()?.jid
        }
    }
}

private fun BaseBrewery<ColibriStatsExtension>.BrewInstance.isInGracefulShutdown(): Boolean =
    java.lang.Boolean.parseBoolean(status.getValueAsString(ColibriStatsExtension.SHUTDOWN_IN_PROGRESS))
private fun BaseBrewery<ColibriStatsExtension>.BrewInstance.supportsTranscription(): Boolean =
    java.lang.Boolean.parseBoolean(status.getValueAsString(ColibriStatsExtension.SUPPORTS_TRANSCRIPTION))
private fun BaseBrewery<ColibriStatsExtension>.BrewInstance.supportsSip(): Boolean =
    java.lang.Boolean.parseBoolean(status.getValueAsString(ColibriStatsExtension.SUPPORTS_SIP))
private fun BaseBrewery<ColibriStatsExtension>.BrewInstance.isInRegion(vararg regions: String?): Boolean =
    regions.contains(status.getValueAsString(ColibriStatsExtension.REGION))
private fun BaseBrewery<ColibriStatsExtension>.BrewInstance.getParticipantCount(): Int =
    status.getValueAsInt(ColibriStatsExtension.PARTICIPANTS) ?: 0
private fun List<BaseBrewery<ColibriStatsExtension>.BrewInstance>.leastLoaded() =
    minByOrNull { it.getParticipantCount() }
