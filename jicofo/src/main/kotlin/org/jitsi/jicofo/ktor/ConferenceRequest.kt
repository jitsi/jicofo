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
package org.jitsi.jicofo.ktor

import org.jitsi.jicofo.ConferenceRequest
import org.jitsi.jicofo.ktor.exception.BadRequest
import org.jitsi.jicofo.ktor.exception.Forbidden
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
            throw BadRequest("Invalid room name: ${e.message}")
        } catch (e: Exception) {
            logger.error(e.message, e)
            throw BadRequest(e.message)
        }

        if (response !is ConferenceIq) {
            if (response is ErrorIQ) {
                throw when (response.error.condition) {
                    StanzaError.Condition.not_authorized -> Forbidden()
                    StanzaError.Condition.not_acceptable -> BadRequest("invalid-session")
                    else -> BadRequest(response.error.toString())
                }
            } else {
                throw InternalError()
            }
        }

        return ConferenceRequest.fromConferenceIq(response)
    }
}

