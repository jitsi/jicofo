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
    private var updatedSources = signaledSources.copy()

    fun addSources(sourcesToAdd: ConferenceSourceMap) = updatedSources.add(sourcesToAdd)
    fun removeSources(sourcesToRemove: ConferenceSourceMap) = updatedSources.remove(sourcesToRemove)

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
            if (sourcesToAdd.isNotEmpty())
                add(SourcesToAddOrRemove(Add, sourcesToAdd))
            if (sourcesToRemove.isNotEmpty())
                add(SourcesToAddOrRemove(Remove, sourcesToRemove))
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
}

/**
 * If an endpoint has a screensharing (desktop) source, filter out all other video sources.
 */
private fun ConferenceSourceMap.filterMultiStream() = map { ess ->
    val desktopSourceName = ess.sources.find { it.videoType == VideoType.Desktop }?.name
    if (desktopSourceName != null) {
        val sources = ess.sources.filter { it.mediaType != MediaType.VIDEO || it.name == desktopSourceName }.toSet()
        val ssrcs = sources.map { it.ssrc }.toSet()
        val ssrcGroups = ess.ssrcGroups.filter { (it.ssrcs - ssrcs).isNotEmpty() }.toSet()
        EndpointSourceSet(sources, ssrcGroups)
    } else {
        ess
    }
}
