package org.jitsi.jicofo.recording.jibri

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import org.jitsi.eventadmin.EventAdmin
import org.jitsi.impl.osgi.framework.BundleContextImpl
import org.jitsi.jicofo.FocusBundleActivator
import org.jitsi.osgi.ServiceUtils2
import org.jitsi.protocol.xmpp.XmppConnection
import org.jitsi.test.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging.Logger
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jxmpp.jid.impl.JidCreate

class JibriSessionTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    mockkStatic(ServiceUtils2::class)
    every { ServiceUtils2.getService(any(), EventAdmin::class.java)} returns mockk(relaxed = true)
    val bundleContext: BundleContextImpl = mockk(relaxed = true)
    val owner: JibriSession.Owner = mockk(relaxed = true)
    val roomName = JidCreate.entityBareFrom("room@bar.com/baz")
    val initiator = JidCreate.bareFrom("foo@bar.com/baz")
    val pendingTimeout = 60L
    val maxNumRetries = 2
    val xmppConnection: XmppConnection = mockk()
    val executor: FakeScheduledExecutorService = spyk()
    val jibriList = mutableListOf(
        JidCreate.bareFrom("jibri1@bar.com"),
        JidCreate.bareFrom("jibri2@bar.com"),
        JidCreate.bareFrom("jibri3@bar.com")
    )
    val detector: JibriDetector = mockk {
        every { selectJibri() } returnsMany(jibriList)
        every { isAnyInstanceConnected } returns true
        every { memberHadTransientError(any()) } answers {
            // Simulate the real JibriDetector logic and put the Jibri at the back of the list
            jibriList.remove(arg(0))
            jibriList.add(arg(0))
        }
    }
    val logger: Logger = mockk(relaxed = true)

    val jibriSession = JibriSession(
        bundleContext,
        owner,
        roomName,
        initiator,
        pendingTimeout,
        maxNumRetries,
        xmppConnection,
        executor,
        detector,
        false /* isSIP */,
        null /* sipAddress */,
        "displayName",
        "streamID",
        "youTubeBroadcastId",
        "sessionId",
        "applicationData",
        logger
    )

    FocusBundleActivator.bundleContext = bundleContext

    context("When sending a request to a Jibri to start a session throws an error") {
        val iqRequests = mutableListOf<IQ>()
        every { xmppConnection.sendPacketAndGetReply(capture(iqRequests)) } answers {
            // First return error
            IQ.createErrorResponse(arg(0), XMPPError.Condition.service_unavailable)
        } andThen {
            // Then return a successful response
            JibriIq().apply {
                status = JibriIq.Status.PENDING
                from = (arg(0) as IQ).to
            }
        }
        context("Trying to start a Jibri session") {
            should("retry with another jibri") {
                jibriSession.start()
                verify(exactly = 2) { xmppConnection.sendPacketAndGetReply(any()) }
                iqRequests shouldHaveSize 2
                iqRequests[0].to shouldNotBe iqRequests[1].to
            }
        }
        context("and that's the only Jibri") {
            every { detector.selectJibri() } returns JidCreate.bareFrom("solo@bar.com")
            every { xmppConnection.sendPacketAndGetReply(capture(iqRequests)) } answers {
                // First return error
                IQ.createErrorResponse(arg(0), XMPPError.Condition.service_unavailable)
            }
            context("trying to start a jibri session") {
                should("give up after exceeding the retry count") {
                    shouldThrow<JibriSession.StartException> {
                        jibriSession.start()
                    }
                    verify(exactly = maxNumRetries + 1) { xmppConnection.sendPacketAndGetReply(any())}
                }
            }
        }
    }
})
