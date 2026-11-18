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
package org.jitsi.jicofo.conference.source

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.createLogger
import java.lang.IllegalStateException

/**
 * An extension of [ConferenceSourceMap] with an API for addition ([tryToAdd]) and removal ([tryToRemove]) of sources
 * with validation. Note that the standard [add] and [remove] APIs are still available and do NOT perform validation.
 *
 * Validation means that the addition of sources does not lead to a conflict of SSRC, MSID or source name with another
 * endpoint in the conference, that source names have the format that clients use (see [checkSourceNameFormat]), and
 * that the addition/removal operations leave the endpoint's resulting set of sources valid. The checks of the source
 * names between signaled sources run only if [validateSourceNames] is set.
 *
 * A source without a name is checked with the name that the bridge uses for it, "<owner>-a0" or "<owner>-v0" (see
 * [effectiveName]).
 *
 * A set of sources is valid if there are no conflicts in SSRC or MSID, all sources within a group have the same MSID,
 * all SSRCs within a group have a corresponding source, and other similar checks pass. See [validateEndpointSourceSet].
 *
 * The map can also hold reserved sources ([tryToReserve]). These are sources of an endpoint that are not signaled to
 * the participants, for example the sources of a voice agent. They are not part of the map's content, and [add],
 * [remove] and [tryToRemove] do not change them. A reserved SSRC, MSID or source name can not be used by a signaled
 * source, even one with the same owner, and a reserved source can not use the SSRC, MSID or name of a signaled source.
 *
 * This implementation inherits the thread safety characteristics of [ConferenceSourceMap].
 */
class ValidatingConferenceSourceMap(
    private val maxSsrcsPerUser: Int,
    private val maxSsrcGroupsPerUser: Int,
    /**
     * Whether to check the names of signaled sources: their format (see [checkSourceNameFormat]), and that two
     * endpoints do not use the same name. The names of reserved sources are checked regardless.
     */
    private val validateSourceNames: Boolean = true
) : ConferenceSourceMap() {
    val logger = createLogger()

    /**
     * Maps the SSRC of a signaled source to the ID of the endpoint that owns it. Used to detect cross-endpoint
     * conflicts efficiently.
     */
    private val ssrcToOwnerMap = mutableMapOf<Long, String>()

    /**
     * Maps the MSID of a signaled source to the ID of the endpoint that owns it. Used to detect cross-endpoint
     * conflicts efficiently.
     */
    private val msidToOwnerMap = mutableMapOf<String, String>()

    /**
     * Maps the name of a signaled source (see [effectiveName]) to the IDs of the endpoints that use it, and for each
     * one whether its sources with that name are synthetic. Several sources of the same endpoint can share a name (for
     * example the SSRCs of a simulcast stream). Several endpoints can use the same name only if [validateSourceNames]
     * is not set.
     */
    private val nameToOwnersMap = mutableMapOf<String, MutableMap<String, Boolean>>()

    /**
     * The reserved sources of each endpoint (see [tryToReserve]). There are few of them (for example one for each
     * voice agent), so a linear search is used to find a conflict.
     */
    private val reservedSources = mutableMapOf<String, Set<Source>>()

    /** The endpoint that has a reserved source that matches [predicate], or null. */
    private fun reservedOwner(predicate: (String, Source) -> Boolean): String? =
        reservedSources.entries.firstOrNull { (owner, sources) -> sources.any { predicate(owner, it) } }?.key

    /** Whether a signaled or reserved source of any endpoint uses [ssrc]. */
    fun isSsrcUsed(ssrc: Long): Boolean = synchronized(syncRoot) {
        ssrcToOwnerMap.containsKey(ssrc) || reservedOwner { _, it -> it.ssrc == ssrc } != null
    }

    /**
     * Checks that [name], the name of a [source] of [owner], has the format that clients use: "<owner>-a<index>" for
     * an audio source and "<owner>-v<index>" for a video source, for example "abcd1234-a0". A synthetic source (one
     * that jicofo adds) can also have a suffix of up to 32 letters, digits, "_" or "-" after a ".", for example
     * "abcd1234-a0.pt-BR" for a translation. A name of one endpoint is never a valid name of another endpoint.
     */
    @Throws(SourceNameNotOwnedException::class)
    private fun checkSourceNameFormat(owner: String, source: Source, name: String) {
        val prefix = "$owner-"
        val rest = name.removePrefix(prefix)
        val token = if (source.synthetic) rest.substringBefore('.') else rest
        val suffix = if (source.synthetic && rest.contains('.')) rest.substringAfter('.') else null
        if (!name.startsWith(prefix) ||
            !token.matches(if (source.mediaType == MediaType.AUDIO) AUDIO_TOKEN else VIDEO_TOKEN) ||
            (suffix != null && !suffix.matches(SUFFIX))
        ) {
            throw SourceNameNotOwnedException(name, owner)
        }
    }

    /**
     * Checks that [owner] can use [source] as a signaled source ([reserve] is false) or as a reserved source
     * ([reserve] is true):
     * - The SSRC is valid, and no signaled source of any endpoint (including [owner]) uses it. No reserved source
     * uses it, except a reserved source of [owner] when [reserve] is true (because the reservation is replaced).
     * - No other endpoint uses the MSID.
     * - For a signaled source, if [validateSourceNames] is set: the name has the format checked by
     * [checkSourceNameFormat], and no other endpoint uses the name.
     * - A synthetic source and a source that is not synthetic do not have the same name.
     *
     * Signaled and reserved sources are kept apart: a signaled source can not use the MSID or name of a reserved
     * source, and the reverse, even when the owner is the same.
     */
    @Throws(ValidationFailedException::class)
    private fun checkConflicts(owner: String, source: Source, reserve: Boolean) {
        if (source.ssrc <= 0 || source.ssrc >= 0x1_0000_0000) {
            throw InvalidSsrcException(source.ssrc)
        }
        ssrcToOwnerMap[source.ssrc]?.let { throw SsrcAlreadyUsedException(source.ssrc, it) }
        reservedOwner { o, it -> it.ssrc == source.ssrc && !(reserve && o == owner) }?.let {
            throw SsrcAlreadyUsedException(source.ssrc, "$it (reserved)")
        }

        source.msid?.let { msid ->
            val signaledOwner = msidToOwnerMap[msid]
            val reservedOwner = reservedOwner { o, it -> it.msid == msid && !(reserve && o == owner) }
            if (reserve && signaledOwner != null) throw MsidConflictException(msid, signaledOwner)
            if (!reserve && signaledOwner != null && signaledOwner != owner) {
                throw MsidConflictException(msid, signaledOwner)
            }
            if (reservedOwner != null) throw MsidConflictException(msid, "$reservedOwner (reserved)")
        }

        if (source.name != null && (reserve || validateSourceNames)) {
            checkSourceNameFormat(owner, source, source.name)
        }
        val name = effectiveName(owner, source)
        reservedOwner { o, it -> effectiveName(o, it) == name && !(reserve && o == owner) }?.let {
            throw SourceNameConflictException(name, "$it (reserved)")
        }
        nameToOwnersMap[name]?.let { owners ->
            owners.keys.firstOrNull { reserve || (validateSourceNames && it != owner) }?.let {
                throw SourceNameConflictException(name, it)
            }
            owners.entries.firstOrNull { it.value != source.synthetic }?.let {
                throw SourceNameConflictException(name, it.key)
            }
        }
    }

    /**
     * Attempts to reserve [sources] for [owner], replacing the sources that [owner] reserved before. Reserved sources
     * are not part of the map's content (they are not returned by [get] and are not signaled), but no signaled source
     * can use their SSRCs, MSIDs or names. Use an empty set to release the reservation.
     *
     * @throws ValidationFailedException if a source has an invalid SSRC, if [sources] repeats an SSRC, if a name does
     * not have the required format (see [checkSourceNameFormat]), or if a source conflicts (see [checkConflicts]) with
     * a signaled source of any endpoint or with a reserved source of another endpoint. The state of the map remains
     * unchanged.
     */
    @Throws(ValidationFailedException::class)
    fun tryToReserve(owner: String, sources: Set<Source>) = synchronized(syncRoot) {
        val ssrcs = mutableSetOf<Long>()
        sources.forEach { source ->
            checkConflicts(owner, source, reserve = true)
            if (!ssrcs.add(source.ssrc)) {
                throw SsrcAlreadyUsedException(source.ssrc, owner)
            }
        }
        if (sources.isEmpty()) {
            reservedSources.remove(owner)
        } else {
            reservedSources[owner] = sources.toSet()
        }
    }

    /** The sources that [owner] reserved (see [tryToReserve]). */
    fun getReserved(owner: String): Set<Source> = synchronized(syncRoot) { reservedSources[owner] ?: emptySet() }

    /** The reserved sources in the expanded JSON format used for debugging (see [toJson]). */
    fun reservedToJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        synchronized(syncRoot) {
            reservedSources.forEach { (owner, sources) -> set<ObjectNode>(owner, EndpointSourceSet(sources).toJson()) }
        }
    }

    /**
     * Attempts to add [sourcesToAdd] as sources owned by [owner]. The attempt is successful if the addition does not
     * introduce any conflicts in SSRC, MSID or source name (with signaled or reserved sources, see [checkConflicts]),
     * the names have the required format (if [validateSourceNames] is set), and the resulting set of sources for
     * [owner] is valid (see [validateEndpointSourceSet]).
     *
     * If the attempt is successful, the sources are added to [owner]'s sources and the accepted sources are returned.
     * The accepted sources might differ from [sourcesToAdd] in their SSRC groups, because empty and duplicate groups
     * are silently ignored.
     *
     * If the attempt is unsuccessful, a [ValidationFailedException] is thrown and the state of the map remains
     * unchanged.
     *
     * @throws ValidationFailedException if the attempt to add sources is unsuccessful. The state of the map remains
     * unchanged.
     * @return The sources that have been accepted and added.
     */
    @Throws(ValidationFailedException::class)
    fun tryToAdd(owner: String, sourcesToAdd: EndpointSourceSet): EndpointSourceSet = synchronized(syncRoot) {
        val existingSourceSet = this[owner] ?: EndpointSourceSet.EMPTY

        // Check for validity of the new SSRCs, and conflicts with other endpoints (and with reserved sources).
        sourcesToAdd.sources.forEach { source ->
            checkConflicts(owner, source, reserve = false)
            if (existingSourceSet.sources.size + sourcesToAdd.sources.size > maxSsrcsPerUser) {
                throw SsrcLimitExceededException(maxSsrcsPerUser)
            }
        }

        // If we rejected any of the SSRCs we'd throw above. We accepted them all.
        val acceptedSources = sourcesToAdd.sources
        val resultingSources = existingSourceSet.sources + acceptedSources
        val resultingSsrcs = resultingSources.map { it.ssrc }.toSet()

        val acceptedGroups = mutableSetOf<SsrcGroup>()
        sourcesToAdd.ssrcGroups.forEach {
            // We just ignore empty groups and groups which have been signaled multiple times.
            when {
                it.ssrcs.isEmpty() -> logger.info("Empty group signaled, ignoring.")
                existingSourceSet.ssrcGroups.contains(it) -> logger.info("Duplicate group signaled, ignoring.")
                !resultingSsrcs.containsAll(it.ssrcs) -> throw GroupContainsUnknownSourceException(it.ssrcs)
                else -> acceptedGroups.add(it)
            }
        }
        if (existingSourceSet.ssrcGroups.size + acceptedGroups.size > maxSsrcGroupsPerUser) {
            throw SsrcGroupLimitExceededException(maxSsrcGroupsPerUser)
        }

        val resultingSourceSet = EndpointSourceSet(resultingSources, existingSourceSet.ssrcGroups + acceptedGroups)
        validateEndpointSourceSet(resultingSourceSet)

        val acceptedSourceSet = EndpointSourceSet(acceptedSources, acceptedGroups)
        add(ConferenceSourceMap(owner to acceptedSourceSet))
        return acceptedSourceSet
    }

    /**
     * Attempts to remove [sourcesToRemove] as sources owned by [owner]. The attempt is successful if all referenced
     * sources and groups are indeed owned by [owner], and the resulting set of sources for [owner] is valid (see
     * [validateEndpointSourceSet]).
     *
     * If the attempt is successful, the sources are removed from [owner]'s sources and the set of removed sources is
     * returned. The set of removed sources might differ from [sourcesToRemove] because:
     * 1. We only match a source's SSRC, so a source added with parameters (MSID, etc) can be removed by simply
     * referencing the SSRC.
     * 2. We automatically remove any groups that contain any of the removed sources.
     *
     * If the attempt is unsuccessful, a [ValidationFailedException] is thrown and the state of the map remains
     * unchanged.
     *
     * @throws ValidationFailedException if the attempt to remove sources is unsuccessful. The state of the map remains
     * unchanged.
     * @return The sources that have been removed.
     */
    @Throws(ValidationFailedException::class)
    fun tryToRemove(owner: String, sourcesToRemove: EndpointSourceSet): EndpointSourceSet = synchronized(syncRoot) {
        if (sourcesToRemove.isEmpty()) return EndpointSourceSet.EMPTY

        val existingSources = this[owner]
        if (existingSources == null || existingSources.isEmpty()) {
            throw SourceDoesNotExistException()
        }

        val sourcesAcceptedToBeRemoved = mutableSetOf<Source>()
        sourcesToRemove.sources.forEach { source ->
            // Be lenient and allow sources to be removed without matching the original parameters (media type, msid).
            val existingSource = existingSources.sources.find { it.ssrc == source.ssrc }
                ?: throw SourceDoesNotExistException(source.ssrc)
            sourcesAcceptedToBeRemoved.add(existingSource)
        }
        val ssrcsAcceptedToRemove = sourcesAcceptedToBeRemoved.map { it.ssrc }

        if (!existingSources.ssrcGroups.containsAll(sourcesToRemove.ssrcGroups)) {
            throw SourceGroupDoesNotExistException()
        }
        val groupsAcceptedToBeRemoved = mutableSetOf(*sourcesToRemove.ssrcGroups.toTypedArray())

        // Also automatically remove groups some of whose sources are removed.
        existingSources.ssrcGroups.forEach { existingGroup ->
            if (existingGroup.ssrcs.any { ssrcsAcceptedToRemove.contains(it) }) {
                groupsAcceptedToBeRemoved.add(existingGroup)
            }
        }

        val resultingEndpointSourceSet = EndpointSourceSet(
            existingSources.sources - sourcesAcceptedToBeRemoved,
            existingSources.ssrcGroups - groupsAcceptedToBeRemoved
        )
        validateEndpointSourceSet(resultingEndpointSourceSet)

        val acceptedSourceSet = EndpointSourceSet(sourcesAcceptedToBeRemoved, groupsAcceptedToBeRemoved)
        remove(ConferenceSourceMap(owner to acceptedSourceSet))
        return acceptedSourceSet
    }

    /** Override [add] to keep the [ssrcToOwnerMap], [msidToOwnerMap] and [nameToOwnersMap] maps updated. */
    override fun add(other: ConferenceSourceMap) = synchronized(syncRoot) {
        super.add(other).also {
            other.forEach { (owner, endpointSourceSet) -> sourceSetAdded(owner, endpointSourceSet) }
        }
    }

    /** Override [add] to keep the [ssrcToOwnerMap], [msidToOwnerMap] and [nameToOwnersMap] maps updated. */
    override fun add(owner: String, endpointSourceSet: EndpointSourceSet) = synchronized(syncRoot) {
        super.add(owner, endpointSourceSet).also {
            sourceSetAdded(owner, endpointSourceSet)
        }
    }

    private fun sourceSetAdded(owner: String, endpointSourceSet: EndpointSourceSet) = synchronized(syncRoot) {
        endpointSourceSet.sources.forEach { source ->
            ssrcToOwnerMap[source.ssrc] = owner
            source.msid?.let {
                msidToOwnerMap[it] = owner
            }
            nameToOwnersMap.getOrPut(effectiveName(owner, source)) { mutableMapOf() }[owner] = source.synthetic
        }
    }

    /** Override [remove] to keep the [ssrcToOwnerMap], [msidToOwnerMap] and [nameToOwnersMap] maps updated. */
    override fun remove(other: ConferenceSourceMap) = synchronized(syncRoot) {
        super.remove(other).also {
            other.forEach { (owner, ownerRemovedSourceSet) -> sourceSetRemoved(owner, ownerRemovedSourceSet) }
        }
    }

    /** Override [remove] to keep the [ssrcToOwnerMap], [msidToOwnerMap] and [nameToOwnersMap] maps updated. */
    override fun remove(owner: String): EndpointSourceSet? = synchronized(syncRoot) {
        val ownerRemovedSourceSet = super.remove(owner)
        ownerRemovedSourceSet?.let {
            sourceSetRemoved(owner, it)
        }
        return ownerRemovedSourceSet
    }

    /**
     * Update the local maps ([ssrcToOwnerMap], [msidToOwnerMap] and [nameToOwnersMap]) after the removal of a source
     * set.
     * @param owner the owner of the removed source set.
     * @param endpointSourceSet the source set which has already been removed.
     */
    private fun sourceSetRemoved(owner: String, endpointSourceSet: EndpointSourceSet) = synchronized(syncRoot) {
        val ownerRemainingSourceSet = this[owner]
        endpointSourceSet.sources.forEach { source ->
            ssrcToOwnerMap.remove(source.ssrc)
            source.msid?.let { sourceMsid ->
                if (ownerRemainingSourceSet == null ||
                    ownerRemainingSourceSet.sources.none { it.msid == sourceMsid }
                ) {
                    msidToOwnerMap.remove(sourceMsid)
                }
            }
            val sourceName = effectiveName(owner, source)
            if (ownerRemainingSourceSet == null ||
                ownerRemainingSourceSet.sources.none { effectiveName(owner, it) == sourceName }
            ) {
                nameToOwnersMap[sourceName]?.let { owners ->
                    owners.remove(owner)
                    if (owners.isEmpty()) nameToOwnersMap.remove(sourceName)
                }
            }
        }
    }

    companion object {
        private val AUDIO_TOKEN = Regex("a[0-9]{1,4}")
        private val VIDEO_TOKEN = Regex("v[0-9]{1,4}")
        private val SUFFIX = Regex("[A-Za-z0-9_-]{1,32}")

        /**
         * The name of [source] of [owner] as the bridge knows it: its name, or "<owner>-a0" / "<owner>-v0" if it has
         * none (see [Source.nameForIdAndMediaType]).
         */
        private fun effectiveName(owner: String, source: Source): String =
            source.name ?: Source.nameForIdAndMediaType(owner, source.mediaType, 0)

        /**
         * Checks if a given [EndpointSourceSet] is internally valid. The checks include:
         * -- FID groups have exactly 2 SSRCs
         * -- Grouped sources have an MSID
         * -- Sources within a group have the same MSID
         * -- There are no MSID conflicts
         */
        @Throws(ValidationFailedException::class)
        private fun validateEndpointSourceSet(endpointSourceSet: EndpointSourceSet) {
            endpointSourceSet.ssrcGroups.forEach { group ->
                if (group.ssrcs.isEmpty()) {
                    throw IllegalStateException("Empty group should have been filtered out.")
                }
                var groupMsid: String? = null
                group.ssrcs.forEach { ssrc ->
                    // We are doing a linear search in a list, but the list only has sources for one endpoint, so
                    // its size is limited to [maxSsrcsPerUser].
                    val source = endpointSourceSet.sources.find { it.ssrc == ssrc }
                        ?: throw IllegalStateException(
                            "Groups with SSRCs that have no corresponding source should have been filtered out."
                        )

                    if (group.semantics == SsrcGroupSemantics.Fid && group.ssrcs.size != 2) {
                        throw InvalidFidGroupException(group.ssrcs)
                    }

                    if (source.msid == null) {
                        throw RequiredParameterMissingException("msid")
                    }
                    if (groupMsid == null) {
                        groupMsid = source.msid
                    }

                    if (source.msid != groupMsid) {
                        throw GroupMsidMismatchException(group.ssrcs)
                    }
                }
            }

            // Audio and video sources can have the same MSID.
            listOf(MediaType.AUDIO, MediaType.VIDEO).forEach { mediaType ->
                // This groups all sources according to the extended group that they belong to, i.e. a SIM group gets
                // associated with all of its sources plus the sources in FID groups associated with the SIM group's
                // sources. For example given these groups:
                // SIM(1, 2, 3)
                // FID(1, 4)
                // FID(2, 5)
                // FID(3, 6)
                // FID(111, 222)
                // And non-grouped source 333 this will associate the SIM group with sources 1..6, the lonely FID group
                // with sources 111 and 222, and a dummy single-source group with source 333.
                //
                // This is technically O(s*g^2) where s is the number of sources and g is the number of groups. But in
                // practice both s and g are very small (at most 20 and 4 respectively).
                val grouped: Map<SsrcGroup, List<Source>> =
                    endpointSourceSet.sources
                        .filter { it.mediaType == mediaType && it.msid != null }
                        .groupBy { groupBySimulcastGroup(it.ssrc, endpointSourceSet.ssrcGroups) }

                // Here we check for MSID conflicts, i.e. we make sure that each group has a unique MSID.
                val msidsSeen = mutableSetOf<String?>()
                grouped.values.map { it[0] }.forEach { source ->
                    if (msidsSeen.contains(source.msid)) throw MsidConflictException(source.msid ?: "NONE")
                    msidsSeen.add(source.msid)
                }
            }
        }

        /** Returns the extended group to which an SSRC belongs. See [validateEndpointSourceSet]. */
        private fun groupBySimulcastGroup(ssrc: Long, ssrcGroups: Set<SsrcGroup>): SsrcGroup {
            val simGroup = ssrcGroups.find {
                it.semantics == SsrcGroupSemantics.Sim && it.ssrcs.contains(ssrc)
            }
            if (simGroup != null) {
                return simGroup
            }

            val fidGroup = ssrcGroups.find {
                it.semantics == SsrcGroupSemantics.Fid && it.ssrcs.contains(ssrc)
            }
            if (fidGroup != null) {
                if (ssrc == fidGroup.ssrcs[1]) {
                    val simGroup2 = ssrcGroups.find {
                        it.semantics == SsrcGroupSemantics.Sim && it.ssrcs.contains(fidGroup.ssrcs[0])
                    }
                    if (simGroup2 != null) {
                        return simGroup2
                    }
                }
                return fidGroup
            }

            // Dummy group just for this source.
            return SsrcGroup(SsrcGroupSemantics.Sim, listOf(ssrc))
        }
    }
}

sealed class ValidationFailedException(message: String?) : Exception(message)
class InvalidSsrcException(ssrc: Long) : ValidationFailedException("Invalid SSRC: $ssrc.")
class SsrcLimitExceededException(limit: Int) : ValidationFailedException("SSRC limit ($limit) exceeded.")
class SsrcGroupLimitExceededException(limit: Int) : ValidationFailedException("ssrc-group limit ($limit) exceeded.")
class SsrcAlreadyUsedException(ssrc: Long, owner: String? = null) : ValidationFailedException(
    "SSRC is already used: $ssrc${owner?.let { " (by $it)" } ?: ""}."
)
class RequiredParameterMissingException(name: String) : ValidationFailedException(
    "Required source parameter '$name' is not present."
)
class GroupMsidMismatchException(ssrcs: List<Long>) : ValidationFailedException(
    "SsrcGroup contains sources with different MSIDs: $ssrcs."
)
class MsidConflictException(msid: String, owner: String? = null) : ValidationFailedException(
    "MSID is already used: $msid${owner?.let { " (by $it)" } ?: ""}."
)
class GroupContainsUnknownSourceException(groupSsrcs: List<Long>) : ValidationFailedException(
    "An SSRC group contains an SSRC, which hasn't been signaled as a source: $groupSsrcs."
)
class InvalidFidGroupException(groupSsrcs: List<Long>) : ValidationFailedException("Invalid FID group: $groupSsrcs.")
class SourceDoesNotExistException(ssrc: Long? = null) : ValidationFailedException(
    "Source does not exist or is not owned by endpoint ${ssrc?.let { "(ssrc=$it)" }}."
)
class SourceGroupDoesNotExistException : ValidationFailedException("Source group does not exist")
class SourceNameNotOwnedException(name: String, owner: String) : ValidationFailedException(
    "Invalid source name for endpoint $owner: '$name' (expected $owner-a<index> or $owner-v<index>)."
)
class SourceNameConflictException(name: String, owner: String) : ValidationFailedException(
    "Source name is already used: $name (by $owner)."
)
