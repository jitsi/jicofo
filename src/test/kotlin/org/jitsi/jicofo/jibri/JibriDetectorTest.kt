package org.jitsi.jicofo.jibri

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.impl.protocol.xmpp.ChatRoomMember
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.conference.source.SourceInfo
import org.jitsi.jicofo.jibri.JibriDetector.Companion.FAILURE_TIMEOUT
import org.jitsi.jicofo.jibri.JibriDetector.Companion.SELECT_TIMEOUT
import org.jitsi.jicofo.xmpp.muc.MemberRole
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.mins
import org.jitsi.utils.ms
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt.Health.HEALTHY
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt.Health.UNHEALTHY
import org.jitsi.xmpp.extensions.jibri.JibriBusyStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.Presence
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class JibriDetectorTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val mockXmppConnection = mockk<AbstractXMPPConnection>()
    val mockXmppProvider = mockk<XmppProvider>().apply { every { xmppConnection } returns mockXmppConnection }
    val clock = FakeClock().apply { elapse(10.mins) }
    val detector = JibriDetector(mockXmppProvider, JidCreate.entityBareFrom("brewery_name@example.com"), false, clock)
    val jibriJids = listOf(
        JidCreate.entityFullFrom("jibribrewery@bar.com/jibri1"),
        JidCreate.entityFullFrom("jibribrewery@bar.com/jibri2"),
        JidCreate.entityFullFrom("jibribrewery@bar.com/jibri3")
    )

    val jibriMembers = jibriJids.map { JibriChatRoomMember(it, detector) }

    context("Selecting a Jibri") {
        context("When none of the instances are healthy") {
            jibriMembers.forEach { it.setStatus(idle = true, healthy = false) }
            detector.selectJibri() shouldBe null
        }
        context("When none of the instances are idle") {
            jibriMembers.forEach { it.setStatus(idle = false, healthy = true) }
            detector.selectJibri() shouldBe null
        }
        context("Select an idle and healthy jibri if one is available") {
            jibriMembers[0].setStatus(idle = true, healthy = false)
            jibriMembers[1].setStatus(idle = false, healthy = true)
            jibriMembers[2].setStatus(idle = true, healthy = true)

            detector.selectJibri() shouldBe jibriJids[2]
        }
        context("Select timeout") {
            val selection1 = detector.selectJibri()
            selection1 shouldNotBe null

            val selection2 = detector.selectJibri()
            selection2 shouldNotBe null
            selection2 shouldNotBe selection1

            val selection3 = detector.selectJibri()
            selection3 shouldNotBe null
            selection3 shouldNotBe selection1
            selection3 shouldNotBe selection2

            val selection4 = detector.selectJibri()
            selection4 shouldBe null

            clock.elapse(SELECT_TIMEOUT + 10.ms)
            detector.selectJibri() shouldNotBe null
        }
        context("Instances failure") {
            jibriMembers[0].setStatus(idle = false, healthy = true)
            jibriMembers[1].setStatus(idle = true, healthy = true)
            jibriMembers[2].setStatus(idle = true, healthy = true)

            detector.instanceFailed(jibriJids[1])
            detector.selectJibri() shouldBe jibriJids[2]
            detector.selectJibri() shouldBe null // select timeout

            clock.elapse(SELECT_TIMEOUT + 100.ms)
            detector.instanceFailed(jibriJids[2])
            // All idle instances failed recently.
            detector.selectJibri() shouldBe null

            clock.elapse(FAILURE_TIMEOUT + 1.mins)
            // jibri1 failed less recently
            detector.selectJibri() shouldBe jibriJids[1]

            clock.elapse(FAILURE_TIMEOUT + 1.mins)
            detector.instanceFailed(jibriJids[1])
            clock.elapse(SELECT_TIMEOUT + 100.ms)
            detector.instanceFailed(jibriJids[2])
            clock.elapse(SELECT_TIMEOUT + 100.ms)
            jibriMembers[1].setStatus(idle = true, healthy = true)
            // Updated presence should clear the failure indication.
            detector.selectJibri() shouldBe jibriJids[1]
            jibriMembers[2].setStatus(idle = true, healthy = true)
            // jibri1 is still in select timeout
            detector.selectJibri() shouldBe jibriJids[2]
        }
    }
})

class JibriChatRoomMember(
    val jid_: EntityFullJid,
    val detector: JibriDetector
) : ChatRoomMember {
    override fun getName(): String = TODO("Not yet implemented")
    override fun getRole(): MemberRole = TODO("Not yet implemented")
    override fun setRole(role: MemberRole?) = TODO("Not yet implemented")
    override fun getJid(): Jid = TODO("Not yet implemented")
    override fun getSourceInfos(): MutableSet<SourceInfo> = TODO("Not yet implemented")

    override fun getJoinOrderNumber(): Int = TODO("Not yet implemented")
    override fun isRobot(): Boolean = TODO("Not yet implemented")
    override fun isJigasi(): Boolean = TODO("Not yet implemented")
    override fun isJibri(): Boolean = TODO("Not yet implemented")
    override fun isAudioMuted(): Boolean = TODO("Not yet implemented")
    override fun isVideoMuted(): Boolean = TODO("Not yet implemented")

    override fun getRegion(): String = TODO("Not yet implemented")
    override fun getStatsId(): String = TODO("Not yet implemented")

    var idle: Boolean = true
    var healthy: Boolean = true

    init {
        detector.processMemberPresence(this)
    }

    fun setStatus(idle: Boolean, healthy: Boolean) {
        this.idle = idle
        this.healthy = healthy
        detector.processMemberPresence(this)
    }

    override fun getOccupantJid(): EntityFullJid = jid_
    override fun getPresence(): Presence = mockk {
        every {
            getExtensionElement(JibriStatusPacketExt.ELEMENT, JibriStatusPacketExt.NAMESPACE)
        } answers {
            JibriStatusPacketExt().apply {
                healthStatus = HealthStatusPacketExt().apply {
                    status = if (healthy) HEALTHY else UNHEALTHY
                }
                busyStatus = JibriBusyStatusPacketExt().apply {
                    setAttribute("status", if (idle) "idle" else "busy")
                }
            }
        }
    }
}
