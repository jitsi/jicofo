/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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
package org.jitsi.jicofo.bridge.colibri

import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.xmpp.extensions.colibri2.Capability
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ

/** Read the [IceUdpTransportPacketExtension] for an endpoint with ID [endpointId] (or null if missing). */
fun ConferenceModifiedIQ.parseTransport(endpointId: String): Transport? {
    return endpoints.find { it.id == endpointId }?.transport
}

/**
 * Reads the feedback sources (at the "conference" level) and parses them into a [ConferenceSourceMap].
 * Uses the special [SSRC_OWNER_JVB] JID as the "owner" and appends the msid, since this is how jitsi-meet clients
 * expect the bridge's sources to be signaled.
 */
fun ConferenceModifiedIQ.parseSources(): ConferenceSourceMap {
    val parsedSources = ConferenceSourceMap()
    sources?.mediaSources?.forEach { mediaSource ->
        mediaSource.sources.forEach { sourcePacketExtension ->
            val msid = "mixedmslabel mixedlabel${mediaSource.type}0"
            val source = Source(sourcePacketExtension.ssrc, mediaSource.type, sourcePacketExtension.name, msid)
            parsedSources.add(SSRC_OWNER_JVB, EndpointSourceSet(source))
        }
        mediaSource.ssrcGroups.forEach {
            parsedSources.add(SSRC_OWNER_JVB, EndpointSourceSet(SsrcGroup.fromPacketExtension(it, mediaSource.type)))
        }
    }

    return parsedSources
}

fun EndpointSourceSet.toColibriMediaSources(endpointId: String): Sources {
    val mediaSources: MutableMap<String, MediaSource.Builder> = mutableMapOf()

    // We use the signaled "name" of the source at the colibri2 source ID. If a source name isn't signaled, we use a
    // default ID of "endpointId-v0". This allows backwards compat with clients that don't signal source names
    // (and only support a single source).
    sources.forEach { source ->
        val sourceId = source.name ?: Source.nameForIdAndMediaType(endpointId, source.mediaType, 0)
        val mediaSource = mediaSources.computeIfAbsent(sourceId) {
            MediaSource.getBuilder()
                .setType(source.mediaType)
                .setId(sourceId)
        }
        mediaSource.addSource(source.toPacketExtension(encodeMsid = false))
    }
    ssrcGroups.forEach group@{ ssrcGroup ->
        if (ssrcGroup.ssrcs.isEmpty()) return@group

        val firstSource = sources.firstOrNull { ssrcGroup.ssrcs.contains(it.ssrc) }
            ?: throw IllegalStateException("An SsrcGroup in an EndpointSourceSet has an SSRC without a Source")

        val sourceId = firstSource.name ?: Source.nameForIdAndMediaType(endpointId, ssrcGroup.mediaType, 0)
        val mediaSource = mediaSources.computeIfAbsent(sourceId) {
            MediaSource.getBuilder()
                .setType(ssrcGroup.mediaType)
                .setId(sourceId)
        }
        mediaSource.addSsrcGroup(ssrcGroup.toPacketExtension())
    }

    val sources = Sources.getBuilder()
    mediaSources.values.forEach { sources.addMediaSource(it.build()) }
    return sources.build()
}

/** Create a [Colibri2Endpoint] for a specific [ParticipantInfo]. */
internal fun ParticipantInfo.toEndpoint(
    /** Whether the request should have the "create" flag set. */
    create: Boolean,
    /** Whether the request should have the "expire" flag set. */
    expire: Boolean
): Colibri2Endpoint.Builder = Colibri2Endpoint.getBuilder().apply {
    setId(id)
    if (create) {
        setCreate(true)
        setStatsId(statsId)
        addCapability(Capability.CAP_SOURCE_NAME_SUPPORT)
        if (supportsPrivateAddresses) {
            addCapability(Capability.CAP_PRIVATE_ADDRESS_CONNECTIVITY)
        }
        if (useSsrcRewriting) {
            addCapability(Capability.CAP_SSRC_REWRITING_SUPPORT)
        }
    }
    // TODO: find a way to signal sources only when they change? Or is this already the case implicitly?
    if (!expire) {
        setSources(sources.toColibriMediaSources(id))
    }
    if (expire) {
        setExpire(true)
    }
}

internal fun AbstractXMPPConnection.sendIqAndHandleResponseAsync(iq: IQ, block: (IQ?) -> Unit) {
    val stanzaCollector = createStanzaCollectorAndSend(iq)
    TaskPools.ioPool.submit {
        try {
            block(stanzaCollector.nextResult())
        } finally {
            stanzaCollector.cancel()
        }
    }
}

/**
 * The constant value used as owner attribute value of
 * {@link SSRCInfoPacketExtension} for the SSRC which belongs to the JVB.
 */
const val SSRC_OWNER_JVB = "jvb"
