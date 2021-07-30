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
package org.jitsi.jicofo.conference.source

import org.jitsi.protocol.xmpp.util.MediaSourceGroupMap
import org.jitsi.protocol.xmpp.util.MediaSourceMap
import org.jitsi.protocol.xmpp.util.SSRCSignaling
import org.jitsi.protocol.xmpp.util.SourceGroup
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jxmpp.jid.Jid
import java.lang.UnsupportedOperationException

/**
 * A container for sources from multiple endpoints, mapped by the ID of the endpoint. This could contain sources for
 * an entire conference, or a subset.
 * This map is not thread safe.
 */
open class ConferenceSourceMap(
    /**
     * The sources mapped by endpoint ID.
     */
    private val endpointSourceSets: MutableMap<Jid?, EndpointSourceSet> = mutableMapOf()
) : Map<Jid?, EndpointSourceSet> by endpointSourceSets {

    constructor(vararg entries: Pair<Jid?, EndpointSourceSet>) : this(
        mutableMapOf<Jid?, EndpointSourceSet>().apply {
            entries.forEach { (k, v) ->
                this[k] = v
            }
        }
    )

    /**
     * An unmodifiable view of this [ConferenceSourceMap].
     */
    val unmodifiable by lazy { UnmodifiableConferenceSourceMap(endpointSourceSets) }
    fun unmodifiable() = unmodifiable

    /** Adds the sources of another [ConferenceSourceMap] to this. */
    open fun add(other: ConferenceSourceMap) {
        other.endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            endpointSourceSets[owner] += endpointSourceSet
        }
    }

    /** Removes the sources of another [ConferenceSourceMap] from this one. */
    open fun remove(other: ConferenceSourceMap) {
        other.endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            val existing = endpointSourceSets[owner]
            if (existing != null) {
                val result = existing - endpointSourceSet
                // TODO: do we want to allow lingering SsrcGroups? Should we actually remove SsrcGroups when their
                // sources are removed?
                if (result.isEmpty()) {
                    endpointSourceSets.remove(owner)
                } else {
                    endpointSourceSets[owner] = result
                }
            }
        }
    }

    /**
     * Creates a list of [ContentPacketExtension]s that describe the sources in this [ConferenceSourceMap].
     */
    fun toJingle(): List<ContentPacketExtension> {
        val contents = mutableMapOf<MediaType, ContentPacketExtension>()
        forEach { (owner, sourceSet) -> sourceSet.toJingle(contents, owner) }
        return contents.values.toList()
    }

    /** This is temporary until we fully transition to [ConferenceSourceMap], etc. */
    fun toMediaSourceMap(): SourceMapAndGroupMap {
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

    fun copy(): ConferenceSourceMap = ConferenceSourceMap(endpointSourceSets.toMutableMap())

    fun removeInjected() = this.apply {
        endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            val withoutInjected = endpointSourceSet.withoutInjected()
            if (withoutInjected.isEmpty()) {
                endpointSourceSets.remove(owner)
            } else {
                endpointSourceSets[owner] = withoutInjected
            }
        }
    }

    companion object {

        /** This is temporary until we fully transition to [ConferenceSourceMap], etc. */
        @JvmStatic
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
                    ownerSsrcGroups.add(SsrcGroup(semantics, sources))
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
    }
}

/**
 * A read-only version of [ConferenceSourceMap]. Attempts to modify the map will via [add], [remove] or any of the
 * standard [java.lang.Map] mutating methods will result in an exception.
 */
class UnmodifiableConferenceSourceMap(
    endpointSourceSets: MutableMap<Jid?, EndpointSourceSet>
) : ConferenceSourceMap(endpointSourceSets) {
    override fun add(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("add() not supported in unmodifiable view")
    override fun remove(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")
}

data class SourceMapAndGroupMap(val sources: MediaSourceMap, val groups: MediaSourceGroupMap)

fun EndpointSourceSet.withoutInjected() = EndpointSourceSet(
    sources.filter { !it.injected }.toSet(),
    // Just maintain the groups. We never use groups with injected SSRCs, and "injected" should go away at some point.
    ssrcGroups
)
