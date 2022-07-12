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
import org.json.simple.JSONObject

class SourceSignaling {
    private var signaledSources = ConferenceSourceMap()
    private var updatedSources = signaledSources.copy()

    fun addSources(sourcesToAdd: ConferenceSourceMap) = updatedSources.add(sourcesToAdd)
    fun removeSources(sourcesToRemove: ConferenceSourceMap) = updatedSources.remove(sourcesToRemove)

    fun update(): List<SourcesToAddOrRemove> {
        val sourcesToAdd = updatedSources - signaledSources
        val sourcesToRemove = signaledSources - updatedSources
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
        }

    fun reset(s: ConferenceSourceMap) {
        signaledSources = s.copy()
        updatedSources = signaledSources.copy()
    }
}
