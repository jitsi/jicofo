/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp.muc

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.auth.AuthenticationAuthority
import org.jitsi.jicofo.auth.AuthenticationListener
import org.jitsi.jicofo.mock.MockChatRoom
import org.jitsi.jicofo.mock.MockXmppProvider
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class ChatRoomRoleManagerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val mockChatRoom = MockChatRoom(MockXmppProvider().xmppProvider)
    private val chatRoom = mockChatRoom.chatRoom

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    init {
        context("AutoOwnerRoleManager") {
            val roleManager = AutoOwnerRoleManager(chatRoom)
            chatRoom.addListener(roleManager)

            should("elect the first participant that joins") {
                val member = mockChatRoom.addMember("member1")
                verify(exactly = 1) { chatRoom.grantOwnership(member) }
            }
            should("not elect a second owner while one exists") {
                val member1 = mockChatRoom.addMember("member1")
                val member2 = mockChatRoom.addMember("member2")
                verify(exactly = 1) { chatRoom.grantOwnership(member1) }
                verify(exactly = 0) { chatRoom.grantOwnership(member2) }
            }
            should("not elect an owner when a member already has owner rights") {
                mockChatRoom.addMember("owner", MemberRole.OWNER)
                verify(exactly = 0) { chatRoom.grantOwnership(any()) }
            }
            should("skip jibri, jigasi and visitors") {
                mockChatRoom.addMember("jibri", jibri = true)
                mockChatRoom.addMember("jigasi", jigasi = true)
                mockChatRoom.addMember("visitor", MemberRole.VISITOR)

                verify(exactly = 0) { chatRoom.grantOwnership(any()) }

                val participant = mockChatRoom.addMember("participant")
                verify(exactly = 1) { chatRoom.grantOwnership(participant) }
            }
            should("elect a new owner when the owner leaves") {
                val member1 = mockChatRoom.addMember("member1")
                val member2 = mockChatRoom.addMember("member2")
                verify(exactly = 1) { chatRoom.grantOwnership(member1) }

                mockChatRoom.removeMember(member1)
                verify(exactly = 1) { chatRoom.grantOwnership(member2) }
            }
            should("not elect a new owner when a non-owner leaves") {
                val member1 = mockChatRoom.addMember("member1")
                val member2 = mockChatRoom.addMember("member2")
                mockChatRoom.removeMember(member2)
                verify(exactly = 1) { chatRoom.grantOwnership(member1) }
                verify(exactly = 0) { chatRoom.grantOwnership(member2) }
            }
        }

        context("AuthenticationRoleManager") {
            val sessions = mutableMapOf<Jid, String>()
            val authenticationListeners = mutableListOf<AuthenticationListener>()
            val authenticationAuthority: AuthenticationAuthority = mockk(relaxed = true) {
                every { addAuthenticationListener(capture(authenticationListeners)) } returns Unit
                every { getSessionForJid(any()) } answers { sessions[firstArg()] }
            }
            val roleManager = AuthenticationRoleManager(chatRoom, authenticationAuthority)
            chatRoom.addListener(roleManager)

            val authenticatedJid = JidCreate.from("authenticated@example.com")

            should("grant ownership to an authenticated user that joins") {
                sessions[authenticatedJid] = "session-id"
                val member = mockChatRoom.addMember("member1")
                every { member.jid } returns authenticatedJid
                // Re-fire the join now that the jid is set up.
                roleManager.memberJoined(member)

                verify(exactly = 1) { chatRoom.grantOwnership(member) }
            }
            should("not grant ownership to an unauthenticated user") {
                val member = mockChatRoom.addMember("member1")
                roleManager.memberJoined(member)
                verify(exactly = 0) { chatRoom.grantOwnership(any()) }
            }
            should("grant ownership when a user authenticates") {
                val member = mockChatRoom.addMember("member1")
                every { member.jid } returns authenticatedJid

                authenticationListeners.forEach { it.jidAuthenticated(authenticatedJid, "session-id", "machine-uid") }
                verify(exactly = 1) { chatRoom.grantOwnership(member) }
            }
            should("grant ownership to authenticated users with grantOwnership") {
                val member1 = mockChatRoom.addMember("member1")
                every { member1.jid } returns authenticatedJid
                val member2 = mockChatRoom.addMember("member2")
                every { member2.jid } returns JidCreate.from("other@example.com")
                sessions[authenticatedJid] = "session-id"

                roleManager.grantOwnership()

                verify(exactly = 1) { chatRoom.grantOwnership(member1) }
                verify(exactly = 0) { chatRoom.grantOwnership(member2) }
            }
            should("remove the authentication listener when stopped") {
                roleManager.stop()
                verify { authenticationAuthority.removeAuthenticationListener(any()) }
            }
        }
    }
}
