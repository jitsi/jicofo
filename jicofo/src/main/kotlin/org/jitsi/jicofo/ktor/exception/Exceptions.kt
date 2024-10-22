/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc
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
package org.jitsi.jicofo.ktor.exception

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

sealed class JicofoKtorException(message: String?) : RuntimeException(message)
open class BadRequest(message: String? = null) : JicofoKtorException("Bad request: ${message ?: ""}")
class NotFound(message: String?) : JicofoKtorException("Not found: ${message ?: ""}")
class Forbidden(message: String? = null) : JicofoKtorException("Forbidden: ${message ?: ""}")
class InternalError(message: String? = null) : JicofoKtorException("Internal error: ${message ?: ""}")

@SuppressFBWarnings(value = ["NP_METHOD_PARAMETER_TIGHTENS_ANNOTATION"], justification = "False positive")
class MissingParameter(parameter: String) : BadRequest("Missing parameter: $parameter")

object ExceptionHandler {
    suspend fun handle(call: ApplicationCall, cause: Throwable) {
        when (cause) {
            is BadRequest -> call.respond(HttpStatusCode.BadRequest, cause.message)
            is Forbidden -> call.respond(HttpStatusCode.Forbidden)
            is InternalError -> call.respond(HttpStatusCode.InternalServerError, cause.message)
            else -> call.respond(HttpStatusCode.InternalServerError, cause.message)
        }
    }

    private suspend fun ApplicationCall.respond(status: HttpStatusCode, message: String? = null) {
        respondText(ContentType.Text.Plain, status) { message ?: "Error" }
    }
}
