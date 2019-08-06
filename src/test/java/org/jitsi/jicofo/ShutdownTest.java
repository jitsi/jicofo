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

import org.jitsi.meet.*;
import org.jitsi.xmpp.extensions.colibri.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.xmpp.util.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
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
    private final OSGiHandler osgi = OSGiHandler.getInstance();
    private Jid shutdownJid;
    private TestShutdownService shutdownService;
    private FocusComponent focusComponent;
    private TestConference conf1;
    private MockParticipant conf1User1;
    private MockParticipant conf1User2;
    private EntityBareJid roomName;
    private boolean shutdownStartedExpected;

    @Before
    public void setUp()
        throws Exception
    {
        shutdownJid = JidCreate.from("shutdown.server.net");
        System.setProperty(
            FocusComponent.SHUTDOWN_ALLOWED_JID_PNAME, shutdownJid.toString());

        osgi.init();

        shutdownService = new TestShutdownService(osgi.bc);
        roomName = JidCreate.entityBareFrom(
                "testroom@conference.pawel.jitsi.net");
        String serverName = "test-server";

        focusComponent = MockMainMethodActivator.getFocusComponent();

        conf1 = TestConference.allocate(osgi.bc, serverName, roomName);

        conf1User1 = new MockParticipant("C1U1");
        conf1User2 = new MockParticipant("C1U2");

        conf1.addParticipant(conf1User1);
        conf1.addParticipant(conf1User2);

        assertNotNull(conf1User1.acceptInvite(4000));
        assertNotNull(conf1User2.acceptInvite(4000));
    }

    @After
    public void tearDown()
        throws Exception
    {
        try
        {
            // End conference
            conf1User1.leave();
            conf1User2.leave();
            conf1.stop();
        }
        finally
        {
            osgi.shutdown();
        }

        // Ensure that the shutdown state matches the test expectation
        assertEquals(shutdownStartedExpected, shutdownService.shutdownStarted);
    }

    /**
     * Try shutdown from wrong jid
     */
    @Test
    public void testShutdownForbidden()
        throws Exception
    {
        shutdownStartedExpected = false;
        ShutdownIQ shutdownIQ = ShutdownIQ.createGracefulShutdownIQ();

        shutdownIQ.setFrom(JidCreate.from("randomJid1234"));

        IQ result = focusComponent.handleIQSetImpl(
                IQUtils.convert(shutdownIQ));

        assertEquals(result.toXML(), IQ.Type.error, result.getType());
        assertEquals(PacketError.Condition.forbidden,
                result.getError().getCondition());
    }

    /**
     * Try shutdown from authorized JID
     */
    @Test
    public void testShutdownAllowed()
        throws Exception
    {
        ShutdownIQ shutdownIQ = ShutdownIQ.createGracefulShutdownIQ();
        shutdownIQ.setFrom(shutdownJid);

        IQ result = focusComponent.handleIQSetImpl(
                IQUtils.convert(shutdownIQ));

        assertEquals(result.toXML(), IQ.Type.result, result.getType());
        shutdownStartedExpected = true;
    }

    /**
     * Try to allocate new conference - must be rejected
     */
    @Test
    public void testNewConferenceDuringShutdown()
        throws Exception
    {
        // initiate shutdown
        testShutdownAllowed();

        // try to allocate the conference
        ConferenceIq newConferenceIQ = new ConferenceIq();
        newConferenceIQ.setRoom(
                JidCreate.entityBareFrom("newRoom1@example.com"));

        IQ result = focusComponent.handleIQSetImpl(
                IQUtils.convert(newConferenceIQ));

        assertEquals(result.toXML(), IQ.Type.error, result.getType());
        assertEquals(
                ColibriConferenceIQ.GracefulShutdown.ELEMENT_NAME,
                result.getError().getApplicationConditionName());
        assertEquals(
                ColibriConferenceIQ.GracefulShutdown.NAMESPACE,
                result.getError().getApplicationConditionNamespaceURI());
    }

    /**
     * Try to join existing conference - must be allowed
     */
    @Test
    public void testJoinExistingConferenceDuringShutdown()
        throws Exception
    {
        // initiate shutdown
        testShutdownAllowed();

        // Request for active conference - should reply with ready
        ConferenceIq activeConfRequest = new ConferenceIq();
        activeConfRequest.setRoom(roomName);

        IQ result = focusComponent.handleIQSetImpl(
            IQUtils.convert(activeConfRequest));

        assertEquals(result.toXML(), IQ.Type.result, result.getType());

        org.jivesoftware.smack.packet.IQ smackResult
            = IQUtils.convert(result);

        assertTrue(smackResult instanceof ConferenceIq);
        assertEquals(true, ((ConferenceIq)smackResult).isReady());
    }

    class TestShutdownService
        implements ShutdownService
    {
        private boolean shutdownStarted;

        TestShutdownService(BundleContext context)
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
