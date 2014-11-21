/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import mock.*;
import mock.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.service.shutdown.*;

import org.jitsi.jicofo.osgi.*;
import org.jitsi.jicofo.xmpp.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import org.osgi.framework.*;

import org.xmpp.packet.*;
import org.xmpp.packet.IQ;

import static org.junit.Assert.*;

/**
 *
 */
@RunWith(JUnit4.class)
public class ShutdownTest
{
    static OSGiHandler osgi = new OSGiHandler();

    static String shutdownJid = "shutdown.server.net";

    @BeforeClass
    public static void setUpClass()
        throws InterruptedException
    {
        System.setProperty(
            FocusComponent.SHUTDOWN_ALLOWED_JID_PNAME, shutdownJid);

        OSGi.setUseMockProtocols(true);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
    {
        osgi.shutdown();
    }

    /**
     * Allocates Colibri channels in bundle
     */
    @Test
    public void testShutdown()
        throws Exception
    {
        TestShutdownService shutdownService
            = new TestShutdownService(osgi.bc);

        String roomName = "testroom@conference.pawel.jitsi.net";
        String serverName = "test-server";

        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        TestConference conf1 = new TestConference();

        conf1.allocateMockConference(osgi, serverName, roomName);

        MockParticipant conf1User1 = new MockParticipant("C1U1");
        MockParticipant conf1User2 = new MockParticipant("C1U2");

        conf1.addParticipant(conf1User1);
        conf1.addParticipant(conf1User2);

        assertNotNull(conf1User1.acceptInvite(2000));
        assertNotNull(conf1User2.acceptInvite(2000));

        // Try shutdown from wrong jid
        GracefulShutdownIQ gracefulShutdownIQ = new GracefulShutdownIQ();

        gracefulShutdownIQ.setFrom("randomJid1234");

        IQ result = focusComponent.handleIQSet(
            IQUtils.convert(gracefulShutdownIQ));

        assertEquals(IQ.Type.error, result.getType());
        assertEquals(PacketError.Condition.forbidden,
                     result.getError().getCondition());

        // Now use authorized JID
        gracefulShutdownIQ.setFrom(shutdownJid);

        result = focusComponent.handleIQSet(
            IQUtils.convert(gracefulShutdownIQ));

        assertEquals(IQ.Type.result, result.getType());

        // Now try to allocate conference - must be rejected
        ConferenceIq newConferenceIQ = new ConferenceIq();
        newConferenceIQ.setRoom("newRoom1");

        result = focusComponent.handleIQSet(
            IQUtils.convert(newConferenceIQ));

        assertEquals(IQ.Type.error, result.getType());
        assertEquals(
            ColibriConferenceIQ.GracefulShutdown.ELEMENT_NAME,
            result.getError().getApplicationConditionName());
        assertEquals(
            ColibriConferenceIQ.GracefulShutdown.NAMESPACE,
            result.getError().getApplicationConditionNamespaceURI());

        // Request for active conference - should reply with ready
        ConferenceIq activeConfRequest = new ConferenceIq();
        activeConfRequest.setRoom(roomName);

        result = focusComponent.handleIQSet(
            IQUtils.convert(activeConfRequest));

        assertEquals(IQ.Type.result, result.getType());

        org.jivesoftware.smack.packet.IQ smackResult
            = IQUtils.convert(result);

        assertTrue(smackResult instanceof ConferenceIq);
        assertEquals(true, ((ConferenceIq)smackResult).isReady());

        // Now test shutdown
        assertFalse(shutdownService.shutdownStarted);

        // End conference
        conf1User1.leave();
        conf1User2.leave();

        assertTrue(shutdownService.shutdownStarted);
    }

    class TestShutdownService
        implements ShutdownService
    {

        private boolean shutdownStarted;

        public TestShutdownService(BundleContext context)
        {
            context.registerService(ShutdownService.class, this, null);
        }

        @Override
        public void beginShutdown()
        {
            shutdownStarted = true;
        }
    }
}
