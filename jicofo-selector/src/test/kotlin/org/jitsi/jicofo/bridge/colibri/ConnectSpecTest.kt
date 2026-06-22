/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc.
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI

class ConnectSpecTest : ShouldSpec() {
    init {
        val spec = ConnectSpec(
            id = "c1",
            url = URI("wss://example.com/t"),
            type = Connect.Types.TRANSLATOR,
            exports = listOf("a-a0", "b-a0"),
            requests = listOf("a-a0.es", "b-a0.fr"),
            httpHeaders = mapOf("Authorization" to "Bearer x"),
            ping = ConnectSpec.Ping(1000, 2000)
        )

        context("toConnect") {
            should("carry all fields and default to no create/expire") {
                val connect = spec.toConnect()
                connect.id shouldBe "c1"
                connect.url shouldBe URI("wss://example.com/t")
                connect.type shouldBe Connect.Types.TRANSLATOR
                connect.audio shouldBe true
                connect.create shouldBe false
                connect.expire shouldBe false
                connect.getExports() shouldBe listOf("a-a0", "b-a0")
                connect.getRequests() shouldBe listOf("a-a0.es", "b-a0.fr")
                connect.getHttpHeaders().associate { it.name to it.value } shouldBe mapOf("Authorization" to "Bearer x")
                connect.getPing()!!.let {
                    it.interval shouldBe 1000
                    it.timeout shouldBe 2000
                }
            }
            should("set the create marker") {
                spec.toConnect(create = true).create shouldBe true
            }
            should("set the expire marker") {
                spec.toConnect(expire = true).expire shouldBe true
            }
        }

        context("sameAs") {
            should("be true for an identical spec") {
                spec.sameAs(spec.copy()) shouldBe true
            }
            should("ignore source-name ordering") {
                val reordered = spec.copy(exports = listOf("b-a0", "a-a0"), requests = listOf("b-a0.fr", "a-a0.es"))
                spec.sameAs(reordered) shouldBe true
            }
            should("differ when the exported source set changes") {
                spec.sameAs(spec.copy(exports = listOf("a-a0"))) shouldBe false
            }
            should("differ when the requested source set changes") {
                spec.sameAs(spec.copy(requests = listOf("a-a0.es"))) shouldBe false
            }
            should("differ when the url changes") {
                spec.sameAs(spec.copy(url = URI("wss://example.com/other"))) shouldBe false
            }
            should("differ when the headers change") {
                spec.sameAs(spec.copy(httpHeaders = emptyMap())) shouldBe false
            }
            should("differ when the ping changes") {
                spec.sameAs(spec.copy(ping = ConnectSpec.Ping(1, 2))) shouldBe false
            }
        }
    }
}
