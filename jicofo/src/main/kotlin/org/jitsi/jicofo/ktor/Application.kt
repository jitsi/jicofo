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
package org.jitsi.jicofo.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseHeaderValue
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jitsi.health.HealthCheckService
import org.jitsi.jicofo.ConferenceRequest
import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.ktor.exception.ExceptionHandler
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.rest.RestConfig
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.utils.logging2.createLogger
import org.json.simple.JSONObject

class Application(
    private val healthChecker: HealthCheckService?,
    conferenceIqHandler: ConferenceIqHandler,
    private val conferenceStore: ConferenceStore,
    bridgeSelector: BridgeSelector
) {
    private val logger = createLogger()
    private val server = start()
    private val conferenceRequestHandler = ConferenceRequestHandler(conferenceIqHandler)
    private val moveEndpointsHandler = MoveEndpoints(conferenceStore, bridgeSelector)

    private fun start(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        logger.info("Starting ktor on port 9999")
        return embeddedServer(Netty, port = 9999, host = "0.0.0.0") {
            install(ContentNegotiation) {
                jackson {}
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    ExceptionHandler.handle(call, cause)
                }
            }

            routing {
                metrics()
                about()
                conferenceRequest()
                moveEndpoints()
                rtcstats()
            }
        }.start(wait = false)
    }

    fun stop() = server.stop()

    private fun Route.metrics() {
        if (RestConfig.config.enablePrometheus) {
            get("/metrics") {
                val accepts =
                    parseHeaderValue(call.request.headers["Accept"]).sortedBy { it.quality }.map { it.value }
                val (metrics, contentType) = JicofoMetricsContainer.instance.getMetrics(accepts)
                call.respondText(metrics, contentType = ContentType.parse(contentType))
            }
        }
    }

    private fun Route.about() {
        data class VersionInfo(val name: String? = null, val version: String? = null, val os: String? = null)
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

    private fun Route.conferenceRequest() {
        if (RestConfig.config.enableConferenceRequest) {
            post("/conference-request/v1") {
                val conferenceRequest = call.receive<ConferenceRequest>()
                val response = conferenceRequestHandler.handleRequest(conferenceRequest)
                call.respond(response)
            }
        }
    }

    private fun Route.rtcstats() {
        get("/rtcstats") {
            val rtcstats = JSONObject()
            conferenceStore.getAllConferences().forEach { conference ->
                if (conference.includeInStatistics() && conference.isRtcStatsEnabled) {
                    conference.meetingId?.let { meetingId ->
                        rtcstats.put(meetingId, conference.rtcstatsState)
                    }
                }
            }

            call.respondText(ContentType.Application.Json, HttpStatusCode.OK) {rtcstats.toJSONString() }
        }
    }

    private fun Route.moveEndpoints() {
        if (RestConfig.config.enableMoveEndpoints) {
            route("/move-endpoints") {
                get("move-endpoint") {
                    call.respond(
                        moveEndpointsHandler.moveEndpoint(
                            call.request.queryParameters["conference"],
                            call.request.queryParameters["endpoint"],
                            call.request.queryParameters["bridge"],
                        )
                    )
                }
                get("move-endpoints") {
                    call.respond(
                        moveEndpointsHandler.moveEndpoints(
                            call.request.queryParameters["bridge"],
                            call.request.queryParameters["conference"],
                            call.request.queryParameters["numEndpoints"]?.toInt() ?: 1
                        )
                    )
                }
                get("move-endpoints") {
                    call.respond(
                        moveEndpointsHandler.moveFraction(
                            call.request.queryParameters["bridge"],
                            call.request.queryParameters["fraction"]?.toDouble() ?: 0.1
                        )
                    )
                }
            }
        }
    }
}
