package org.jitsi.jicofo.jibri

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.impl.protocol.xmpp.ChatRoomMember
import org.jitsi.impl.protocol.xmpp.ChatRoomMemberPresenceChangeEvent
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriBusyStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Presence
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate

class JibriDetectorTest : ShouldSpec({
    val mockXmppConnection = mockk<AbstractXMPPConnection>()
    val mockXmppProvider = mockk<XmppProvider>().apply { every { xmppConnection } returns mockXmppConnection }
    val detector = JibriDetector(mockXmppProvider, JidCreate.entityBareFrom("brewery_name@example.com"), false)
    val jibriJids = listOf(
        JidCreate.entityFullFrom("jibri1@bar.com/nick"),
        JidCreate.entityFullFrom("jibri2@bar.com/nick")
    )

    jibriJids
        .map { createJibriMember(it) }
        .forEach {
            detector.memberPresenceChanged(
                ChatRoomMemberPresenceChangeEvent.Joined(it)
            )
        }

    context("When selecting a Jibri, JibriDetector") {
        should("pick the first one from the list") {
            detector.selectJibri() shouldBe jibriJids[0]
        }
        context("and a jibri has had a transient error") {
            detector.memberHadTransientError(jibriJids[0])
            should("pick the next one") {
                detector.selectJibri() shouldBe jibriJids[1]
            }
            context("and the next member has a transient error") {
                detector.memberHadTransientError(jibriJids[1])
                should("pick the next one") {
                    // We will have rolled around to the first Jibri again here
                    detector.selectJibri() shouldBe jibriJids[0]
                }
            }
        }
    }
})

private fun createJibriMember(jid: EntityFullJid): ChatRoomMember {
    return mockk {
        every { occupantJid } returns jid
        every { presence } returns mockk<Presence> {
            every {
                getExtension<ExtensionElement>(JibriStatusPacketExt.ELEMENT_NAME, JibriStatusPacketExt.NAMESPACE)
            } answers {
                JibriStatusPacketExt().apply {
                    healthStatus = HealthStatusPacketExt().apply { status = HealthStatusPacketExt.Health.HEALTHY }
                    busyStatus = JibriBusyStatusPacketExt().apply { setAttribute("status", "idle") }
                }
            }
        }
    }
}
