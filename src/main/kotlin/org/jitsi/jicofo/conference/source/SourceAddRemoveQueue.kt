/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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

import org.jitsi.jicofo.conference.AddOrRemove
import org.jitsi.jicofo.conference.SourcesToAddOrRemove

/**
 * A queue of [SourcesToAddOrRemove] which merges consecutive "add" or "remove" operations.
 *
 * Calling `sourceAdd(s1); sourceAdd(s2)` will result in a single entry in the queue with [AddOrRemove.Add] as the
 * action and the merge of s1 and s2 as the sources.
 */
class SourceAddRemoveQueue {
    private val queuedRemoteSourceChanges: MutableList<SourcesToAddOrRemove> = mutableListOf()

    /** Returns the list of [SourcesToAddOrRemove] from the queue. */
    fun get(): List<SourcesToAddOrRemove> = synchronized(queuedRemoteSourceChanges) {
        return queuedRemoteSourceChanges.toMutableList()
    }

    /** Clear the queue and return the list of [SourcesToAddOrRemove]. */
    fun clear(): List<SourcesToAddOrRemove> = synchronized(queuedRemoteSourceChanges) {
        return get().also {
            queuedRemoteSourceChanges.clear()
        }
    }

    /** Add a source-add operation to the queue. */
    fun sourceAdd(sourcesToAdd: ConferenceSourceMap) {
        var sourcesToAdd = sourcesToAdd
        synchronized(queuedRemoteSourceChanges) {
            val previous = queuedRemoteSourceChanges.lastOrNull()
            if (previous != null && previous.action === AddOrRemove.Add) {
                // We merge sourcesToAdd with the previous sources queued to be added to reduce the number of
                // source-add messages that need to be sent.
                queuedRemoteSourceChanges.removeLast()
                sourcesToAdd = sourcesToAdd.copy()
                sourcesToAdd.add(previous.sources)
            }
            queuedRemoteSourceChanges.add(SourcesToAddOrRemove(AddOrRemove.Add, sourcesToAdd))
        }
    }

    /** Add a source-remove operation to the queue. */
    fun sourceRemove(sourcesToRemove: ConferenceSourceMap) {
        var sourcesToRemove = sourcesToRemove
        synchronized(queuedRemoteSourceChanges) {
            val previous = queuedRemoteSourceChanges.lastOrNull()
            if (previous != null && previous.action === AddOrRemove.Remove) {
                // We merge sourcesToRemove with the previous sources queued to be removed to reduce the number of
                // source-remove messages that need to be sent.
                queuedRemoteSourceChanges.removeLast()
                sourcesToRemove = sourcesToRemove.copy()
                sourcesToRemove.add(previous.sources)
            }
            queuedRemoteSourceChanges.add(SourcesToAddOrRemove(AddOrRemove.Remove, sourcesToRemove))
        }
    }
}
