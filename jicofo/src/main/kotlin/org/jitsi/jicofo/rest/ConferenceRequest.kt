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

import jakarta.servlet.http.HttpServletResponse
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.stringprep.XmppStringprepException

@Path("/conference-request/v1")
class ConferenceRequest(
    val conferenceIqHandler: ConferenceIqHandler
) {
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun conferenceRequest(conferenceRequest: org.jitsi.jicofo.ConferenceRequest): String {

        val response: IQ
        try {
            response = conferenceIqHandler.handleConferenceIq(conferenceRequest.toConferenceIq())
        } catch (e: XmppStringprepException) {
            throw BadRequestExceptionWithMessage("Invalid room name: ${e.message}")
        } catch (e: Exception) {
            throw BadRequestExceptionWithMessage(e.message)
        }

        if (response !is ConferenceIq) {
            if (response is ErrorIQ) {
                throw when (response.error.condition) {
                    StanzaError.Condition.not_authorized -> {
                        System.err.println("Not authorised")
                        ForbiddenException()
                    }
                    StanzaError.Condition.not_acceptable -> {
                        System.err.println("not_acceptable")
                        BadRequestExceptionWithMessage("invalid-session")
                    }
                    else -> BadRequestExceptionWithMessage(response.error.toString())
                }
            } else {
                throw InternalServerErrorException()
            }
        }

        return org.jitsi.jicofo.ConferenceRequest.fromConferenceIq(response).toJson()
    }
}

/**
 * The ctor for {@link BadRequestException} which takes in a String doesn't
 * actually include that String in the response.  A much longer syntax (seen
 * in the constructor below) is necessary.  This class exists in order to expose
 * that behavior in a more concise way
 */
private class BadRequestExceptionWithMessage(message: String?) : BadRequestException(
    Response.status(HttpServletResponse.SC_BAD_REQUEST, message).build()
)
