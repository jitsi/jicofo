/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.jicofo.rest

import org.jitsi.jicofo.ConferenceRequest
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.stringprep.XmppStringprepException

class ConferenceRequestHandler(private val conferenceIqHandler: ConferenceIqHandler) {
    private val logger = createLogger()

    fun handleRequest(conferenceRequest: ConferenceRequest): ConferenceRequest {
        val response: IQ
        try {
            response = conferenceIqHandler.handleConferenceIq(conferenceRequest.toConferenceIq())
        } catch (e: XmppStringprepException) {
            throw BadRequestExceptionWithMessage("Invalid room name: ${e.message}")
        } catch (e: Exception) {
            logger.error(e.message, e)
            throw BadRequestExceptionWithMessage(e.message)
        }

        if (response !is ConferenceIq) {
            if (response is ErrorIQ) {
                throw when (response.error.condition) {
                    StanzaError.Condition.not_authorized -> {
                        Forbidden()
                    }
                    StanzaError.Condition.not_acceptable -> {
                        BadRequestExceptionWithMessage("invalid-session")
                    }
                    else -> BadRequestExceptionWithMessage(response.error.toString())
                }
            } else {
                throw InternalServerErrorException()
            }
        }

        return ConferenceRequest.fromConferenceIq(response)
    }
}

class BadRequestExceptionWithMessage(message: String?) : RuntimeException(message)
class Forbidden : RuntimeException("Forbidden")
class InternalServerErrorException : RuntimeException()
