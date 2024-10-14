/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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
package org.jitsi.jicofo

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseHeaderValue
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jitsi.health.HealthCheckService
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.rest.RestConfig
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.utils.logging2.createLogger

class JicofoKtor(
    val healthChecker: HealthCheckService?
) {
    val logger = createLogger()

    private fun Route.about() {
        data class VersionInfo(
            val name: String? = null,
            val version: String? = null,
            val os: String? = null
        )

        val versionInfo = VersionInfo(
            CurrentVersionImpl.VERSION.applicationName,
            CurrentVersionImpl.VERSION.toString(),
            System.getProperty("os.name")
        )

        route("/about") {
            get("version") {
                call.respond(versionInfo)
            }
            healthChecker?.let {
                get("health") {
                    healthChecker.result.let {
                        if (it.success) {
                            call.respond("OK")
                        } else {
                            val status = it.responseCode ?: if (it.hardFailure) 500 else 503
                            call.respondText(ContentType.Text.Plain, HttpStatusCode.fromValue(status)) {
                                it.message ?: "Unknown error."
                            }
                        }
                    }
                }
            }
        }
    }

    fun start() {
        logger.info("Starting ktor on port 9999")
        embeddedServer(Netty, port = 9999, host = "0.0.0.0") {
            install(ContentNegotiation) {
                jackson {}
            }

            routing {
                if (RestConfig.config.enablePrometheus) {
                    get("/metrics") {
                        val accepts =
                            parseHeaderValue(call.request.headers["Accept"]).sortedBy { it.quality }.map { it.value }
                        val (metrics, contentType) = JicofoMetricsContainer.instance.getMetrics(accepts)
                        call.respondText(metrics, contentType = ContentType.parse(contentType))
                    }
                }
                about()
            }
        }.start(wait = false)
    }

    fun stop() {

    }
}
