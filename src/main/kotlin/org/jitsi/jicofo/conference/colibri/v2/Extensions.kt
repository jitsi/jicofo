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
package org.jitsi.jicofo.conference.colibri.v2

import org.jitsi.jicofo.conference.ParticipantInviteRunnable
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension

fun ConferenceModifiedIQ.parseTransport(endpointId: String): IceUdpTransportPacketExtension? {
    return endpoints.find { it.id == endpointId }?.transport?.iceUdpTransport
}

fun ConferenceModifiedIQ.parseSources(): ConferenceSourceMap {
    val parsedSources = ConferenceSourceMap()
    val owner = ParticipantInviteRunnable.SSRC_OWNER_JVB
    sources?.mediaSources?.forEach { mediaSource ->
        mediaSource.sources.forEach { sourcePacketExtension ->
            parsedSources.add(owner, EndpointSourceSet(Source(mediaSource.type, sourcePacketExtension)))
        }
        mediaSource.ssrcGroups.forEach {
            parsedSources.add(owner, EndpointSourceSet(SsrcGroup.fromPacketExtension(it, mediaSource.type)))
        }
    }

    return parsedSources
}

fun ConferenceSourceMap.toColibriMediaSources(): Sources {
    val mediaSources: MutableMap<MediaType, MediaSource.Builder> = mutableMapOf()

    forEach { endpointSourceSet ->
        endpointSourceSet.value.sources.forEach { source ->
            val mediaSource = mediaSources.computeIfAbsent(source.mediaType) {
                MediaSource.getBuilder().setType(source.mediaType)
            }
            mediaSource.addSource(source.toPacketExtension())
        }
        endpointSourceSet.value.ssrcGroups.forEach { ssrcGroup ->
            val mediaSource = mediaSources.computeIfAbsent(ssrcGroup.mediaType) {
                MediaSource.getBuilder().setType(ssrcGroup.mediaType)
            }
            mediaSource.addSsrcGroup(ssrcGroup.toPacketExtension())
        }
    }

    val sources = Sources.getBuilder()
    mediaSources.values.forEach { sources.addMediaSource(it.build()) }
    return sources.build()
}

fun ContentPacketExtension.toMedia(): Media? {
    val media = when (name.lowercase()) {
        "audio" -> Media.getBuilder().setType(MediaType.AUDIO)
        "video" -> Media.getBuilder().setType(MediaType.VIDEO)
        else -> return null
    }

    getFirstChildOfType(RtpDescriptionPacketExtension::class.java)?.let {
        it.payloadTypes.forEach { payloadTypePacketExtension ->
            media.addPayloadType(payloadTypePacketExtension)
        }
        it.extmapList.forEach { rtpHdrExtPacketExtension ->
            media.addRtpHdrExt(rtpHdrExtPacketExtension)
        }
    }
    return media.build()
}