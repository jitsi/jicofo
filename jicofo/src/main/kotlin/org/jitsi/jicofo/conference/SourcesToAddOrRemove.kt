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
package org.jitsi.jicofo.conference

import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.utils.OrderedJsonObject

/** An action -- add or remove. */
enum class AddOrRemove {
    Add,
    Remove
}

/** Holds a [ConferenceSourceMap] together with an action specifying if the sources are to be added or removed. */
data class SourcesToAddOrRemove(
    val action: AddOrRemove,
    val sources: ConferenceSourceMap
) {
    val debugState: OrderedJsonObject
        get() = OrderedJsonObject().apply {
            put("action", action.toString())
            put("sources", sources.toJson())
        }
}
