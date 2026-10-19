/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi.jicofo.bridge.colibri

/** Bridge selection failed, i.e. there were no bridges available. */
class BridgeSelectionFailedException : Exception("Bridge selection failed")

open class ColibriAllocationFailedException(
    message: String = "",
    val removeBridge: Boolean = false
) : Exception(message)

/**
 * Jicofo's view of a colibri2 session and the bridge's disagree (e.g. the conference exists on the bridge when we
 * think we are creating it, or no longer exists when we think it does). The bridge itself is healthy. The session is
 * removed and its participants re-invited, so this is recoverable and not an error.
 */
open class ColibriStateOutOfSyncException(message: String) : ColibriAllocationFailedException(message, true)

class ConferenceAlreadyExistsException(message: String) : ColibriStateOutOfSyncException(message)

/**
 * The bridge responded with conference_not_found: the colibri2 conference does not exist on the bridge any more
 * (e.g. the bridge expired it when its last endpoint was removed).
 */
class ColibriConferenceNotFoundException(message: String) : ColibriStateOutOfSyncException(message)

/**
 * The session or participant a request was for had already been removed when its response arrived (e.g. the bridge
 * was removed meanwhile). Nothing is wrong with the bridge, so [removeBridge] is false.
 */
class ColibriSessionGoneException(message: String) : ColibriAllocationFailedException(message, removeBridge = false)
