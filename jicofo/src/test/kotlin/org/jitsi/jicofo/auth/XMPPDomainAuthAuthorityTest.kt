import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.jitsi.jicofo.FocusManager
import org.jitsi.jicofo.auth.XMPPDomainAuthAuthority
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jitsi.xmpp.extensions.jitsimeet.SessionInvalidPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError.Condition
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration

class XMPPDomainAuthAuthorityTest : ShouldSpec() {
    private val authDomain = "auth.server.net"
    private val guestDomain = "guest.server.net"
    private val authAuthority = XMPPDomainAuthAuthority(
        true,
        Duration.ofDays(1),
        JidCreate.domainBareFrom(authDomain)
    )
    val focusManager = mockk<FocusManager>(relaxed = true)

    /** The authentication logic is shared between [XMPPDomainAuthAuthority] and the IQ handler, so we test both. */
    private val conferenceIqHandler = ConferenceIqHandler(
        xmppProvider = mockk(relaxed = true),
        focusManager = focusManager,
        focusAuthJid = "",
        authAuthority = authAuthority,
        jigasiEnabled = false,
        visitorsManager = mockk(relaxed = true)
    )

    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance

    init {
        val user1GuestJid = JidCreate.from("user1@$guestDomain")
        val user1AuthJid = JidCreate.from("user1@$authDomain")
        val user1MachineUid = "machine1uid"

        val user2GuestJid = JidCreate.from("user2@$guestDomain")
        val user2AuthJid = JidCreate.from("user2@$authDomain")
        val user2MachineUid = "machine2uid"

        val room1 = JidCreate.entityBareFrom("testroom1@example.com")
        val room2 = JidCreate.entityBareFrom("newroom@example.com")
        val room3 = JidCreate.entityBareFrom("newroom2@example.com")

        context("CASE 1: guest Domain, no session-id passed and room does not exist") {
            every { focusManager.getConference(any()) } returns null
            val query = ConferenceIq().apply {
                from = user1GuestJid
                sessionId = null
                room = room1
                machineUID = user1MachineUid
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
            }

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_authorized
        }

        // Save the session ID that will be created with the request below
        lateinit var user1SessionId: String
        context("CASE 2: Auth domain, no session-id and room does not exist") {
            every { focusManager.getConference(any()) } returns null
            val query = ConferenceIq().apply {
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                from = user1AuthJid
                sessionId = null
                room = room1
                machineUID = user1MachineUid
            }

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldNotBe null
                user1SessionId = it.sessionId
            }
        }

        context("CASE 3: guest domain, no session-id, room exists") {
            every { focusManager.getConference(any()) } returns mockk(relaxed = true)
            val query = ConferenceIq().apply {
                to = JidCreate.from("jicofo@example.com")
                from = user2GuestJid
                sessionId = null
                room = room1
                machineUID = user2MachineUid
            }

            println("query=${query.toXML()}")
            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldBe null
            }
        }

        context("CASE 4: guest domain, session-id, room does not exists") {
            every { focusManager.getConference(any()) } returns null
            val query = ConferenceIq().apply {
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                from = user1GuestJid
                sessionId = user1SessionId
                machineUID = user1MachineUid
                room = room2
            }

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldBe user1SessionId
            }
        }

        context("CASE 5: guest jid, invalid session-id, room exists") {
            every { focusManager.getConference(any()) } returns mockk()
            val query = ConferenceIq().apply {
                room = room2
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                from = user2GuestJid
                sessionId = "someinvalidsessionid"
                machineUID = user2MachineUid
            }

            conferenceIqHandler.handleConferenceIq(query).let {
                val sessionInvalidPacketExtension: SessionInvalidPacketExtension =
                    it.error.getExtension(
                        SessionInvalidPacketExtension.ELEMENT,
                        SessionInvalidPacketExtension.NAMESPACE
                    )
                sessionInvalidPacketExtension shouldNotBe null
            }
        }

        context("CASE 6: do not allow to use session-id from different machine") {
            every { focusManager.getConference(any()) } returns mockk()
            val query = ConferenceIq().apply {
                room = room2
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                sessionId = user1SessionId
                from = user2GuestJid
                machineUID = user2MachineUid
            }

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 7: auth jid, but stolen session id") {
            every { focusManager.getConference(any()) } returns mockk()
            val query = ConferenceIq().apply {
                room = room2
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                sessionId = user1SessionId
                from = user2GuestJid
                machineUID = user2MachineUid
            }

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 8: guest jid, session used without machine UID") {
            every { focusManager.getConference(any()) } returns mockk()
            val query = ConferenceIq().apply {
                room = room2
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                from = user1GuestJid
                sessionId = user1SessionId
                machineUID = null
            }

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 9: auth jid, try to create session without machine UID") {
            every { focusManager.getConference(any()) } returns mockk()
            val query = ConferenceIq().apply {
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                room = room3
                from = user2AuthJid
                sessionId = null
                machineUID = null
            }

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 10: same user, different machine UID - assign separate session") {
            every { focusManager.getConference(any()) } returns mockk(relaxed = true)
            val user3MachineUID = "user3machineUID"
            val query = ConferenceIq().apply {
                room = room3
                to = JidCreate.from("jicofo@example.com")
                type = IQ.Type.set
                from = user1AuthJid
                machineUID = user3MachineUID
                sessionId = null
            }

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldNotBe null
                it.sessionId shouldNotBe user1SessionId
            }
        }
    }
}
