import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.jitsi.jicofo.JicofoHarnessTest
import org.jitsi.jicofo.auth.XMPPDomainAuthAuthority
import org.jitsi.jicofo.xmpp.ConferenceIqHandler
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jitsi.xmpp.extensions.jitsimeet.SessionInvalidPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError.Condition
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration

class XMPPDomainAuthAuthorityTest : JicofoHarnessTest() {
    private val authDomain = "auth.server.net"
    private val guestDomain = "guest.server.net"
    private val authAuthority = XMPPDomainAuthAuthority(
        true,
        Duration.ofDays(1),
        JidCreate.domainBareFrom(authDomain)
    )

    /** The authentication logic is shared between [XMPPDomainAuthAuthority] and the IQ handler, so we test both. */
    private val conferenceIqHandler = ConferenceIqHandler(
        xmppProvider = mockk(relaxed = true),
        focusManager = harness.jicofoServices.focusManager,
        focusAuthJid = "",
        isFocusAnonymous = true,
        authAuthority = authAuthority,
        jigasiEnabled = false
    )

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

        val query = ConferenceIq()

        context("CASE 1: guest Domain, no session-id passed and room does not exist") {
            query.from = user1GuestJid
            query.sessionId = null
            query.room = room1
            query.machineUID = user1MachineUid
            query.to = harness.jicofoServices.jicofoJid
            query.type = IQ.Type.set

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_authorized
        }

        // Save the session ID that will be created with the request below
        lateinit var user1SessionId: String
        context("CASE 2: Auth domain, no session-id and room does not exist") {
            query.from = user1AuthJid
            query.sessionId = null
            query.room = room1
            query.machineUID = user1MachineUid

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldNotBe null
                user1SessionId = it.sessionId
            }
        }

        context("CASE 3: guest domain, no session-id, room exists") {
            query.from = user2GuestJid
            query.sessionId = null
            query.machineUID = user2MachineUid

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldBe null
            }
        }

        context("CASE 4: guest domain, session-id, room does not exists") {
            query.from = user1GuestJid
            query.sessionId = user1SessionId
            query.machineUID = user1MachineUid
            query.room = room2

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldBe user1SessionId
            }
        }

        context("CASE 5: guest jid, invalid session-id, room exists") {
            query.from = user2GuestJid
            query.sessionId = "someinvalidsessionid"
            query.machineUID = user2MachineUid

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
            query.sessionId = user1SessionId
            query.from = user2GuestJid
            query.machineUID = user2MachineUid

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 7: auth jid, but stolen session id") {
            query.sessionId = user1SessionId
            query.from = user2GuestJid
            query.machineUID = user2MachineUid

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 8: guest jid, session used without machine UID") {
            query.from = user1GuestJid
            query.sessionId = user1SessionId
            query.machineUID = null

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 9: auth jid, try to create session without machine UID") {
            query.room = room3
            query.from = user2AuthJid
            query.sessionId = null
            query.machineUID = null

            conferenceIqHandler.handleConferenceIq(query).error.condition shouldBe Condition.not_acceptable
        }

        context("CASE 10: same user, different machine UID - assign separate session") {
            val user3MachineUID = "user3machineUID"
            query.from = user1AuthJid
            query.machineUID = user3MachineUID
            query.sessionId = null

            conferenceIqHandler.handleConferenceIq(query).let {
                it.shouldBeInstanceOf<ConferenceIq>()
                it.sessionId shouldNotBe null
                it.sessionId shouldNotBe user1SessionId
            }
        }
    }
}

