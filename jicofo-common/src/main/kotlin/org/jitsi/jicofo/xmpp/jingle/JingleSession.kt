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
package org.jitsi.jicofo.xmpp.jingle

import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.xmpp.IqProcessingResult
import org.jitsi.jicofo.xmpp.createSessionInitiate
import org.jitsi.jicofo.xmpp.createTransportReplace
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.GroupPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.JinglePacketFactory
import org.jitsi.xmpp.extensions.jingle.Reason
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.Jid

/**
 * Class describes Jingle session.
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 */
class JingleSession(
    /** Jingle session identifier. */
    val sid: String,
    /** Remote peer XMPP address. */
    val remoteJid: Jid,
    private val jingleIqRequestHandler: JingleIqRequestHandler,
    private val connection: AbstractXMPPConnection,
    private val requestHandler: JingleRequestHandler,
    private val encodeSourcesAsJson: Boolean
) {
    private var state = State.PENDING
    fun isActive() = state == State.ACTIVE

    val logger = createLogger().apply {
        addContext("remoteJid", remoteJid.toString())
        addContext("sid", sid)
    }

    private val incomingIqQueue = PacketQueue<JingleIQ>(
        Integer.MAX_VALUE,
        true,
        "jingle-iq-queue",
        {
            doProcessIq(it)
            return@PacketQueue true
        },
        TaskPools.ioPool
    )

    private val localJid: Jid = connection.user

    fun processIq(iq: JingleIQ): IqProcessingResult {
        val action = iq.action
            ?: return IqProcessingResult.RejectedWithError(
                IQ.createErrorResponse(
                    iq,
                    StanzaError.getBuilder(StanzaError.Condition.bad_request)
                        .setConditionText("Missing 'action'").build()
                )
            )
        JingleStats.stanzaReceived(action)

        if (state == State.ENDED) {
            return IqProcessingResult.RejectedWithError(
                IQ.createErrorResponse(
                    iq,
                    StanzaError.getBuilder(StanzaError.Condition.gone).setConditionText("session ended").build()
                )
            )
        }

        incomingIqQueue.add(iq)
        return IqProcessingResult.AcceptedWithNoResponse()
    }

    private fun doProcessIq(iq: JingleIQ) {
        val error = when (iq.action) {
            JingleAction.SESSION_ACCEPT -> {
                // The session needs to be marked as active early to allow code executing as part of onSessionAccept
                // to proceed (e.g. to signal source updates).
                state = State.ACTIVE
                val error = requestHandler.onSessionAccept(this, iq.contentList)
                if (error != null) state = State.ENDED
                error
            }

            JingleAction.SESSION_INFO -> requestHandler.onSessionInfo(this, iq)
            JingleAction.SESSION_TERMINATE -> requestHandler.onSessionTerminate(this, iq).also {
                state = State.ENDED
            }

            JingleAction.TRANSPORT_ACCEPT -> requestHandler.onTransportAccept(this, iq.contentList)
            JingleAction.TRANSPORT_INFO -> requestHandler.onTransportInfo(this, iq.contentList)
            JingleAction.TRANSPORT_REJECT -> {
                requestHandler.onTransportReject(this, iq)
                null
            }

            JingleAction.ADDSOURCE, JingleAction.SOURCEADD -> requestHandler.onAddSource(this, iq.contentList)
            JingleAction.REMOVESOURCE, JingleAction.SOURCEREMOVE -> requestHandler.onRemoveSource(
                this,
                iq.contentList
            )

            else -> {
                logger.warn("unsupported action ${iq.action}")
                StanzaError.getBuilder(StanzaError.Condition.feature_not_implemented)
                    .setConditionText("Unsupported 'action'").build()
            }
        }

        val response = if (error == null) {
            IQ.createResultIQ(iq)
        } else {
            logger.info("Returning error: request=${iq.toXML()}, error=${error.toXML()} ")
            IQ.createErrorResponse(iq, error)
        }
        connection.tryToSendStanza(response)
    }

    fun terminate(
        reason: Reason,
        message: String?,
        /** Whether to send a session-terminate IQ, or only terminate the session locally. */
        sendIq: Boolean
    ) {
        logger.debug("Terminating session with $remoteJid, reason=$reason, sendIq=$sendIq")
        val oldState = state
        state = State.ENDED

        if (oldState == State.ENDED) logger.warn("Terminating session which is already in state ENDED")

        if (sendIq) {
            if (oldState == State.ENDED) {
                logger.warn("Not sending session-terminate for session in state $state")
            } else {
                val terminate = JinglePacketFactory.createSessionTerminate(
                    localJid,
                    remoteJid,
                    sid,
                    reason,
                    message
                )
                connection.tryToSendStanza(terminate)
                JingleStats.stanzaSent(JingleAction.SESSION_TERMINATE)
            }
        }

        jingleIqRequestHandler.removeSession(this)
    }

    /**
     * Send a transport-replace IQ and wait for a response. Return true if the response is successful, and false
     * otherwise.
     */
    @Throws(SmackException.NotConnectedException::class)
    fun replaceTransport(
        contents: List<ContentPacketExtension>,
        additionalExtensions: List<ExtensionElement>,
        sources: ConferenceSourceMap
    ): Boolean {
        logger.info("Sending transport-replace, sources=$sources.")
        if (state != State.ACTIVE) logger.error("Sending transport-replace for session in state $state")

        val contentsWithSources = if (encodeSourcesAsJson) contents else sources.toContents(contents)
        val jingleIq = createTransportReplace(localJid, this, contentsWithSources)

        jingleIq.addExtension(GroupPacketExtension.createBundleGroup(jingleIq.contentList))
        additionalExtensions.forEach { jingleIq.addExtension(it) }
        if (encodeSourcesAsJson) {
            jingleIq.addExtension(sources.toJsonMessageExtension())
        }

        JingleStats.stanzaSent(jingleIq.action)
        val response = connection.sendIqAndGetResponse(jingleIq)
        return if (response?.type == IQ.Type.result) {
            true
        } else {
            logger.error("Unexpected response to transport-replace: ${response?.toXML()}")
            false
        }
    }

    /**
     * Send a source-remove IQ with the specified sources. Returns immediately without waiting for a response.
     */
    fun removeSource(sourcesToRemove: ConferenceSourceMap) {
        val removeSourceIq = JingleIQ(JingleAction.SOURCEREMOVE, sid).apply {
            from = localJid
            type = IQ.Type.set
            to = remoteJid
        }

        if (encodeSourcesAsJson) {
            removeSourceIq.addExtension(sourcesToRemove.toJsonMessageExtension())
        } else {
            sourcesToRemove.toJingle().forEach { removeSourceIq.addContent(it) }
        }
        logger.debug { "Sending source-remove, sources=$sourcesToRemove" }
        if (state != State.ACTIVE) logger.error("Sending source-remove for session in state $state")
        connection.tryToSendStanza(removeSourceIq)
        JingleStats.stanzaSent(JingleAction.SOURCEREMOVE)
    }

    /**
     * Send a source-add IQ with the specified sources. Returns immediately without waiting for a response.
     */
    fun addSource(sources: ConferenceSourceMap) {
        logger.debug { "Sending source-add, sources=$sources" }
        JingleStats.stanzaSent(JingleAction.SOURCEADD)
        if (state != State.ACTIVE) logger.error("Sending source-add for session in state $state")
        connection.tryToSendStanza(createAddSourceIq(sources))
    }

    @Throws(SmackException.NotConnectedException::class)
    fun initiateSession(
        contents: List<ContentPacketExtension>,
        additionalExtensions: List<ExtensionElement>,
        sources: ConferenceSourceMap,
    ): Boolean {
        if (state != State.PENDING) logger.error("Sending session-initiate for session in state $state")
        val contentsWithSources = if (encodeSourcesAsJson) contents else sources.toContents(contents)
        val sessionInitiate = createSessionInitiate(localJid, remoteJid, sid, contentsWithSources).apply {
            addExtension(GroupPacketExtension.createBundleGroup(contentList))
            additionalExtensions.forEach { addExtension(it) }
            if (encodeSourcesAsJson) {
                addExtension(sources.toJsonMessageExtension())
            }
        }

        jingleIqRequestHandler.registerSession(this)
        JingleStats.stanzaSent(sessionInitiate.action)
        val response = connection.sendIqAndGetResponse(sessionInitiate)
        // We treat a timeout (null) as success. This prevents failures in case the client delays processing, observed
        // when joining a large conference. The session will be pending until we receive session-accept.
        return if (response == null || response.type == IQ.Type.result) {
            true
        } else {
            logger.error("Unexpected response to session-initiate: $response")
            false
        }
    }

    private fun createAddSourceIq(sources: ConferenceSourceMap) = JingleIQ(JingleAction.SOURCEADD, sid).apply {
        from = localJid
        type = IQ.Type.set
        to = remoteJid
        if (encodeSourcesAsJson) {
            addExtension(sources.toJsonMessageExtension())
        } else {
            sources.toJingle().forEach { addContent(it) }
        }
    }

    fun debugState() = OrderedJsonObject().apply {
        put("sid", sid)
        put("remoteJid", remoteJid.toString())
        put("state", state.toString())
    }

    enum class State { PENDING, ACTIVE, ENDED }
}

/**
 * Encodes the sources described in `sources` as a [JsonMessageExtension] in the compact JSON format
 * (see [ConferenceSourceMap.compactJson]).
 * @return the [JsonMessageExtension] encoding `sources`.
 */
fun ConferenceSourceMap.toJsonMessageExtension() = JsonMessageExtension("{\"sources\":${compactJson()}}")

/**
 * Encodes the sources described in `sources` in the list of Jingle contents. If necessary, new
 * [ContentPacketExtension]s are created. Returns the resulting list of [ContentPacketExtension] which
 * contains the encoded sources.
 *
 * @return the resulting list of [ContentPacketExtension], which consists of `contents` plus any new
 * [ContentPacketExtension]s that were created.
 */
private fun ConferenceSourceMap.toContents(
    /** The list of existing [ContentPacketExtension] to which to add sources if possible. */
    existingContents: List<ContentPacketExtension>
): List<ContentPacketExtension> {
    val ret = mutableListOf<ContentPacketExtension>()
    var audioContent = existingContents.find { it.name == "audio" }
    var videoContent = existingContents.find { it.name == "video" }

    audioContent?.let { ret.add(it) }
    videoContent?.let { ret.add(it) }

    val audioSourceExtensions = createSourcePacketExtensions(MediaType.AUDIO)
    val audioSsrcGroupExtensions = createSourceGroupPacketExtensions(MediaType.AUDIO)
    if (audioSourceExtensions.isNotEmpty() || audioSsrcGroupExtensions.isNotEmpty()) {
        if (audioContent == null) {
            audioContent = ContentPacketExtension().apply { name = "audio" }
            ret.add(audioContent)
        }
        var audioDescription = audioContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)
        if (audioDescription == null) {
            audioDescription = RtpDescriptionPacketExtension().apply { media = "audio" }
            audioContent.addChildExtension(audioDescription)
        }
        for (extension in audioSourceExtensions) {
            audioDescription.addChildExtension(extension)
        }
        for (extension in audioSsrcGroupExtensions) {
            audioDescription.addChildExtension(extension)
        }
    }

    val videoSourceExtensions = createSourcePacketExtensions(MediaType.VIDEO)
    val videoSsrcGroupExtensions = createSourceGroupPacketExtensions(MediaType.VIDEO)
    if (videoSourceExtensions.isNotEmpty() || videoSsrcGroupExtensions.isNotEmpty()) {
        if (videoContent == null) {
            videoContent = ContentPacketExtension().apply { name = "video" }
            ret.add(videoContent)
        }
        var videoDescription = videoContent.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)
        if (videoDescription == null) {
            videoDescription = RtpDescriptionPacketExtension().apply { media = "video" }
            videoContent.addChildExtension(videoDescription)
        }
        for (extension in videoSourceExtensions) {
            videoDescription.addChildExtension(extension)
        }
        for (extension in videoSsrcGroupExtensions) {
            videoDescription.addChildExtension(extension)
        }
    }
    return ret
}
