/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
package org.jitsi.jicofo.conference.colibri

import org.apache.commons.lang3.StringUtils
import org.jitsi.jicofo.JicofoServices
import org.jitsi.jicofo.OctoConfig
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.protocol.xmpp.util.TransportSignaling
import org.jitsi.utils.MediaType
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceRtcpmuxPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jxmpp.jid.Jid
import java.util.concurrent.ConcurrentHashMap

/**
 * Manage all Colibri sessions for a conference.
 */
class ColibriSessionManager(
    private val jicofoServices: JicofoServices,
    /**
     * A "global" identifier of this [JitsiMeetConferenceImpl] (i.e.
     * a unique ID across a set of independent jicofo instances).
     */
    val gid: Long,
    /**
     * The associated conference.
     * TODO: remove this reference.
     */
    private val jitsiMeetConference: JitsiMeetConferenceImpl,
    private val colibriRequestCallback: ColibriRequestCallback,
    parentLogger: Logger
) {
    /** The list of [BridgeSession]s currently used. */
    private val bridgeSessions = mutableListOf<BridgeSession>()
    private val syncRoot = Any()
    private val logger = createChildLogger(parentLogger)
    private val participantInfoMap = ConcurrentHashMap<Participant, ParticipantInfo>()

    private val eventEmitter = SyncEventEmitter<Listener>()
    fun addListener(listener: Listener) = eventEmitter.addHandler(listener)
    fun removeListener(listener: Listener) = eventEmitter.removeHandler(listener)

    /** Expire all colibri sessions. */
    fun expireAll() = synchronized(syncRoot) {
        // Expire all bridge sessions
        bridgeSessions.forEach { it.dispose() }
        bridgeSessions.clear()
        eventEmitter.fireEvent { bridgeCountChanged(0) }
    }

    /** Get the set of regions of the bridges. */
    fun getBridgeRegions(): Set<String> = synchronized(syncRoot) {
        return bridgeSessions.mapNotNull { it.bridge.region }.toSet()
    }

    /** Remove a set of participants */
    fun removeParticipants(participants: List<Participant>) = synchronized(syncRoot) {
        participants.forEach { removeParticipant(it) }
    }

    /** Removes a participant, terminating its colibri session. */
    fun removeParticipant(participant: Participant) {
        participantInfoMap.remove(participant)
        val bridgeSession = findBridgeSession(participant)

        // Expire the OctoEndpoints for this participant on other bridges.
        if (bridgeSession != null) {
            participant.setChannelAllocator(null)
            bridgeSession.terminate(participant)

            val removedSources = participant.sources

            synchronized(syncRoot) {
                operationalBridges()
                    .filter { it != bridgeSession }
                    .forEach { bridge -> bridge.removeSourcesFromOcto(removedSources) }
                maybeExpireBridgeSession(bridgeSession)
            }
        }
    }

    fun updateChannels(participant: Participant) = synchronized(syncRoot) {
        val participantInfo = participantInfoMap[participant]
        val bridgeSession = findBridgeSession(participant) ?: return
        bridgeSession.colibriConference.updateChannelsInfo(
            participantInfo?.colibriChannels,
            participantInfo?.rtpDescriptionMap,
            participant.sources
        )
    }

    @Throws(ColibriAllocationFailedException::class)
    fun allocate(
        participant: Participant,
        contents: List<ContentPacketExtension>,
        reInvite: Boolean
    ): ColibriAllocation {
        val bridgeSession: BridgeSession
        val participantInfo: ParticipantInfo
        synchronized(syncRoot) {
            // Some bridges in the conference may have become non-operational. Allocating channels for a new participant
            // requires communication with all bridges, so we remove them from the conference first.
            removeNonOperationalBridges()

            val bridge: Bridge = selectBridge(participant)
            if (!bridge.isOperational) {
                // TODO should we throw here?
                logger.error("The selected bridge is non-operational: $bridge")
            }

            participantInfo = ParticipantInfo(hasColibriSession = true)
            participantInfoMap[participant] = participantInfo
            bridgeSession = findBridgeSession(bridge) ?: addBridgeSession(bridge)
            bridgeSession.addParticipant(participant)
            logger.info("Added participant id=${participant.chatMember.name}, bridge=${bridgeSession.bridge.jid}")
        }

        return allocateChannels(participant, participantInfo, bridgeSession, contents).also {
            if (reInvite) {
                addSourcesToOcto(participant.sources, bridgeSession)
            }
        }
    }

    @Throws(ColibriAllocationFailedException::class)
    private fun allocateChannels(
        participant: Participant,
        participantInfo: ParticipantInfo,
        bridgeSession: BridgeSession,
        contents: List<ContentPacketExtension>
    ): ColibriAllocation {
        val jvb = bridgeSession.bridge.jid
        // We want to re-invite the participants in this conference.
        try {
            logger.info("Using $jvb to allocate channels for: $participant")

            // null means canceled, because colibriConference has been disposed by another thread
            val colibriChannels = bridgeSession.colibriConference.createColibriChannels(
                participant.endpointId,
                participant.statId,
                true /* initiator */,
                contents
            ) ?: throw ColibriConferenceDisposedException()

            val colibriAllocation: ColibriAllocation
            try {
                colibriAllocation = parseAllocation(colibriChannels)
            } catch (e: ColibriParsingException) {
                // This is not an error coming from the bridge, so the channels are still active. Make sure they are
                // expired.
                bridgeSession.colibriConference.expireChannels(colibriChannels)
                throw BridgeFailedException(jvb, restartConference = false)
            }

            bridgeSession.bridge.setIsOperational(true)
            colibriRequestCallback.requestSucceeded(jvb)
            participantInfo.colibriChannels = colibriChannels

            return colibriAllocation
        } catch (e: ConferenceNotFoundException) {
            // The conference on the bridge has likely expired. We want to re-invite the conference participants,
            // though the bridge is not faulty.
            logger.error("$jvb - conference ID not found (expired?): ${e.message}")
            throw ColibriConferenceExpiredException(
                jvb,
                // If the ColibriConference is in use, and we want to retry.
                restartConference = StringUtils.isNotBlank(bridgeSession.colibriConference.conferenceId)
            )
        } catch (e: BadRequestException) {
            // The bridge indicated that our request is invalid. This does not mean the bridge is faulty, and retrying
            // will likely result in the same error.
            // We observe this when an endpoint uses an ID not accepted by the new bridge (via a custom client).
            // TODO: Jicofo should not allow such endpoints.
            logger.error("$jvb - the bridge indicated bad-request: ${e.message}")
            throw BadColibriRequestException()
        } catch (e: ColibriException) {
            // All other errors indicate that the bridge is faulty: timeout, wrong response type, or something else.
            bridgeSession.bridge.setIsOperational(false)
            bridgeSession.hasFailed = true
            logger.error("$jvb - failed to allocate channels, will consider the bridge faulty: ${e.message}", e)
            throw BridgeFailedException(
                jvb,
                restartConference = StringUtils.isNotBlank(bridgeSession.colibriConference.conferenceId)
            )
        }
    }

    @Throws(ColibriParsingException::class)
    private fun parseAllocation(colibriConferenceIQ: ColibriConferenceIQ): ColibriAllocation {
        // Look for any channels that reference a channel-bundle and extract the associated transport element.
        val channelBundleId =
            colibriConferenceIQ.contents.flatMap { it.channels }.mapNotNull { it.channelBundleId }.firstOrNull()
                ?: throw ColibriParsingException("No channel with a channel-bundle-id found")
        val channelBundle = colibriConferenceIQ.getChannelBundle(channelBundleId)
            ?: throw ColibriParsingException("No channel-bundle found with ID=$channelBundleId")
        val transport = channelBundle.transport
            ?: throw ColibriParsingException("channel-bundle has no transport")

        if (!transport.isRtcpMux) {
            transport.addChildExtension(IceRtcpmuxPacketExtension())
        }

        val sources = ConferenceSourceMap()
        colibriConferenceIQ.contents.forEach { content ->
            val mediaType = MediaType.parseString(content.name)
            content.channels.forEach { channel ->
                channel.sources.firstOrNull()?.let { sourcePacketExtension ->
                    sources.add(
                        ParticipantChannelAllocator.SSRC_OWNER_JVB,
                        EndpointSourceSet(
                            Source(
                                sourcePacketExtension.ssrc,
                                mediaType, // assuming either audio or video the source name: jvb-a0 or jvb-v0
                                "jvb-" + mediaType.toString()[0] + "0",
                                "mixedmslabel mixedlabel" + content.name + "0",
                                false
                            )
                        )
                    )
                }
            }
        }

        return ColibriAllocation(sources, transport)
    }

    /**
     * Add sources for a participant.
     */
    fun addSources(participant: Participant, sourcesToAdd: ConferenceSourceMap) {
        val bridgeSession = findBridgeSession(participant)
        if (bridgeSession == null) {
            logger.warn("No bridge session for $participant")
            return
        }

        val colibriChannelsInfo = participantInfoMap[participant]?.colibriChannels
        if (colibriChannelsInfo == null) {
            logger.warn("No colibriChannelsInfo for $participant")
            return
        }

        bridgeSession.colibriConference.updateSourcesInfo(
            participant.sources,
            colibriChannelsInfo
        )

        if (sourcesToAdd.isNotEmpty()) {
            addSourcesToOcto(sourcesToAdd, bridgeSession)
        }
    }

    /**
     * This is very similar to [addSources]. This one is called when we receive session-accept.
     * TODO: figure out why they are different.
     */
    fun updateSources(participant: Participant, sourcesToAdd: ConferenceSourceMap) {
        val bridgeSession = findBridgeSession(participant)
        if (bridgeSession == null) {
            logger.warn("No bridge found for a participant: " + participant.chatMember.name)
            return
        }

        bridgeSession.updateColibriChannels(participant)

        // If we accepted any new sources from the participant, update
        // the state of all remote bridges.
        if (sourcesToAdd.isNotEmpty()) {
            addSourcesToOcto(sourcesToAdd, bridgeSession)
        }
    }

    /**
     * Get the set of relay IDs of bridges currently used, excluding [exclude].
     * TODO: don't expose.
     */
    fun getAllRelays(exclude: String?): List<String> = synchronized(syncRoot) {
        return operationalBridges().mapNotNull { bridge -> bridge.bridge.relayId }
            .filter { it != exclude }.toList()
    }

    /** Remove sources for a [participant] */
    fun removeSources(participant: Participant, sourcesToRemove: ConferenceSourceMap) {
        // TODO error handling
        val bridgeSession = findBridgeSession(participant)
        if (bridgeSession != null) {
            bridgeSession.colibriConference.updateSourcesInfo(
                participant.sources,
                participantInfoMap[participant]?.colibriChannels
            )
            removeSourcesFromOcto(sourcesToRemove, bridgeSession)
        }
    }

    /** Get the ID of the bridge session for a [participant], or null if there's none. */
    fun getBridgeSessionId(participant: Participant): String? = synchronized(syncRoot) {
        return findBridgeSession(participant)?.id
    }

    fun getRegion(participant: Participant): String? = synchronized(syncRoot) {
        return findBridgeSession(participant)?.bridge?.region
    }

    /**
     * Handles the event of a set of bridges going down. Removes the associated bridge sessions.
     *
     * @return the set of participants which were on removed bridges (and now need to be re-invited).
     */
    fun bridgesDown(bridges: Set<Jid>): List<Participant> {
        val participantsToReinvite: MutableList<Participant> = ArrayList()
        var bridgesRemoved = 0

        synchronized(syncRoot) {
            bridges.forEach { bridgeJid: Jid ->
                val bridgeSession = findBridgeSession(bridgeJid)
                if (bridgeSession != null) {
                    logger.error("One of our bridges failed: $bridgeJid")

                    // Note: the Jingle sessions are still alive, we'll just
                    // (try to) move to a new bridge and send transport-replace.
                    participantsToReinvite.addAll(bridgeSession.terminateAll())
                    bridgesRemoved++
                    bridgeSessions.remove(bridgeSession)
                }
            }
            eventEmitter.fireEvent { bridgeCountChanged(bridgeSessions.size) }
            eventEmitter.fireEvent { failedBridgesRemoved(bridgesRemoved) }
            updateOctoRelays()
        }
        return participantsToReinvite
    }

    /** The number of bridges currently used. */
    fun bridgeCount() = synchronized(syncRoot) { bridgeSessions.size }

    /** Updates the transport info for a participant. */
    fun updateTransportInfo(participant: Participant, contents: List<ContentPacketExtension>) {
        addTransportFromJingle(participant, contents)
        val bridgeSession = findBridgeSession(participant)
        // We can hit null here during conference restart, but the state will be synced up later when the client
        // sends 'transport-accept'
        if (bridgeSession == null) {
            logger.warn("Can not update transport-info, no bridge session for $participant.")
            return
        }

        bridgeSession.colibriConference.updateBundleTransportInfo(
            participantInfoMap[participant]?.transport,
            participant.endpointId
        )
    }

    /**
     * Mute a participant.
     * @return true iff successful.
     * TODO: improve error handling.
     */
    fun mute(participant: Participant, doMute: Boolean, mediaType: MediaType): Boolean {
        val bridgeSession = findBridgeSession(participant)
        if (bridgeSession == null) {
            logger.error("No bridge session for $participant")
            return false
        }

        val participantChannels = participantInfoMap[participant]?.colibriChannels
        if (participantChannels == null) {
            logger.error("No colibri channels for $participant")
            return false
        }

        return bridgeSession.colibriConference.muteParticipant(participantChannels, doMute, mediaType)
    }

    fun getSources(except: List<Participant>): ConferenceSourceMap {
        val sources = jitsiMeetConference.sources
        val sourcesCopy = sources.copy()
        sources.keys.forEach { sourceJid ->
            // If the return value is used to create a new octo participant then
            // we skip participants without a bridge session (which happens when
            // a bridge fails & participant are re-invited). The reason why we
            // do this is to avoid adding sources to the (newly created) octo
            // participant from soon to be re-invited (and hence soon to be local)
            // participants, causing a weird transition from octo participant to
            // local participant in the new bridge.
            if (except.any { it.mucJid == sourceJid } || (sourceJid != null && !hasColibriSession(sourceJid))) {
                sourcesCopy.remove(sourceJid)
            }
        }
        return sourcesCopy.unmodifiable
    }

    private fun hasColibriSession(mucJid: Jid) = getParticipantInfo(mucJid).let { it != null && it.hasColibriSession }
    private fun getParticipantInfo(mucJid: Jid) =
        participantInfoMap.entries.firstOrNull { it.key.mucJid == mucJid }?.value

    /** Get the [ParticipantInfo] structure associated with a participant. This persists across re-invites. */
    fun getParticipantInfo(participant: Participant) = participantInfoMap[participant]

    /** Update the RTP Description map for a specific participant. */
    fun setRtpDescriptionMap(participant: Participant, contents: List<ContentPacketExtension>) {
        val rtpDescMap = mutableMapOf<String, RtpDescriptionPacketExtension>()
        for (content in contents) {
            val rtpDesc = content.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)
            if (rtpDesc != null) {
                rtpDescMap[content.name] = rtpDesc
            }
        }

        participantInfoMap[participant]?.let { it.rtpDescriptionMap = rtpDescMap }
            ?: run { logger.warn("Can not set RTP description, no ParticipantInfo for $participant") }
    }

    /**
     * Update a participant's transport information given a list of Jingle contents (using the first
     * [IceUdpTransportPacketExtension] extension found in the contents).
     * If the participant already has the transport information, the new one will be merged into it using
     * [TransportSignaling#mergeTransportExtension].
     *
     * @param contents the list of [ContentPacketExtension] received from the remote endpoint in a jingle message like
     * 'session-accept', 'transport-info', 'transport-accept' etc.
     */
    fun addTransportFromJingle(participant: Participant, contents: List<ContentPacketExtension>) {
        participantInfoMap[participant]?.let { participantInfo ->
            val transport = contents.mapNotNull {
                it.getFirstChildOfType(IceUdpTransportPacketExtension::class.java)
            }.firstOrNull()

            if (transport == null) {
                logger.error("No valid transport supplied in transport-update from $participant")
                return
            }

            if (!transport.isRtcpMux) {
                transport.addChildExtension(IceRtcpmuxPacketExtension())
            }

            val existingTransport = participantInfo.transport
            if (existingTransport == null) {
                participantInfo.transport = transport
            } else {
                TransportSignaling.mergeTransportExtension(existingTransport, transport)
            }
        } ?: run { logger.warn("Can not add transport info, no ParticipantInfo for $participant.") }
    }

    private fun addBridgeSession(bridge: Bridge): BridgeSession = synchronized(syncRoot) {
        val bridgeSession = BridgeSession(
            jitsiMeetConference,
            this,
            colibriRequestCallback,
            jicofoServices.xmppServices.serviceConnection.xmppConnection,
            bridge,
            gid,
            logger
        )
        bridgeSessions.add(bridgeSession)
        eventEmitter.fireEvent { bridgeCountChanged(bridgeSessions.size) }
        if (operationalBridges().count() >= 2) {
            if (!jicofoServices.focusManager.isJicofoIdConfigured) {
                logger.warn(
                    "Enabling Octo while the jicofo ID is not set. Configure a valid value [1-65535] by " +
                        "setting org.jitsi.jicofo.SHORT_ID in sip-communicator.properties or jicofo.octo.id in " +
                        "jicofo.conf. Future versions will require this for Octo."
                )
            }
            // Octo needs to be enabled (by inviting an Octo participant for each bridge), or if it is already enabled
            // the list of relays for each bridge may need to be updated.
            updateOctoRelays()
        }

        return bridgeSession
    }

    /**
     * Expires the session with a particular bridge if it has no real (non-octo)
     * participants left.
     * @param bridgeSession the bridge session to expire.
     */
    private fun maybeExpireBridgeSession(bridgeSession: BridgeSession) = synchronized(syncRoot) {
        if (bridgeSession.participants.isEmpty()) {
            bridgeSession.terminateAll()
            bridgeSessions.remove(bridgeSession)
            eventEmitter.fireEvent { bridgeCountChanged(bridgeSessions.size) }
            updateOctoRelays()
        }
    }

    private fun getBridges(): Map<Bridge, Int> = synchronized(syncRoot) {
        return bridgeSessions.filter { !it.hasFailed }.associate { Pair(it.bridge, it.participants.size) }
    }

    private fun selectBridge(participant: Participant): Bridge {
        if (findBridgeSession(participant) != null) {
            // This should never happen.
            throw IllegalStateException("The participant already has a bridge:" + participant.chatMember.name)
        }

        return jicofoServices.bridgeSelector.selectBridge(getBridges(), participant.chatMember.region)
            ?: throw BridgeSelectionFailedException()
    }

    private fun updateOctoRelays() {
        if (!OctoConfig.config.enabled) {
            return
        }

        synchronized(syncRoot) {
            val allRelays = getAllRelays(null)

            logger.info("Updating Octo relays: $allRelays")
            operationalBridges().forEach { it.setRelays(allRelays) }
        }
    }

    private fun removeNonOperationalBridges() {
        val nonOperationalBridges: Set<Jid> = bridgeSessions
            .filter { it.hasFailed || !it.bridge.isOperational }
            .map { it.bridge.jid }.toSet()

        if (nonOperationalBridges.isNotEmpty()) {
            bridgesDown(nonOperationalBridges)
        }
    }

    private fun findBridgeSession(jid: Jid) = synchronized(syncRoot) {
        bridgeSessions.find { it.bridge.jid == jid }
    }
    private fun findBridgeSession(bridge: Bridge) = findBridgeSession(bridge.jid)
    /** Return the bridge session for a specific [Participant], or null if there isn't one. */
    private fun findBridgeSession(participant: Participant) = synchronized(syncRoot) {
        bridgeSessions.find { it.participants.contains(participant) }
    }

    /**
     * Update octo channels on all bridges except `except`, removing the specified set of `sources`.
     * @param sources the sources to remove.
     * @param except the bridge session which is not to be updated.
     */
    private fun removeSourcesFromOcto(sources: ConferenceSourceMap, except: BridgeSession) = synchronized(syncRoot) {
        operationalBridges()
            .filter { it != except }
            .forEach { it.removeSourcesFromOcto(sources) }
    }

    /**
     * Adds the specified sources and source groups to the Octo participants
     * of all bridges except for `exclude`.
     * @param except the bridge to which sources will not be added (i.e. the
     * bridge to which the participant whose sources we are adding is
     * connected).
     * @param sources the sources to add.
     */
    private fun addSourcesToOcto(sources: ConferenceSourceMap, except: BridgeSession) = synchronized(syncRoot) {
        operationalBridges()
            .filter { it != except }
            .forEach { it.addSourcesToOcto(sources) }
    }

    /**
     * Get a stream of those bridges which are operational.
     */
    private fun operationalBridges(): List<BridgeSession> = synchronized(syncRoot) {
        bridgeSessions.filter { !it.hasFailed && it.bridge.isOperational }
    }

    /**
     * Interface for events fired by [ColibriSessionManager].
     *
     * Note that [ColibriSessionManager] calls these while holding its internal lock, so listeners should be careful
     * not to perform action that will cause a deadlock.
     */
    interface Listener {
        /** The number of bridges changed. */
        fun bridgeCountChanged(bridgeCount: Int)
        /** A specific number of bridges were removed from the conference because they failed. */
        fun failedBridgesRemoved(count: Int)
    }
}
