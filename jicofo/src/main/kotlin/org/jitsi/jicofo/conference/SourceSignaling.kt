/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022-Present 8x8, Inc.
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
package org.jitsi.jicofo.conference

import org.jitsi.jicofo.conference.AddOrRemove.Add
import org.jitsi.jicofo.conference.AddOrRemove.Remove
import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.conference.source.Source
import org.jitsi.jicofo.conference.source.VideoType
import org.jitsi.utils.MediaType
import org.json.simple.JSONArray
import org.json.simple.JSONObject

class SourceSignaling(
    audio: Boolean = true,
    video: Boolean = true,
    private val stripSimulcast: Boolean = true,
    /**
     * Whether the endpoint supports receiving multiple video streams. If it doesn't, we make sure to only signal the
     * screensharing (desktop) source when another endpoint has both camera and screensharing.
     *
     * We assume at most one desktop source and at most one camera source.
     */
    private val supportsReceivingMultipleStreams: Boolean = true
) {
    /** The set of media types supported by the endpoint. */
    private val supportedMediaTypes: Set<MediaType> = buildSet {
        if (audio) add(MediaType.AUDIO)
        if (video) add(MediaType.VIDEO)
    }

    /**
     * The pre-filtered set of sources that have been signaled to the endpoint.
     * The actual set of sources that have been signaled are the result of [filter] applied to [signaledSources].
     */
    private var signaledSources = ConferenceSourceMap()

    /**
     * The pre-filtered set of updated sources, i.e. [signaledSources] with any requested changes made via [addSources]
     * or [removeSources].
     */
    private var updatedSources = ConferenceSourceMap()

    /**
     * In the case when [supportsReceivingMultipleStreams] is false, stores any screensharing sources which are
     * signaled to be muted, so that they can be restored once unmuted.
     */
    private val mutedDesktopSources = ConferenceSourceMap()

    fun addSources(sourcesToAdd: ConferenceSourceMap) {
        sourcesToAdd.copy().entries.forEach { (owner, ess) ->
            if (mutedDesktopSources[owner] == NO_SOURCES) {
                // owner's desktop source was muted before the source details were signaled. Suppress and save the
                // desktop sources.
                val desktopSources = ess.getDesktopSources()
                if (!desktopSources.isEmpty()) {
                    mutedDesktopSources.remove(owner)
                    mutedDesktopSources.add(owner, desktopSources)
                    sourcesToAdd.remove(ConferenceSourceMap(owner, desktopSources))
                }
            }
        }
        updatedSources.add(sourcesToAdd)
    }

    fun removeSources(sourcesToRemove: ConferenceSourceMap) {
        updatedSources.remove(sourcesToRemove)
        mutedDesktopSources.remove(sourcesToRemove)
    }

    /**
     * Update [signaledSources] to [updatedSources]. Return the set of operations ([Add] or [Remove]) needed to be
     * signaled to the endpoint to accomplish the update.
     */
    fun update(): List<SourcesToAddOrRemove> {
        val ss = signaledSources.filter()
        val us = updatedSources.filter()
        val sourcesToAdd = us - ss
        val sourcesToRemove = ss - us
        reset(updatedSources)
        return buildList {
            if (sourcesToRemove.isNotEmpty())
                add(SourcesToAddOrRemove(Remove, sourcesToRemove))
            if (sourcesToAdd.isNotEmpty())
                add(SourcesToAddOrRemove(Add, sourcesToAdd))
        }
    }

    val debugState: JSONObject
        get() = JSONObject().apply {
            this["signaled_sources"] = signaledSources.toJson()
            this["sources"] = updatedSources.toJson()
            this["supported_media_types"] = JSONArray().apply { supportedMediaTypes.forEach { add(it.toString()) } }
        }

    fun reset(s: ConferenceSourceMap): ConferenceSourceMap {
        signaledSources = s.copy()
        updatedSources = signaledSources.copy()
        return s.filter()
    }

    /**
     * Filter out certain sources which should not be signaled to this endpoint. E.g. filter out video for endpoints
     * which don't support video.
     */
    private fun ConferenceSourceMap.filter(): ConferenceSourceMap = copy().apply {
        stripByMediaType(supportedMediaTypes)
        if (stripSimulcast) stripSimulcast()
        if (!supportsReceivingMultipleStreams) {
            filterMultiStream()
        }
    }

    /**
     * Notifies this instance that a remote participant (identified by [owner]) has muted or unmuted their screensharing
     * source.
     */
    fun remoteDesktopSourceIsMutedChanged(owner: String, muted: Boolean) {
        if (muted) {
            // so that we can fall back to the video source
            val allParticipantSources = updatedSources[owner] ?: EndpointSourceSet()
            val desktopSources = allParticipantSources.getDesktopSources()

            // The source was muted. If there was a screensharing source signaled (desktopSources is not empty)
            // we remove it from [updatedSources], so that we can signal a source-remove with the next update.
            updatedSources.remove(ConferenceSourceMap(owner, desktopSources))
            // If the source was not signaled yet, save NO_SOURCES in the map to remember that it is muted once it
            // is signaled.
            mutedDesktopSources.add(owner, if (desktopSources.isEmpty()) NO_SOURCES else desktopSources)
        } else {
            val unmutedDesktopSources = mutedDesktopSources[owner]

            // Remove it from the muted map so future calls to [addSources] are allowed to add it.
            mutedDesktopSources.remove(owner)
            // If there was a screensharing source previously signaled, and it is not the NO_SOURCES placeholder, add
            // it to [updatedSources] so that is signaled with the next update.
            if (unmutedDesktopSources != null && unmutedDesktopSources != NO_SOURCES) {
                updatedSources.add(owner, unmutedDesktopSources)
            }
        }
    }
}

/**
 * If an endpoint has a screensharing (desktop) source, filter out all other video sources.
 */
private fun ConferenceSourceMap.filterMultiStream() = map { ess ->
    val desktopSourceName = ess.sources.find { it.videoType == VideoType.Desktop }?.name
    if (desktopSourceName != null) {
        val remainingSources = ess.sources.filter {
            it.mediaType != MediaType.VIDEO || it.name == desktopSourceName
        }.toSet()
        val remainingSsrcs = remainingSources.map { it.ssrc }.toSet()
        val remainingGroups = ess.ssrcGroups.filter { it.ssrcs.any { it in remainingSsrcs } }.toSet()
        EndpointSourceSet(remainingSources, remainingGroups)
    } else {
        ess
    }
}

private fun EndpointSourceSet.getDesktopSources(): EndpointSourceSet {
    val desktopSourceName = sources.find { it.videoType == VideoType.Desktop }?.name
    return if (desktopSourceName != null) {
        val desktopSources = sources.filter { it.name == desktopSourceName }.toSet()
        val desktopSsrcs = desktopSources.map { it.ssrc }.toSet()
        val desktopGroups = ssrcGroups.filter { it.ssrcs.any { it in desktopSsrcs } }.toSet()
        EndpointSourceSet(desktopSources, desktopGroups)
    } else {
        EndpointSourceSet()
    }
}

private val NO_SOURCES = EndpointSourceSet(Source(987654321, MediaType.VIDEO))
