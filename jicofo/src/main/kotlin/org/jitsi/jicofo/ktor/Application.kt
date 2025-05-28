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
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingRequest
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jitsi.health.HealthCheckService
import org.jitsi.jicofo.ConferenceRequest
import org.jitsi.jicofo.ConferenceStore
import org.jitsi.jicofo.bridgeload.BridgeNotFoundException
import org.jitsi.jicofo.bridgeload.ConferenceNotFoundException
import org.jitsi.jicofo.bridgeload.InvalidParameterException
import org.jitsi.jicofo.bridgeload.LoadRedistributor
import org.jitsi.jicofo.bridgeload.MissingParameterException
import org.jitsi.jicofo.bridgeload.MoveFailedException
import org.jitsi.jicofo.bridgeload.MoveResult
import org.jitsi.jicofo.ktor.exception.BadRequest
import org.jitsi.jicofo.ktor.exception.ExceptionHandler
import org.jitsi.jicofo.ktor.exception.Forbidden
import org.jitsi.jicofo.ktor.exception.MissingParameter
import org.jitsi.jicofo.ktor.exception.NotFound
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.jicofo.xmpp.XmppCapsStats
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException
import java.time.Duration
import org.jitsi.jicofo.ktor.RestConfig.Companion.config as config

class Application(
    private val healthChecker: HealthCheckService?,
    private val conferenceIqHandler: ConferenceIqHandler,
    private val conferenceStore: ConferenceStore,
    private val loadRedistributor: LoadRedistributor,
    private val getStatsJson: () -> OrderedJsonObject,
    private val getDebugState: (full: Boolean, confId: EntityBareJid?) -> OrderedJsonObject
) {
    private val logger = createLogger()
    private val server = start()

    private fun start(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        logger.info("Starting ktor on port ${config.port}, host ${config.host}")
        return embeddedServer(Netty, port = config.port, host = config.host) {
            install(ContentNegotiation) {
                jackson {}
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    ExceptionHandler.handle(call, cause)
                }
            }

            routing {
                about()
                conferenceRequest()
                debug()
                metrics()
                moveEndpoints()
                pin()
                rtcstats()
                stats()
            }
        }.start(wait = false)
    }

    fun stop() = server.stop()

    private fun Route.metrics() {
        if (config.enablePrometheus) {
            get("/metrics") {
                val accepts =
                    parseHeaderValue(call.request.headers["Accept"]).sortedByDescending { it.quality }.map { it.value }
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
        if (config.enableConferenceRequest) {
            options("/conference-request/v1") {
                call.respond(HttpStatusCode.OK)
            }
            post("/conference-request/v1") {
                val request = try {
                    call.receive<ConferenceRequest>()
                } catch (e: Exception) {
                    throw BadRequest(e.message)
                }

                val token = call.request.getToken()

                val response: IQ = try {
                    conferenceIqHandler.handleConferenceIq(request.toConferenceIq(token))
                } catch (e: XmppStringprepException) {
                    throw BadRequest("Invalid room name: ${e.message}")
                } catch (e: Exception) {
                    logger.error(e.message, e)
                    throw BadRequest(e.message)
                }

                response.error?.let {
                    throw when (it.condition) {
                        StanzaError.Condition.not_authorized -> Forbidden()
                        StanzaError.Condition.not_acceptable -> BadRequest("invalid-session")
                        else -> BadRequest(it.toString())
                    }
                }
                if (response !is ConferenceIq) {
                    throw InternalError()
                }
                call.respond(ConferenceRequest.fromConferenceIq(response))
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

            call.respondJson(rtcstats)
        }
    }

    private fun Route.moveEndpoints() {
        if (config.enableMoveEndpoints) {
            route("/move-endpoints") {
                get("move-endpoint") {
                    call.respond(
                        translateException {
                            loadRedistributor.moveEndpoint(
                                call.request.queryParameters["conference"],
                                call.request.queryParameters["endpoint"],
                                call.request.queryParameters["bridge"],
                            )
                        }
                    )
                }
                get("move-endpoints") {
                    call.respond(
                        translateException {
                            loadRedistributor.moveEndpoints(
                                call.request.queryParameters["bridge"],
                                call.request.queryParameters["conference"],
                                call.request.queryParameters["numEndpoints"]?.toInt() ?: 1
                            )
                        }
                    )
                }
                get("move-fraction") {
                    call.respond(
                        translateException {
                            loadRedistributor.moveFraction(
                                call.request.queryParameters["bridge"],
                                call.request.queryParameters["fraction"]?.toDouble() ?: 0.1
                            )
                        }
                    )
                }
            }
        }
    }

    private fun Route.debug() {
        if (config.enableDebug) {
            route("/debug") {
                get("") {
                    call.respondJson(
                        getDebugState(call.request.queryParameters["full"] == "true", null)
                    )
                }
                get("conferences") {
                    val conferencesJson = JSONArray().apply {
                        conferenceStore.getAllConferences().forEach {
                            add(it.roomName.toString())
                        }
                    }
                    call.respondJson(conferencesJson)
                }
                get("conferences-full") {
                    val conferencesJson = JSONObject().apply {
                        conferenceStore.getAllConferences().forEach {
                            put(it.roomName.toString(), it.debugState)
                        }
                    }
                    call.respondJson(conferencesJson)
                }
                get("/conference/{conference}") {
                    val conference = call.parameters["conference"] ?: throw MissingParameter("conference")
                    val conferenceJid = try {
                        JidCreate.entityBareFrom(conference)
                    } catch (e: Exception) {
                        throw BadRequest("Invalid conference ID")
                    }
                    call.respondJson(
                        getDebugState(true, conferenceJid)
                    )
                }
                get("xmpp-caps") {
                    call.respondJson(XmppCapsStats.stats)
                }
            }
        }
    }

    private fun Route.pin() {
        data class PinJson(val conferenceId: String, val jvbVersion: String, val durationMinutes: Int)
        data class UnpinJson(val conferenceId: String)

        if (config.pinEnabled) {
            route("/pin") {
                get("") {
                    call.respond(conferenceStore.getPinnedConferences())
                }
                post("") {
                    val pin = try {
                        call.receive<PinJson>()
                    } catch (e: Exception) {
                        throw BadRequest(e.message)
                    }
                    val conferenceJid = try {
                        JidCreate.entityBareFrom(pin.conferenceId)
                    } catch (e: Exception) {
                        throw BadRequest("Invalid conference ID")
                    }

                    conferenceStore.pinConference(
                        conferenceJid,
                        pin.jvbVersion,
                        Duration.ofMinutes(pin.durationMinutes.toLong())
                    )
                    call.respond(HttpStatusCode.OK)
                }
                post("/remove") {
                    val unpin = call.receive<UnpinJson>()
                    val conferenceJid = try {
                        JidCreate.entityBareFrom(unpin.conferenceId)
                    } catch (e: Exception) {
                        throw BadRequest("Invalid conference ID")
                    }

                    conferenceStore.unpinConference(conferenceJid)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    private fun Route.stats() {
        get("/stats") {
            call.respondJson(getStatsJson())
        }
    }
}

private suspend fun RoutingCall.respondJson(json: JSONArray) {
    respondText(ContentType.Application.Json, HttpStatusCode.OK) { json.toJSONString() }
}
private suspend fun RoutingCall.respondJson(json: JSONObject) {
    respondText(ContentType.Application.Json, HttpStatusCode.OK) { json.toJSONString() }
}
private suspend fun RoutingCall.respondJson(json: OrderedJsonObject) {
    respondText(ContentType.Application.Json, HttpStatusCode.OK) { json.toJSONString() }
}

private fun translateException(block: () -> MoveResult): MoveResult {
    return try {
        block()
    } catch (e: MoveFailedException) {
        throw when (e) {
            is BridgeNotFoundException -> NotFound("Bridge not found")
            is ConferenceNotFoundException -> NotFound("Conference not found")
            is MissingParameterException, is InvalidParameterException -> BadRequest(e.message)
        }
    }
}

private fun RoutingRequest.getToken(): String? {
    return this.headers["Authorization"]?.let {
        if (it.startsWith("Bearer ")) {
            it.substring("Bearer ".length)
        } else {
            it
        }
    }
}
