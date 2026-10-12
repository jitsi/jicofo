/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
package org.jitsi.jicofo.xmpp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.TaskPools
import org.jitsi.jicofo.mock.MockXmppConnection
import org.jitsi.jicofo.mock.inPlaceExecutor
import org.jxmpp.jid.impl.JidCreate

class MucTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val xmppProvider: XmppProvider = mockk(relaxed = true) {
        every { xmppConnection } returns MockXmppConnection().xmppConnection
    }
    private val muc = Muc(xmppProvider)
    private val roomJid = JidCreate.entityBareFrom("room@conference.example.com")

    override suspend fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.ioPool = inPlaceExecutor
    }

    override suspend fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetIoPool()
    }

    init {
        context("Creating rooms") {
            val room = muc.createChatRoom(roomJid)
            should("refuse a second room with the same JID while the first one is in use") {
                room.addListener(mockk(relaxed = true))
                shouldThrow<XmppProvider.RoomExistsException> { muc.createChatRoom(roomJid) }
            }
            should("replace a room with the same JID that has no listeners") {
                // E.g. a conference removed its listener, but its leave() did not run.
                val newRoom = muc.createChatRoom(roomJid)
                newRoom shouldNotBe room
                room.isJoined shouldBe false
            }
            should("allow a new room with the same JID after the first one was left") {
                room.addListener(mockk(relaxed = true))
                room.leave()
                muc.createChatRoom(roomJid) shouldNotBe room
            }
            should("keep the new room when the old one leaves again") {
                room.leave()
                val newRoom = muc.createChatRoom(roomJid)
                newRoom.addListener(mockk(relaxed = true))
                newRoom shouldNotBe room
                // Leaving the old room a second time must not remove the new room from the map.
                room.leave()
                shouldThrow<XmppProvider.RoomExistsException> { muc.createChatRoom(roomJid) }
            }
        }
    }
}
