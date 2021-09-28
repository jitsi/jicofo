/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2019-Present 8x8 Inc
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
package org.jitsi.jicofo.conference.colibri

/** An exception in Colibri channel allocation. */
open class ColibriException(message: String) : Exception(message) {
    open fun clone(prefix: String): ColibriException = ColibriException(prefix + message)
}

/**
 * A [ColibriException] indicating the remote Colibri endpoint responded to our request with with a bad-request error.
 */
class BadRequestException(message: String) : ColibriException(message) {
    override fun clone(prefix: String) = BadRequestException(prefix + message)
}

/**
 * A [ColibriException] indicating that the remote Colibri endpoint responded to our request with a
 * "conference not found" error.
 */
class ConferenceNotFoundException(message: String) : ColibriException(message) {
    override fun clone(prefix: String) = ConferenceNotFoundException(prefix + message)
}

/**
 * A [ColibriException] indicating we reached a timeout waiting for a response to our request.
 */
class TimeoutException(message: String = "Timed out waiting for a response.") : ColibriException(message) {
    override fun clone(prefix: String) = TimeoutException(prefix + message)
}

/**
 * An exception indicating the remote Colibri endpoint responded to our
 * request with a response of the wrong type (not a ColibriConferenceIQ).
 */
class WrongResponseTypeException(message: String) : ColibriException(message) {
    override fun clone(prefix: String) = WrongResponseTypeException(prefix + message)
}
