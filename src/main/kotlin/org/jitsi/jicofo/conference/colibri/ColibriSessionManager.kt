package org.jitsi.jicofo.conference.colibri

import org.jitsi.jicofo.JicofoServices
import org.jitsi.jicofo.OctoConfig
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.conference.JitsiMeetConferenceImpl
import org.jitsi.jicofo.conference.Participant
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.MediaType
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jxmpp.jid.Jid

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
    parentLogger: Logger
) {
    /** The list of [BridgeSession]s currently used. */
    private val bridgeSessions = mutableListOf<BridgeSession>()
    private val syncRoot = Any()
    private val logger = createChildLogger(parentLogger)

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
        val bridgeSession = participant.bridgeSession

        // Expire the OctoEndpoints for this participant on other bridges.
        if (bridgeSession != null) {
            participant.setChannelAllocator(null)
            bridgeSession.terminate(participant)
            participant.clearTransportInfo()
            participant.colibriChannelsInfo = null
            participant.bridgeSession = null

            val removedSources = participant.sources

            synchronized(syncRoot) {
                operationalBridges()
                    .filter { it != bridgeSession }
                    .forEach { bridge -> bridge.removeSourcesFromOcto(removedSources) }
                maybeExpireBridgeSession(bridgeSession)
            }
        }
    }

    /**
     * Invites a new participant to the conference. Currently, this includes starting the Jingle session (via
     * [ParticipantChannelAllocator]), but the intention is to simplify this flow.
     */
    @Throws(BridgeSelectionFailedException::class)
    fun inviteParticipant(
        participant: Participant,
        startAudioMuted: Boolean,
        startVideoMuted: Boolean,
        reInvite: Boolean
    ) = synchronized(syncRoot) {
        // Some bridges in the conference may have become non-operational. Inviting a new participant to the conference
        // requires communication with its bridges, so we remove them from the conference first.
        removeNonOperationalBridges()

        val bridge: Bridge = selectBridge(participant)
        if (!bridge.isOperational) {
            logger.error("The selected bridge is non-operational: $bridge")
        }

        val bridgeSession = findBridgeSession(bridge) ?: addBridgeSession(bridge)
        bridgeSession.addParticipant(participant)
        participant.bridgeSession = bridgeSession
        logger.info("Added participant id=${participant.chatMember.name}, bridge=${bridgeSession.bridge.jid}")

        // Colibri channel allocation and jingle invitation take time, so schedule them on a separate thread.
        val channelAllocator = ParticipantChannelAllocator(
            jitsiMeetConference,
            bridgeSession,
            participant,
            startAudioMuted,
            startVideoMuted,
            reInvite,
            logger
        )

        participant.setChannelAllocator(channelAllocator)
        TaskPools.ioPool.execute(channelAllocator)

        if (reInvite) {
            addSourcesToOcto(participant.sources, bridgeSession)
        }
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

        val colibriChannelsInfo = participant.colibriChannelsInfo
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
                participant.colibriChannelsInfo
            )
            removeSourcesFromOcto(sourcesToRemove, bridgeSession)
        }
    }

    /** Get the ID of the bridge session for a [participant], or null if there's none. */
    fun getBridgeSessionId(participant: Participant): String? = synchronized(syncRoot) {
        return findBridgeSession(participant)?.id
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
    fun bridgeCount() = bridgeSessions.size

    /** Updates the transport info for a participant. */
    fun updateTransportInfo(participant: Participant) {
        val bridgeSession = findBridgeSession(participant)
        // We can hit null here during conference restart, but the state will be synced up later when the client
        // sends 'transport-accept'
        if (bridgeSession == null) {
            logger.warn("Can not update transport-info, no bridge session for $participant.")
            return
        }

        bridgeSession.colibriConference.updateBundleTransportInfo(
            participant.bundleTransport,
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

        val participantChannels = participant.colibriChannelsInfo
        if (participantChannels == null) {
            logger.error("No colibri channels for $participant")
            return false
        }

        return bridgeSession.colibriConference.muteParticipant(participantChannels, doMute, mediaType)
    }

    private fun addBridgeSession(bridge: Bridge): BridgeSession = synchronized(syncRoot) {
        val bridgeSession = BridgeSession(
            jitsiMeetConference,
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

    private fun findBridgeSession(jid: Jid) = bridgeSessions.find { it.bridge.jid == jid }
    private fun findBridgeSession(bridge: Bridge) = bridgeSessions.find { it.bridge == bridge }
    /** Return the bridge session for a specific [Participant], or null if there isn't one. */
    private fun findBridgeSession(participant: Participant) =
        bridgeSessions.find { it.participants.contains(participant) }

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
    private fun addSourcesToOcto(sources: ConferenceSourceMap, except: BridgeSession) = synchronized(sources) {
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
     */
    interface Listener {
        /** The number of bridges changed. */
        fun bridgeCountChanged(bridgeCount: Int)
        /** A specific number of bridges were removed from the conference because they failed. */
        fun failedBridgesRemoved(count: Int)
    }
}
