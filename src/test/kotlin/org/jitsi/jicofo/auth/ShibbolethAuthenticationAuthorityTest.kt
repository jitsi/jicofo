/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.auth

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mock.xmpp.MockXmppConnectionWrapper
import org.jitsi.jicofo.JicofoHarness
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jitsi.xmpp.extensions.jitsimeet.SessionInvalidPacketExtension
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests for authentication modules.
 *
 * @author Pawel Domas
 */
class ShibbolethAuthenticationAuthorityTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance

    private var harness: JicofoHarness? = null
    private val xmppConnection = MockXmppConnectionWrapper()

    override fun beforeSpec(spec: Spec) = super.beforeSpec(spec).also {
        // Enable shibboleth authentication
        // TODO port to withLegacyConfig
        System.setProperty(AuthConfig.legacyLoginUrlPropertyName, ShibbolethAuthAuthority.DEFAULT_URL_CONST)
        System.setProperty(AuthConfig.legacyLogoutUrlPropertyName, ShibbolethAuthAuthority.DEFAULT_URL_CONST)
        harness = JicofoHarness()
    }

    override fun afterSpec(spec: Spec) = super.afterSpec(spec).also {
        xmppConnection.shutdown()
        harness?.shutdown()
        System.clearProperty(AuthConfig.legacyLoginUrlPropertyName)
        System.clearProperty(AuthConfig.legacyLogoutUrlPropertyName)
    }

    init {
        context("Shibboleth authentication") {
            val shibbolethAuth = harness?.jicofoServices?.authenticationAuthority
            shibbolethAuth.shouldBeInstanceOf<ShibbolethAuthAuthority>()
            shibbolethAuth as ShibbolethAuthAuthority

            val room = JidCreate.entityBareFrom("testroom1-shibboeth@example.com")
            val query = ConferenceIq().apply {
                to = harness?.jicofoServices?.jicofoJid
                type = IQ.Type.set
                this.room = room
            }

            context("When the room does not exist") {
                val machineUid = "machineUid"
                val identity = "user1@shibboleth.idp.com"
                query.from = JidCreate.entityBareFrom("user1@server.net")
                query.machineUID = machineUid

                context("And no session-id was passed") {
                    xmppConnection.sendIqAndGetResponse(query).let {
                        // REPLY WITH: 'not-authorized'
                        it.shouldBeInstanceOf<ErrorIQ>()
                        it as ErrorIQ
                        it.error.condition shouldBe XMPPError.Condition.not_authorized
                    }
                }
                context("And a valid session-id was passed") {
                    // create a session
                    query.sessionId = shibbolethAuth.authenticateUser(machineUid, identity, room)
                    xmppConnection.sendIqAndGetResponse(query).shouldBeInstanceOf<ConferenceIq>()
                }
            }

            context("When the room exists") {
                val userJid = JidCreate.from("user2@server.net")
                val machineUid = "machine2uid"
                val shibbolethIdentity = "user2@shibboleth.idp.com"

                context("And there is no session-id") {
                    query.sessionId = null
                    query.from = userJid
                    query.machineUID = machineUid
                    xmppConnection.sendIqAndGetResponse(query).shouldBeInstanceOf<ConferenceIq>()
                }
                context("And the session-id is invalid") {
                    // CASE 4: invalid session-id, room exists
                    query.sessionId = "someinvalidsessionid"
                    query.from = userJid
                    query.machineUID = machineUid
                    xmppConnection.sendIqAndGetResponse(query).let {
                        // REPLY with session-invalid
                        it.shouldBeInstanceOf<ErrorIQ>()
                        it as ErrorIQ
                        it.error.getExtension<ExtensionElement>(
                            SessionInvalidPacketExtension.ELEMENT_NAME,
                            SessionInvalidPacketExtension.NAMESPACE
                        ) shouldNotBe null
                    }
                }
                context("And the session-id is valid") {
                    // create a session
                    val sessionId = shibbolethAuth.authenticateUser(machineUid, shibbolethIdentity, room)
                    query.sessionId = sessionId
                    query.from = userJid
                    query.machineUID = machineUid
                    xmppConnection.sendIqAndGetResponse(query).shouldBeInstanceOf<ConferenceIq>()

                    context("Create another session") {
                        val machineUid2 = "machine1uid"
                        val shibbolethIdentity2 = "user1@shibboleth.idp.com"
                        val sessionId2 = shibbolethAuth.authenticateUser(machineUid2, shibbolethIdentity2, room)
                        val userJid2 = JidCreate.entityBareFrom("user1@server.net")

                        context("And machineUid does not match") {
                            query.from = userJid2
                            query.sessionId = sessionId // mismatch
                            query.machineUID = machineUid2
                            xmppConnection.sendIqAndGetResponse(query).let {
                                it.shouldBeInstanceOf<ErrorIQ>()
                                it as ErrorIQ
                                it.error.condition shouldBe XMPPError.Condition.not_acceptable
                            }
                        }
                        context("And the machineUid is not set") {
                            query.from = userJid2
                            query.sessionId = sessionId2
                            query.machineUID = null
                            xmppConnection.sendIqAndGetResponse(query).let {
                                it.shouldBeInstanceOf<ErrorIQ>()
                                it as ErrorIQ
                                it.error.condition shouldBe XMPPError.Condition.not_acceptable
                            }
                        }
                        context("And the same user authenticates again") {
                            // Authenticate the same identity with a different machineUid
                            val machineUid3 = "machine3UID"
                            val sessionId3 = shibbolethAuth.authenticateUser(machineUid3, shibbolethIdentity2, room)
                            sessionId3 shouldNotBe null
                            // Should be a new session
                            sessionId3 shouldNotBe sessionId2

                            // And it gets accepted by the handler
                            query.from = userJid2
                            query.machineUID = machineUid3
                            query.sessionId = sessionId3
                            xmppConnection.sendIqAndGetResponse(query).shouldBeInstanceOf<ConferenceIq>()
                        }
                    }
                }
            }
        }
    }
}
