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

import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import java.lang.UnsupportedOperationException
import java.util.concurrent.ConcurrentHashMap

/**
 * A container for sources from multiple endpoints, mapped by the ID of the endpoint. This could contain sources for
 * an entire conference, or a subset.
 *
 * This map is thread safe. Reading and iteration are safe because the underlying map is a [ConcurrentHashMap]. The
 * only mutating operations that are exposed require a lock on [syncRoot].
 *
 * Note that the [java.util.Map] mutating operations (e.g. [java.util.Map.put]) are still visible in Java, but they
 * are not meant to be used and will result in an exception.
 */
open class ConferenceSourceMap(
    /**
     * The sources mapped by endpoint ID.
     * Note that this primary constructor uses the provided [ConcurrentHashMap], and not a copy, as the underlying map.
     */
    private val endpointSourceSets: ConcurrentHashMap<String, EndpointSourceSet> = ConcurrentHashMap()
) : Map<String, EndpointSourceSet> by endpointSourceSets {

    /** Constructs a new [ConferenceSourceMap] from the entries in [map] ([map] itself is not reused). */
    constructor(map: Map<String, EndpointSourceSet>) : this(ConcurrentHashMap(map))
    constructor(vararg entries: Pair<String, EndpointSourceSet>) : this(entries.toMap())
    constructor(owner: String, endpointSourceSet: EndpointSourceSet) : this(owner to endpointSourceSet)
    constructor(owner: String, source: Source) : this(owner, EndpointSourceSet(source))
    constructor(owner: String, contents: List<ContentPacketExtension>) :
        this(owner, EndpointSourceSet.fromJingle(contents))

    constructor(owner: String, sources: Set<Source>, groups: Set<SsrcGroup>) :
        this(owner, EndpointSourceSet(sources, groups))

    /** The lock used for write operations to the map. Can and should be used by extending classes. */
    protected val syncRoot = Any()

    /** Remove the entry associated with [owner]. */
    open fun remove(owner: String) = synchronized(syncRoot) {
        endpointSourceSets.remove(owner)
    }

    /** An unmodifiable view of this [ConferenceSourceMap]. */
    val unmodifiable by lazy { UnmodifiableConferenceSourceMap(endpointSourceSets) }
    fun unmodifiable() = unmodifiable

    /** Create a new [ConferenceSourceMap] instance with the same entries as this one. */
    fun copy(): ConferenceSourceMap = ConferenceSourceMap(ConcurrentHashMap(endpointSourceSets))

    /** Adds the sources of another [ConferenceSourceMap] to this one. */
    open fun add(other: ConferenceSourceMap) = synchronized(syncRoot) {
        other.endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            endpointSourceSets[owner] += endpointSourceSet
        }
    }

    /** Adds [endpointSourceSet] as sources owned by [owner]. */
    open fun add(owner: String, endpointSourceSet: EndpointSourceSet) = synchronized(syncRoot) {
        endpointSourceSets[owner] += endpointSourceSet
    }

    /**
     * Create a compact JSON representation of this [ConferenceSourceMap]. The JSON is a map of an ID of the owner
     * to the compact JSON of its [EndpointSourceSet] (see [EndpointSourceSet.compactJson]).
     */
    fun compactJson(): String = synchronized(syncRoot) {
        buildString {
            append("{")
            endpointSourceSets.entries.forEachIndexed { i, entry ->
                if (i > 0) append(",")
                append(""""${entry.key}":${entry.value.compactJson}""")
            }
            append("}")
        }
    }

    /** Removes the sources of another [ConferenceSourceMap] from this one. */
    open fun remove(other: ConferenceSourceMap) = synchronized(syncRoot) {
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

    operator fun plus(other: ConferenceSourceMap) = copy().apply { add(other) }
    operator fun minus(other: ConferenceSourceMap) = copy().apply { remove(other) }

    /**
     * Creates a list of [ContentPacketExtension]s that describe the sources in this [ConferenceSourceMap].
     */
    fun toJingle(): List<ContentPacketExtension> {
        val contents = mutableMapOf<MediaType, ContentPacketExtension>()
        forEach { (owner, sourceSet) -> sourceSet.toJingle(contents, owner) }
        return contents.values.toList()
    }

    fun createSourcePacketExtensions(mediaType: MediaType): List<SourcePacketExtension> {
        val extensions = mutableListOf<SourcePacketExtension>()
        forEach { (owner, endpointSourceSet) ->
            endpointSourceSet.sources.filter { it.mediaType == mediaType }.forEach { source ->
                extensions.add(source.toPacketExtension(owner))
            }
        }
        return extensions
    }

    fun createSourceGroupPacketExtensions(mediaType: MediaType): List<SourceGroupPacketExtension> {
        val extensions = mutableListOf<SourceGroupPacketExtension>()
        forEach { (_, endpointSourceSet) ->
            endpointSourceSet.ssrcGroups.filter { it.mediaType == mediaType }.forEach { ssrcGroup ->
                extensions.add(ssrcGroup.toPacketExtension())
            }
        }
        return extensions
    }

    /** Use a kotlin map for easy pretty printing. Inefficient. */
    override fun toString(): String = endpointSourceSets.toMap().toString()

    /**
     * Strip simulcast SSRCs from each entry in the map. Modifies the map in place.
     */
    fun stripSimulcast() = map { it.stripSimulcast }

    open fun map(transform: (EndpointSourceSet) -> EndpointSourceSet) = synchronized(syncRoot) {
        endpointSourceSets.forEach { (owner, sources) ->
            val transformed = transform(sources)
            if (transformed.isEmpty()) {
                endpointSourceSets.remove(owner)
            } else {
                endpointSourceSets[owner] = transformed
            }
        }

        this
    }

    /**
     * Remove all sources from this [ConferenceSourceMap] unless their media type is in [retain].
     */
    fun stripByMediaType(
        /** The set of media types to retain, all other media types will be removed */
        retain: Set<MediaType>
    ) = synchronized(syncRoot) {
        if (retain.contains(MediaType.AUDIO) && retain.contains(MediaType.VIDEO)) {
            // Nothing to strip.
            return this
        }

        map { sources ->
            val strippedSources = sources.sources.filter { retain.contains(it.mediaType) }.toSet()
            if (strippedSources.isEmpty()) {
                EndpointSourceSet.EMPTY
            } else {
                val strippedSsrcGroups = sources.ssrcGroups.filter { retain.contains(it.mediaType) }.toSet()
                EndpointSourceSet(strippedSources, strippedSsrcGroups)
            }
        }
    }

    /** Expanded JSON format used for debugging */
    fun toJson() = OrderedJsonObject().apply {
        synchronized(syncRoot) {
            endpointSourceSets.forEach { (owner, sourceSet) -> put(owner, sourceSet.toJson()) }
        }
    }
}

/**
 * A read-only version of [ConferenceSourceMap]. Attempts to modify the map will via [add], [remove] or any of the
 * standard [java.util.Map] mutating methods will result in an exception.
 */
class UnmodifiableConferenceSourceMap(
    endpointSourceSets: ConcurrentHashMap<String, EndpointSourceSet>
) : ConferenceSourceMap(endpointSourceSets) {
    constructor(map: Map<String, EndpointSourceSet>) : this(ConcurrentHashMap(map))

    override fun add(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("add() not supported in unmodifiable view")

    override fun add(owner: String, endpointSourceSet: EndpointSourceSet) =
        throw UnsupportedOperationException("add() not supported in unmodifiable view")

    override fun remove(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")

    override fun remove(owner: String) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")

    override fun map(transform: (EndpointSourceSet) -> EndpointSourceSet) =
        throw UnsupportedOperationException("map() not supported in unmodifiable view")
}
