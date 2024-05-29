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
import org.jitsi.utils.MediaType
import org.json.simple.JSONArray
import org.json.simple.JSONObject

class SourceSignaling(
    audio: Boolean = true,
    video: Boolean = true,
    private val stripSimulcast: Boolean = true
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

    fun addSources(sourcesToAdd: ConferenceSourceMap) {
        updatedSources.add(sourcesToAdd)
    }

    fun removeSources(sourcesToRemove: ConferenceSourceMap) {
        updatedSources.remove(sourcesToRemove)
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
            if (sourcesToRemove.isNotEmpty()) {
                add(SourcesToAddOrRemove(Remove, sourcesToRemove))
            }
            if (sourcesToAdd.isNotEmpty()) {
                add(SourcesToAddOrRemove(Add, sourcesToAdd))
            }
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
    }
}
