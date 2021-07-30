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

import org.jitsi.jicofo.ConferenceConfig
import org.jitsi.jicofo.InvalidSSRCsException
import org.jitsi.jicofo.SSRCValidator
import org.jitsi.protocol.xmpp.util.MediaSourceGroupMap
import org.jitsi.protocol.xmpp.util.MediaSourceMap
import org.jitsi.utils.logging2.createLogger
import org.jxmpp.jid.Jid

class ValidatingConferenceSourceMap : ConferenceSourceMap() {
    val logger = createLogger()

    @Throws(ValidationFailedException::class)
    fun tryToAdd(owner: Jid?, sourcesToAdd: ConferenceSourceMap): ConferenceSourceMap {

        val (allSources, allGroups) = this.toMediaSourceMap()
        val validator = SSRCValidator(
            owner?.resourceOrEmpty.toString(), // This is used for logging only...
            allSources,
            allGroups,
            ConferenceConfig.config.maxSsrcsPerUser,
            this.logger
        )

        val (sourcesToRemove2, groupsToRemove) = sourcesToAdd.toMediaSourceMap()
        try {
            val added = validator.tryAddSourcesAndGroups(sourcesToRemove2, groupsToRemove)
            val addedConferenceSourceMap = fromMediaSourceMap(
                added[0] as MediaSourceMap,
                added[1] as MediaSourceGroupMap
            )
            add(addedConferenceSourceMap)
            return addedConferenceSourceMap
        } catch (e: InvalidSSRCsException) {
            throw ValidationFailedException(e.message)
        }
    }

    @Throws(ValidationFailedException::class)
    fun tryToRemove(owner: Jid?, sourcesToRemove: ConferenceSourceMap): ConferenceSourceMap {

        val (allSources, allGroups) = this.toMediaSourceMap()
        val validator = SSRCValidator(
            owner?.resourceOrEmpty.toString(), // This is used for logging only...
            allSources,
            allGroups,
            ConferenceConfig.config.maxSsrcsPerUser,
            this.logger
        )

        val (sourcesToRemove2, groupsToRemove) = sourcesToRemove.toMediaSourceMap()
        try {
            val removed = validator.tryRemoveSourcesAndGroups(sourcesToRemove2, groupsToRemove)
            val removedConferenceSourceMap = fromMediaSourceMap(
                removed[0] as MediaSourceMap,
                removed[1] as MediaSourceGroupMap
            )
            remove(removedConferenceSourceMap)
            return removedConferenceSourceMap
        } catch (e: InvalidSSRCsException) {
            throw ValidationFailedException(e.message)
        }
    }
}

class ValidationFailedException(message: String?) : Exception(message)
