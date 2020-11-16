package org.jitsi.jicofo.recording.jibri

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.java.sip.communicator.service.protocol.event.ChatRoomMemberPresenceChangeEvent
import org.jitsi.protocol.xmpp.XmppChatMember
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriBusyStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Presence
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.impl.JidCreate

class JibriDetectorTest : ShouldSpec({
    val detector = JibriDetector(mockk(), JidCreate.bareFrom("brewery_name"), false)
    val jibriJids = listOf(
        JidCreate.entityFullFrom("jibri1@bar.com/nick"),
        JidCreate.entityFullFrom("jibri2@bar.com/nick")
    )

    jibriJids
        .map { createJibriMember(it) }
        .forEach {
            detector.memberPresenceChanged(ChatRoomMemberPresenceChangeEvent(
                mockk(),
                it,
                ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED,
                "reason"
            ))
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

private fun createJibriMember(jid: EntityFullJid): XmppChatMember {
    return mockk {
        every { occupantJid } returns jid
        every { presence } returns mockk<Presence> {
            every { getExtension<ExtensionElement>(JibriStatusPacketExt.ELEMENT_NAME, JibriStatusPacketExt.NAMESPACE)} answers {
                JibriStatusPacketExt().apply {
                    healthStatus = HealthStatusPacketExt().apply { status = HealthStatusPacketExt.Health.HEALTHY }
                    busyStatus = JibriBusyStatusPacketExt().apply { setAttribute("status", "idle") }
                }
            }
        }
    }
}
