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

import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.xmpp.createSessionInitiate
import org.jitsi.jicofo.xmpp.createTransportReplace
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.GroupPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.JinglePacketFactory
import org.jitsi.xmpp.extensions.jingle.Reason
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
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
    val sessionID: String,
    /** Remote peer XMPP address. */
    val address: Jid,
    private val jingleApi: JingleApi,
    private val requestHandler: JingleRequestHandler,
    private val encodeSourcesAsJson: Boolean
) {
    val logger = createLogger().apply {
        addContext("address", address.toString())
        addContext("sid", sessionID)
    }

    fun processIq(iq: JingleIQ): StanzaError? {
        val action = iq.action
            ?: return StanzaError.getBuilder(StanzaError.Condition.bad_request)
                .setConditionText("Missing 'action'").build()
        JingleStats.stanzaReceived(action)

        return when (action) {
            JingleAction.SESSION_ACCEPT -> requestHandler.onSessionAccept(this, iq.contentList)
            JingleAction.SESSION_INFO -> requestHandler.onSessionInfo(this, iq)
            JingleAction.SESSION_TERMINATE -> requestHandler.onSessionTerminate(this, iq)
            JingleAction.TRANSPORT_ACCEPT -> requestHandler.onTransportAccept(this, iq.contentList)
            JingleAction.TRANSPORT_INFO -> { requestHandler.onTransportInfo(this, iq.contentList); null }
            JingleAction.TRANSPORT_REJECT -> { requestHandler.onTransportReject(this, iq); null }
            JingleAction.ADDSOURCE, JingleAction.SOURCEADD -> requestHandler.onAddSource(this, iq.contentList)
            JingleAction.REMOVESOURCE, JingleAction.SOURCEREMOVE -> requestHandler.onRemoveSource(this, iq.contentList)
            else -> {
                logger.warn("unsupported action $action")
                StanzaError.getBuilder(StanzaError.Condition.feature_not_implemented)
                    .setConditionText("Unsupported 'action'").build()
            }
        }
    }

    fun terminate(
        reason: Reason,
        message: String?,
        sendTerminate: Boolean
    ) {
        logger.info("Terminating session with $address, reason=$reason, sendTerminate=$sendTerminate")

        if (sendTerminate) {
            val terminate = JinglePacketFactory.createSessionTerminate(
                jingleApi.ourJID,
                address,
                sessionID,
                reason,
                message
            )
            jingleApi.connection.tryToSendStanza(terminate)
            JingleStats.stanzaSent(JingleAction.SESSION_TERMINATE)
        }

        jingleApi.removeSession(this)
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

        val contentsWithSources = if (encodeSourcesAsJson) contents else sources.toContents(contents)
        val jingleIq = createTransportReplace(jingleApi.ourJID, this, contentsWithSources)

        jingleIq.addExtension(GroupPacketExtension.createBundleGroup(jingleIq.contentList))
        additionalExtensions.forEach { jingleIq.addExtension(it) }
        if (encodeSourcesAsJson) {
            jingleIq.addExtension(sources.toJsonMessageExtension())
        }

        JingleStats.stanzaSent(jingleIq.action)
        val response = jingleApi.connection.sendIqAndGetResponse(jingleIq)
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
        val removeSourceIq = JingleIQ(JingleAction.SOURCEREMOVE, sessionID).apply {
            from = jingleApi.ourJID
            type = IQ.Type.set
            to = address
        }

        if (encodeSourcesAsJson) {
            removeSourceIq.addExtension(sourcesToRemove.toJsonMessageExtension())
        } else {
            sourcesToRemove.toJingle().forEach { removeSourceIq.addContent(it) }
        }
        logger.debug { "Sending source-remove, sources=$sourcesToRemove" }
        jingleApi.connection.tryToSendStanza(removeSourceIq)
        JingleStats.stanzaSent(JingleAction.SOURCEREMOVE)
    }

    /**
     * Send a source-add IQ with the specified sources. Returns immediately without waiting for a response.
     */
    fun addSource(sources: ConferenceSourceMap) {
        logger.debug { "Sending source-add, sources=$sources" }
        JingleStats.stanzaSent(JingleAction.SOURCEADD)
        jingleApi.connection.tryToSendStanza(createAddSourceIq(sources))
    }

    @Throws(SmackException.NotConnectedException::class)
    fun initiateSession(
        contents: List<ContentPacketExtension>,
        additionalExtensions: List<ExtensionElement>,
        sources: ConferenceSourceMap,
    ): Boolean {
        val contentsWithSources = if (encodeSourcesAsJson) contents else sources.toContents(contents)
        val sessionInitiate = createSessionInitiate(jingleApi.ourJID, address, sessionID, contentsWithSources).apply {
            addExtension(GroupPacketExtension.createBundleGroup(contentList))
            additionalExtensions.forEach { addExtension(it) }
            if (encodeSourcesAsJson) {
                addExtension(sources.toJsonMessageExtension())
            }
        }

        jingleApi.registerSession(this)
        JingleStats.stanzaSent(sessionInitiate.action)
        val response = jingleApi.connection.sendIqAndGetResponse(sessionInitiate)
        return if (response?.type == IQ.Type.result) {
            true
        } else {
            logger.error("Unexpected response to session-initiate: ${response?.toXML()}")
            false
        }
    }

    /**
     * Send a source-add IQ with the specified sources, and wait for a response. Return true if the response is
     * successful and false otherwise.
     * Note that this is only used in testing to simulate a source-add IQ being sent from a participant to jicofo. The
     * tests should probably be changed.
     */
    @Throws(SmackException.NotConnectedException::class)
    fun addSourceAndWaitForResponse(sources: ConferenceSourceMap): Boolean {
        val response = jingleApi.connection.sendIqAndGetResponse(createAddSourceIq(sources))
        JingleStats.stanzaSent(JingleAction.SOURCEADD)
        return response?.type == IQ.Type.result
    }

    private fun createAddSourceIq(sources: ConferenceSourceMap) = JingleIQ(JingleAction.SOURCEADD, sessionID).apply {
        from = jingleApi.ourJID
        type = IQ.Type.set
        to = address
        if (encodeSourcesAsJson) {
            addExtension(sources.toJsonMessageExtension())
        } else {
            sources.toJingle().forEach { addContent(it) }
        }
    }
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
