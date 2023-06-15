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

import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.createLogger
import java.lang.IllegalStateException

/**
 * An extension of [ConferenceSourceMap] with an API for addition ([tryToAdd]) and removal ([tryToRemove]) of sources
 * with validation. Note that the standard [add] and [remove] APIs are still available and do NOT perform validation.
 *
 * Validation means that the addition of sources does not lead to a conflict of SSRC or MSID with another endpoint
 * in the conference, and that the addition/removal operations leave the endpoint's resulting set of sources valid.
 *
 * A set of sources is valid if there are no conflicts in SSRC or MSID, all sources within a group have the same MSID,
 * all SSRCs within a group have a corresponding source, and other similar checks pass. See [validateEndpointSourceSet].
 *
 * This implementation inherits the thread safety characteristics of [ConferenceSourceMap].
 */
class ValidatingConferenceSourceMap(
    private val maxSsrcsPerUser: Int,
    private val maxSsrcGroupsPerUser: Int
) : ConferenceSourceMap() {
    val logger = createLogger()

    /**
     * Maps an SSRC to the JID of the endpoint that owns it. Used to detect cross-endpoint conflicts efficiently.
     *
     * Note that since the value is nullable [Map.containsKey] must be used when checking for the existence of an entry.
     */
    private val ssrcToOwnerMap = mutableMapOf<Long, String>()

    /**
     * Maps an MSID to the JID of the endpoint that owns it. Used to detect cross-endpoint conflicts efficiently.
     *
     * Note that since the value is nullable [Map.containsKey] must be used when checking for the existence of an entry.
     */
    private val msidToOwnerMap = mutableMapOf<String, String>()

    /**
     * Attempts to add [sourcesToAdd] as sources owned by [owner]. The attempt is successful if the addition does not
     * introduce any conflicts in SSRC/MSID and the resulting set of sources for [owner] is valid (see
     * [validateEndpointSourceSet]).
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

        // Check for validity of the new SSRCs, and conflicts with other endpoints.
        sourcesToAdd.sources.forEach { source ->
            if (source.ssrc <= 0 || source.ssrc >= 0x1_0000_0000) {
                throw InvalidSsrcException(source.ssrc)
            }
            if (ssrcToOwnerMap.containsKey(source.ssrc)) {
                // Adding the same source for the same endpoint is also invalid.
                throw SsrcAlreadyUsedException(source.ssrc)
            }
            source.msid?.let { msid ->
                if (msidToOwnerMap.containsKey(msid) && msidToOwnerMap[msid] != owner) {
                    throw MsidConflictException(msid)
                }
            }
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

    /** Override [add] to keep the additional [ssrcToOwnerMap] and [msidToOwnerMap] maps updated. */
    override fun add(other: ConferenceSourceMap) = synchronized(syncRoot) {
        super.add(other).also {
            other.forEach { (owner, endpointSourceSet) -> sourceSetAdded(owner, endpointSourceSet) }
        }
    }

    /** Override [add] to keep the additional [ssrcToOwnerMap] and [msidToOwnerMap] maps updated. */
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
        }
    }

    /** Override [remove] to keep the additional [ssrcToOwnerMap] and [msidToOwnerMap] maps updated. */
    override fun remove(other: ConferenceSourceMap) = synchronized(syncRoot) {
        super.remove(other).also {
            other.forEach { (owner, ownerRemovedSourceSet) -> sourceSetRemoved(owner, ownerRemovedSourceSet) }
        }
    }

    /** Override [remove] to keep the additional [ssrcToOwnerMap] and [msidToOwnerMap] maps updated. */
    override fun remove(owner: String): EndpointSourceSet? = synchronized(syncRoot) {
        val ownerRemovedSourceSet = super.remove(owner)
        ownerRemovedSourceSet?.let {
            sourceSetRemoved(owner, it)
        }
        return ownerRemovedSourceSet
    }

    /**
     * Update the local maps ([ssrcToOwnerMap] and [msidToOwnerMap] after the removal of a source set.
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
        }
    }

    companion object {

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
class SsrcAlreadyUsedException(ssrc: Long) : ValidationFailedException("SSRC is already used: $ssrc.")
class RequiredParameterMissingException(name: String) : ValidationFailedException(
    "Required source parameter '$name' is not present."
)
class GroupMsidMismatchException(ssrcs: List<Long>) : ValidationFailedException(
    "SsrcGroup contains sources with different MSIDs: $ssrcs."
)
class MsidConflictException(msid: String) : ValidationFailedException("MSID is already used: $msid.")
class GroupContainsUnknownSourceException(groupSsrcs: List<Long>) : ValidationFailedException(
    "An SSRC group contains an SSRC, which hasn't been signaled as a source: $groupSsrcs."
)
class InvalidFidGroupException(groupSsrcs: List<Long>) : ValidationFailedException("Invalid FID group: $groupSsrcs.")
class SourceDoesNotExistException(ssrc: Long? = null) : ValidationFailedException(
    "Source does not exist or is not owned by endpoint ${ssrc?.let { "(ssrc=$it)" }}."
)
class SourceGroupDoesNotExistException : ValidationFailedException("Source group does not exist")
