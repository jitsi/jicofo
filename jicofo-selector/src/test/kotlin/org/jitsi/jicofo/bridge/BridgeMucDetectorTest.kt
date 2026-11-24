/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026-Present 8x8, Inc.
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
package org.jitsi.jicofo.bridge

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomListener
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jivesoftware.smack.packet.Presence
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate

class BridgeMucDetectorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val breweryJid = JidCreate.entityBareFrom("jvbbrewery@example.com")
        val bridgeJid = JidCreate.entityFullFrom("jvbbrewery@example.com/jvb1")

        val listener = slot<ChatRoomListener>()
        val chatRoom = mockk<ChatRoom>(relaxed = true) {
            every { addListener(capture(listener)) } returns Unit
        }
        val xmppProvider = mockk<XmppProvider>(relaxed = true) {
            every { registered } returns true
            every { config.name } returns "service"
            every { createRoom(breweryJid, any()) } returns chatRoom
            // BaseBrewery (Java) calls the single-argument overload that @JvmOverloads generates.
            val createRoom1 = XmppProvider::class.java.getMethod("createRoom", EntityBareJid::class.java)
            every { createRoom1.invoke(this@mockk, breweryJid) } returns chatRoom
        }
        val bridgeSelector = mockk<BridgeSelector>(relaxed = true)
        val detector = BridgeMucDetector(xmppProvider, bridgeSelector, breweryJid).apply { init() }

        val member = mockk<ChatRoomMember> {
            every { occupantJid } returns bridgeJid
            every { presence } returns mockk<Presence> {
                every {
                    getExtensionElement(ColibriStatsExtension.ELEMENT, ColibriStatsExtension.NAMESPACE)
                } returns ColibriStatsExtension()
            }
        }
        listener.captured.memberJoined(member)
        verify { bridgeSelector.addJvbAddress(bridgeJid, any()) }

        context("When the bridge leaves the brewery") {
            listener.captured.memberLeft(member)
            should("Remove the bridge and count it if it is lost") {
                verify(exactly = 1) { bridgeSelector.removeJvbAddress(bridgeJid, true) }
                verify(exactly = 0) { bridgeSelector.removeJvbAddress(bridgeJid, false) }
            }
        }
        context("When jicofo leaves the brewery") {
            detector.shutdown()
            should("Remove the bridge and not count it as lost") {
                verify(exactly = 1) { bridgeSelector.removeJvbAddress(bridgeJid, false) }
                verify(exactly = 0) { bridgeSelector.removeJvbAddress(bridgeJid, true) }
            }
            should("Not count the bridges that left before") {
                verify(exactly = 1) { bridgeSelector.clearPendingLostBridges() }
            }
        }
    }
}
