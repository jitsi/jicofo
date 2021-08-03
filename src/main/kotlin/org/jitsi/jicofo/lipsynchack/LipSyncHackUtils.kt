/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.jicofo.lipsynchack

import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.SsrcGroup
import org.jitsi.jicofo.conference.source.SsrcGroupSemantics
import org.jitsi.utils.MediaType
import org.jxmpp.jid.Jid

/** This is temporary until we fully transition to [ConferenceSourceMap], etc. */
fun fromMediaSourceMap(
    mediaSourceMap: MediaSourceMap,
    mediaSourceGroupMap: MediaSourceGroupMap
): ConferenceSourceMap {
    val sourcesByEndpoint = mutableMapOf<Jid?, MutableSet<Source>>()
    val ssrcGroupsByEndpoint = mutableMapOf<Jid?, MutableSet<SsrcGroup>>()

    mediaSourceMap.mediaTypes.forEach { mediaType ->
        val sourcePacketExtensions = mediaSourceMap.getSourcesForMedia(mediaType)
        sourcePacketExtensions.forEach { sourcePacketExtension ->
            val owner = SSRCSignaling.getSSRCOwner(sourcePacketExtension)
            val ownerSources = sourcesByEndpoint.computeIfAbsent(owner) { mutableSetOf() }
            ownerSources.add(Source(MediaType.parseString(mediaType), sourcePacketExtension))
        }
    }

    mediaSourceGroupMap.mediaTypes.forEach { mediaType ->
        mediaSourceGroupMap.getSourceGroupsForMedia(mediaType).forEach { sourceGroup ->
            val semantics = SsrcGroupSemantics.fromString(sourceGroup.semantics)
            val sources = sourceGroup.sources.map { it.ssrc }.toList()
            // Try to find the owner encoded in XML in the SourceGroup
            var owner = sourceGroup.sources.map { SSRCSignaling.getSSRCOwner(it) }.filterNotNull().firstOrNull()
            if (owner == null) {
                // Otherwise try to find it based on the sources from the other map.
                sourcesByEndpoint.forEach { (endpoint, endpointSources) ->
                    sources.forEach { groupSource ->
                        if (endpointSources.map { it.ssrc }.contains(groupSource)) {
                            owner = endpoint
                        }
                    }
                }
            }

            val ownerSsrcGroups = ssrcGroupsByEndpoint.computeIfAbsent(owner) { mutableSetOf() }
            ownerSsrcGroups.add(SsrcGroup(semantics, sources, MediaType.parseString(mediaType)))
        }
    }

    val endpointSourceSets = mutableMapOf<Jid?, EndpointSourceSet>()
    sourcesByEndpoint.forEach { (owner, sources) ->
        endpointSourceSets[owner] = EndpointSourceSet(
            sources,
            ssrcGroupsByEndpoint[owner] ?: emptySet()
        )
    }
    ssrcGroupsByEndpoint.forEach { (owner, ssrcGroup) ->
        // If the owner had sources this group would have already been added above.
        if (endpointSourceSets.none { it.key == owner }) {
            endpointSourceSets[owner] = EndpointSourceSet(
                emptySet(),
                ssrcGroup,
            )
        }
    }

    return ConferenceSourceMap(endpointSourceSets)
}

/** This is temporary until we fully transition to [ConferenceSourceMap], etc. */
fun ConferenceSourceMap.toMediaSourceMap(): SourceMapAndGroupMap {
    val sources = MediaSourceMap()
    val groups = MediaSourceGroupMap()

    forEach { (owner, endpointSourceSet) ->
        endpointSourceSet.sources.forEach { source ->
            sources.addSource(source.mediaType.toString(), source.toPacketExtension(owner))
        }
        endpointSourceSet.ssrcGroups.forEach { group ->
            groups.addSourceGroup("video", SourceGroup(group.toPacketExtension()))
        }
    }
    return SourceMapAndGroupMap(sources, groups)
}

data class SourceMapAndGroupMap(val sources: MediaSourceMap, val groups: MediaSourceGroupMap)
