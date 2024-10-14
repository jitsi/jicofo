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
import io.ktor.http.parseHeaderValue
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jitsi.jicofo.metrics.JicofoMetricsContainer
import org.jitsi.jicofo.rest.RestConfig
import org.jitsi.jicofo.version.CurrentVersionImpl
import org.jitsi.utils.logging2.createLogger

class JicofoKtor {
    val logger = createLogger()

    fun start() {
        logger.info("Starting ktor on port 9999")
        embeddedServer(Netty, port = 9999, host = "0.0.0.0", module = Application::module).start(wait = false)
    }
    fun stop() {

    }
}

fun Application.module() {
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
        route("/about") {
            get("version") {
                call.respond(versionInfo)
            }
        }
    }
}

data class VersionInfo(
    val name: String? = null,
    val version: String? = null,
    val os: String? = null
)
val versionInfo: VersionInfo = VersionInfo(
    CurrentVersionImpl.VERSION.applicationName,
    CurrentVersionImpl.VERSION.toString(),
    System.getProperty("os.name")
)
