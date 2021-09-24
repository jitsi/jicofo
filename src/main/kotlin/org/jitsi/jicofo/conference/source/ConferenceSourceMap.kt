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
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jxmpp.jid.Jid
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
    private val endpointSourceSets: ConcurrentHashMap<Jid?, EndpointSourceSet> = ConcurrentHashMap()
) : Map<Jid?, EndpointSourceSet> by endpointSourceSets {

    /** Constructs a new [ConferenceSourceMap] from the entries in [map] ([map] itself is not reused). */
    constructor(map: Map<Jid?, EndpointSourceSet>) : this(ConcurrentHashMap(map))
    constructor(vararg entries: Pair<Jid?, EndpointSourceSet>) : this(entries.toMap())
    constructor(owner: Jid?, endpointSourceSet: EndpointSourceSet) : this(owner to endpointSourceSet)
    constructor(owner: Jid?, source: Source) : this(owner, EndpointSourceSet(source))
    constructor(owner: Jid?, contents: List<ContentPacketExtension>) :
        this(owner, EndpointSourceSet.fromJingle(contents))

    constructor(owner: Jid?, sources: Set<Source>, groups: Set<SsrcGroup>) :
        this(owner, EndpointSourceSet(sources, groups))

    /** The lock used for write operations to the map. Can and should be used by extending classes. */
    protected val syncRoot = Any()

    /** Remove the entry associated with [owner]. */
    open fun remove(owner: Jid?) = synchronized(syncRoot) {
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
    open fun add(owner: Jid?, endpointSourceSet: EndpointSourceSet) = synchronized(syncRoot) {
        endpointSourceSets[owner] += endpointSourceSet
    }

    /**
     * Create a compact JSON representation of this [ConferenceSourceMap]. The JSON is a map of an ID of the owner
     * to the compact JSON of its [EndpointSourceSet] (see [EndpointSourceSet.compactJson]).
     *
     * The ID of the owner is taken from the JID by either taking the resource part (which is the endpoint ID), or
     * the domain (the only use-case for this is bridge-owned sources, for which we use the JID "jid").
     *
     * TODO: migrate away from using [Jid] as the identifier in [ConferenceSourceMap]
     */
    fun compactJson(): String = synchronized(syncRoot) {
        buildString {
            append("{")
            endpointSourceSets.entries.forEachIndexed { i, entry ->
                if (i > 0) append(",")
                // In practice we use either the owner's full JID (for endpoints) or the string "jvb" (for bridges).
                val ownerJid = entry.key
                // The XMPP resource or domain are safe to encode as JSON.
                val ownerId = ownerJid?.resourceOrNull ?: ownerJid?.domain.toString()
                append(""""$ownerId":${entry.value.compactJson}""")
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

    /**
     * Removes all [Source]s that have the [Source.injected] flag from this map.
     * @returns the map
     */
    open fun stripInjected() = synchronized(syncRoot) { strip(stripInjected = true) }

    /** Use a kotlin map for easy pretty printing. Inefficient. */
    override fun toString(): String = endpointSourceSets.toMap().toString()

    /**
     * Strip simulcast SSRCs from each entry in the map. Modifies the map in place.
     * See also [EndpointSourceSet.stripSimulcast].
     */
    open fun stripSimulcast() = synchronized(syncRoot) { strip(stripSimulcast = true) }

    /**
     * Strip simulcast and/or injected SSRCs from each entry in the map. Modifies the map in place.
     *
     * This is defined separately to improve performance because the two operations are often performed together.
     */
    @JvmOverloads
    open fun strip(stripSimulcast: Boolean = false, stripInjected: Boolean = false) = synchronized(syncRoot) {
        // Nothing to strip
        if (!stripSimulcast && !stripInjected) return this

        endpointSourceSets.forEach { (owner, sources) ->
            val stripped = when {
                stripSimulcast -> sources.stripSimulcast(stripInjected = stripInjected)
                stripInjected -> sources.stripInjected()
                else -> sources
            }
            if (stripped.isEmpty()) {
                endpointSourceSets.remove(owner)
            } else {
                endpointSourceSets[owner] = stripped
            }
        }
        this
    }

    /**
     * Remove all sources from this [ConferenceSourceMap] unless their media type is in [retain].
     */
    open fun stripByMediaType(
        /** The set of media types to retain, all other media types will be removed */
        retain: Set<MediaType>
    ) = synchronized(syncRoot) {
        if (retain.contains(MediaType.AUDIO) && retain.contains(MediaType.VIDEO)) {
            // Nothing to strip.
            return this
        }
        endpointSourceSets.forEach { (owner, sources) ->
            val strippedSources = sources.sources.filter { retain.contains(it.mediaType) }.toSet()
            if (strippedSources.isEmpty()) {
                endpointSourceSets.remove(owner)
            } else {
                val strippedSsrcGroups = sources.ssrcGroups.filter { retain.contains(it.mediaType) }.toSet()
                endpointSourceSets[owner] = EndpointSourceSet(strippedSources, strippedSsrcGroups)
            }
        }
        this
    }
}

/**
 * A read-only version of [ConferenceSourceMap]. Attempts to modify the map will via [add], [remove] or any of the
 * standard [java.util.Map] mutating methods will result in an exception.
 */
class UnmodifiableConferenceSourceMap(
    endpointSourceSets: ConcurrentHashMap<Jid?, EndpointSourceSet>
) : ConferenceSourceMap(endpointSourceSets) {
    constructor(map: Map<Jid?, EndpointSourceSet>) : this(ConcurrentHashMap(map))

    override fun add(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("add() not supported in unmodifiable view")

    override fun add(owner: Jid?, endpointSourceSet: EndpointSourceSet) =
        throw UnsupportedOperationException("add() not supported in unmodifiable view")

    override fun remove(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")

    override fun remove(owner: Jid?) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")

    override fun stripInjected() =
        throw UnsupportedOperationException("removeInjected() not supported in unmodifiable view")

    override fun stripSimulcast() =
        throw UnsupportedOperationException("stripSimulcast() not supported in unmodifiable view")

    override fun strip(stripSimulcast: Boolean, stripInjected: Boolean) =
        throw UnsupportedOperationException("strip() not supported in unmodifiable view")

    override fun stripByMediaType(retain: Set<MediaType>) =
        throw UnsupportedOperationException("stripByMediaType() is not supported in unmodifiable view")
}

fun EndpointSourceSet.stripInjected() = EndpointSourceSet(
    sources.filter { !it.injected }.toSet(),
    // Just maintain the groups. We never use groups with injected SSRCs, and "injected" should go away at some point.
    ssrcGroups
)
