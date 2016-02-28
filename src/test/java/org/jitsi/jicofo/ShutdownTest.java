/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jicofo;

import mock.*;
import mock.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.service.shutdown.*;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.xmpp.util.*;

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
        throws Exception
    {
        System.setProperty(
            FocusComponent.SHUTDOWN_ALLOWED_JID_PNAME, shutdownJid);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
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

        TestConference conf1
            = TestConference.allocate(
                    osgi.bc, serverName, roomName);

        MockParticipant conf1User1 = new MockParticipant("C1U1");
        MockParticipant conf1User2 = new MockParticipant("C1U2");

        conf1.addParticipant(conf1User1);
        conf1.addParticipant(conf1User2);

        assertNotNull(conf1User1.acceptInvite(4000));
        assertNotNull(conf1User2.acceptInvite(4000));

        // Try shutdown from wrong jid
        ShutdownIQ gracefulShutdownIQ = ShutdownIQ.createGracefulShutdownIQ();

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
