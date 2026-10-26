/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.bridge.Bridge
import org.jitsi.jicofo.bridge.BridgeSelector
import org.jitsi.jicofo.conference.source.EndpointSourceSet
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.TestColibri2Server
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jitsi.jicofo.mock.inPlaceScheduledExecutor
import org.jitsi.utils.TemplatedUrl
import org.jitsi.utils.logging2.createLogger
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.impl.JidCreate
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Tests that custom transcription HTTP headers and URL parameters are correctly included in Colibri2 requests,
 * covering both the case where transcription is configured before the first session is created (bug fix) and
 * after a session already exists.
 */
class ColibriTranscriptionTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val colibriRequests = mutableListOf<ConferenceModifyIQ>()
    private val colibri2Server = TestColibri2Server()
    private val xmppConnection = object : MockXmppConnection() {
        override fun handleIq(iq: IQ): IQ? {
            if (iq is ConferenceModifyIQ) {
                colibriRequests.add(iq)
                return colibri2Server.handleConferenceModifyIq(iq)
            }
            return null
        }
    }

    private val bridge: Bridge = mockk(relaxed = true) {
        every { jid } returns JidCreate.from("jvb@example.com/jvb1")
        every { relayId } returns null
        every { isOperational } returns true
        every { debugState } returns JsonNodeFactory.instance.objectNode()
        every { region } returns "us-east"
    }

    private val bridgeSelector: BridgeSelector = mockk {
        every { selectBridge(any(), any(), any()) } returns bridge
    }

    private fun createSessionManager() = ColibriV2SessionManager(
        xmppConnection.xmppConnection,
        bridgeSelector,
        "test-conference",
        "test-meeting-id",
        false,
        null,
        createLogger()
    )

    private fun allocateParticipant(manager: ColibriV2SessionManager, id: String = "p1", diarize: Boolean = false) =
        manager.allocate(
            ParticipantAllocationParameters(
                id = id,
                statsId = null,
                region = null,
                sources = EndpointSourceSet.EMPTY,
                useSsrcRewriting = false,
                useRtpMidDemux = false,
                forceMuteAudio = false,
                forceMuteVideo = false,
                useSctp = false,
                visitor = false,
                supportsPrivateAddresses = false,
                diarize = diarize,
                medias = emptySet()
            )
        )

    /** The errors that [ColibriV2SessionManager] logs. */
    private val errors = mutableListOf<String>()

    /** Keep a strong reference: java.util.logging holds its loggers only weakly. */
    private val managerJulLogger = java.util.logging.Logger.getLogger(ColibriV2SessionManager::class.java.name)
    private val errorHandler = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level == Level.SEVERE) errors.add(record.message)
        }
        override fun flush() {}
        override fun close() {}
    }

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
        TaskPools.scheduledPool = inPlaceScheduledExecutor
        // beforeAny runs for both the container and the leaf, so remove the handler first to not add it twice.
        managerJulLogger.removeHandler(errorHandler)
        managerJulLogger.addHandler(errorHandler)
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
        TaskPools.resetScheduledPool()
        managerJulLogger.removeHandler(errorHandler)
    }

    init {
        // bridge.region = "us-east", so the URL becomes wss://us-east.transcriber.example.com/rec
        val transcriberUrl = TemplatedUrl(
            "wss://{{REGION}}.transcriber.example.com/rec",
            requiredKeys = setOf("REGION")
        )
        val customHeaders = mapOf("X-Custom-Header" to "custom-value", "Authorization" to "Bearer custom")
        val urlParams = mapOf("key1" to "value1", "key2" to "hello world")
        val expectedBaseUrl = "wss://us-east.transcriber.example.com/rec"

        context("Transcription configured before session exists") {
            // This covers the bug fix: when setTranscriberUrl is called before any Colibri session exists,
            // the custom headers and URL params must be included in the initial create request
            // (via sendAllocationRequest → createRequest(create=true)).
            val manager = createSessionManager()
            manager.setTranscriberUrl(transcriberUrl, customHeaders, urlParams)
            allocateParticipant(manager)

            val createRequest = colibriRequests.find { it.create }
            createRequest shouldNotBe null
            val connect = createRequest!!.connects?.getConnects()?.firstOrNull()

            should("include a Connect in the create request") {
                connect shouldNotBe null
            }
            should("use custom HTTP headers") {
                val headers = connect!!.getHttpHeaders().associate { it.name to it.value }
                headers["X-Custom-Header"] shouldBe "custom-value"
                headers["Authorization"] shouldBe "Bearer custom"
            }
            should("append URL params to the transcriber URL") {
                val urlStr = connect!!.url.toString()
                urlStr shouldContain "$expectedBaseUrl?"
                urlStr shouldContain "key1=value1"
                urlStr shouldContain "key2=hello+world"
            }
        }

        context("Transcription configured after session exists") {
            // Verifies the working path: setTranscriberUrl called after a session exists sends the
            // custom headers and URL params in the update request.
            val manager = createSessionManager()
            allocateParticipant(manager)
            colibriRequests.clear()
            manager.setTranscriberUrl(transcriberUrl, customHeaders, urlParams)

            val setUrlRequest = colibriRequests.firstOrNull()
            setUrlRequest shouldNotBe null
            val connect = setUrlRequest!!.connects?.getConnects()?.firstOrNull()

            should("include a Connect in the setTranscriberUrl request") {
                connect shouldNotBe null
            }
            should("use custom HTTP headers") {
                val headers = connect!!.getHttpHeaders().associate { it.name to it.value }
                headers["X-Custom-Header"] shouldBe "custom-value"
                headers["Authorization"] shouldBe "Bearer custom"
            }
            should("append URL params to the transcriber URL") {
                val urlStr = connect!!.url.toString()
                urlStr shouldContain "$expectedBaseUrl?"
                urlStr shouldContain "key1=value1"
                urlStr shouldContain "key2=hello+world"
            }
        }

        context("Setting the same transcriber URL again from a new TemplatedUrl instance") {
            // The conference creates a new TemplatedUrl for each call, so the URLs must be compared by value.
            fun newTranscriberUrl(template: String = "wss://{{REGION}}.transcriber.example.com/rec") =
                TemplatedUrl(template, requiredKeys = setOf("REGION")).apply { set("MEETING_ID", "test-meeting-id") }

            val manager = createSessionManager()
            allocateParticipant(manager)
            manager.setTranscriberUrl(newTranscriberUrl(), customHeaders, urlParams)
            val connect = colibriRequests.flatMap { it.connects?.getConnects() ?: emptyList() }.single()
            colibriRequests.clear()

            context("With the same headers and params") {
                manager.setTranscriberUrl(newTranscriberUrl(), customHeaders, urlParams)

                should("not log an error") { errors shouldBe emptyList() }
                should("not send a request") { colibriRequests shouldBe emptyList() }
                should("keep the connect") { manager.hasConnects() shouldBe true }
            }
            context("With different headers and params") {
                // The bridge does not support changing the headers or the URL of a running connect, so the new values
                // are not applied.
                manager.setTranscriberUrl(newTranscriberUrl(), mapOf("X-Custom-Header" to "other"), null)

                should("not log an error") { errors shouldBe emptyList() }
                should("not send a request") { colibriRequests shouldBe emptyList() }
                should("keep the connect") { manager.hasConnects() shouldBe true }
                context("And then removing the transcriber") {
                    manager.setTranscriberUrl(null, null, null)

                    should("expire the original connect") {
                        val expired = colibriRequests.flatMap { it.connects?.getConnects() ?: emptyList() }.single()
                        expired.expire shouldBe true
                        expired.url shouldBe connect.url
                    }
                }
            }
            context("With a different URL") {
                manager.setTranscriberUrl(
                    newTranscriberUrl("wss://{{REGION}}.other.example.com/rec"),
                    customHeaders,
                    urlParams
                )

                should("log an error") { errors.size shouldBe 1 }
                should("not send a request") { colibriRequests shouldBe emptyList() }
            }
            context("Removing the transcriber") {
                manager.setTranscriberUrl(null, null, null)

                should("not log an error") { errors shouldBe emptyList() }
                should("remove the connect") { manager.hasConnects() shouldBe false }
            }
        }

        context("Diarize flag") {
            context("Participant with diarize=true") {
                val manager = createSessionManager()
                allocateParticipant(manager, diarize = true)

                val createRequest = colibriRequests.find { it.create }!!
                val endpoint = createRequest.endpoints.first()

                should("set the diarize attribute on the endpoint") {
                    endpoint.diarize shouldBe true
                }
            }
            context("Participant with diarize=false") {
                val manager = createSessionManager()
                allocateParticipant(manager, diarize = false)

                val createRequest = colibriRequests.find { it.create }!!
                val endpoint = createRequest.endpoints.first()

                should("not set the diarize attribute on the endpoint") {
                    // The attribute is omitted, so getDiarize() returns null.
                    endpoint.diarize shouldBe null
                }
            }
        }

        context("Transcription with no custom headers or params") {
            context("Configured before session exists") {
                val manager = createSessionManager()
                manager.setTranscriberUrl(transcriberUrl, null, null)
                allocateParticipant(manager)

                val createRequest = colibriRequests.find { it.create }!!
                val connect = createRequest.connects?.getConnects()?.firstOrNull()

                should("include a Connect") { connect shouldNotBe null }
                should("use the base URL without query params") {
                    connect!!.url.toString() shouldBe expectedBaseUrl
                }
                should("include no HTTP headers") {
                    connect!!.getHttpHeaders() shouldBe emptyList()
                }
            }
            context("Configured after session exists") {
                val manager = createSessionManager()
                allocateParticipant(manager)
                colibriRequests.clear()
                manager.setTranscriberUrl(transcriberUrl, null, null)

                val setUrlRequest = colibriRequests.firstOrNull()!!
                val connect = setUrlRequest.connects?.getConnects()?.firstOrNull()

                should("include a Connect") { connect shouldNotBe null }
                should("use the base URL without query params") {
                    connect!!.url.toString() shouldBe expectedBaseUrl
                }
                should("include no HTTP headers") {
                    connect!!.getHttpHeaders() shouldBe emptyList()
                }
            }
        }
    }
}
