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
package org.jitsi.jicofo.auth;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.JUnit4;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for authentication modules.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class XMPPAuthenticationAuthorityTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    private static String authDomain = "auth.server.net";
    private static String guestDomain = "guest.server.net";

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        // Enable XMPP authentication
        System.setProperty(AuthConfig.legacyLoginUrlPropertyName, "XMPP:" + authDomain);
        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
        System.clearProperty(AuthConfig.legacyLoginUrlPropertyName);
    }

    @Test
    public void testXmppDomainAuthentication()
        throws Exception
    {
        System.err.println("Start test");
        XMPPDomainAuthAuthority xmppAuth = (XMPPDomainAuthAuthority) osgi.jicofoServices.getAuthenticationAuthority();

        assertNotNull(xmppAuth);

        Jid user1GuestJid = JidCreate.from("user1@" + guestDomain);
        Jid user1AuthJid = JidCreate.from("user1@" + authDomain);
        String user1MachineUid="machine1uid";

        Jid user2GuestJid = JidCreate.from("user2@" + guestDomain);
        Jid user2AuthJid = JidCreate.from("user2@" + authDomain);
        String user2MachineUid="machine2uid";

        EntityBareJid room1 = JidCreate.entityBareFrom("testroom1@example.com");
        EntityBareJid room2 = JidCreate.entityBareFrom("newroom@example.com");
        EntityBareJid room3 = JidCreate.entityBareFrom("newroom2@example.com");

        ConferenceIq query = new ConferenceIq();

        // CASE 1: guest Domain, no session-id passed and room does not exist
        query.setFrom(user1GuestJid);
        query.setSessionId(null);
        query.setRoom(room1);
        query.setMachineUID(user1MachineUid);

        IqHandler iqHandler = osgi.jicofoServices.getIqHandler();
        IQ response = iqHandler.handleIq(query);

        // REPLY WITH: not-authorized
        assertNotNull(response);
        assertEquals(
                XMPPError.Condition.not_authorized,
                response.getError().getCondition());

        // CASE 2: Auth domain, no session-id and room does not exist
        query.setFrom(user1AuthJid);
        query.setSessionId(null);
        query.setRoom(room1);
        query.setMachineUID(user1MachineUid);

        response = iqHandler.handleIq(query);

        // REPLY WITH: null - no errors, session-id set in response
        assertTrue(response instanceof ConferenceIq);
        String user1SessionId = ((ConferenceIq) response).getSessionId();
        assertNotNull(user1SessionId);

        // CASE 3: guest domain, no session-id, room exists
        query.setFrom(user2GuestJid);
        query.setSessionId(null);
        query.setMachineUID(user2MachineUid);

        response = iqHandler.handleIq(query);

        // REPLY with null - no errors, no session-id in response
        assertTrue(response instanceof ConferenceIq);
        assertNull(((ConferenceIq) response).getSessionId());


        //CASE 4: guest domain, session-id, room does not exists
        query.setFrom(user1GuestJid);
        query.setSessionId(user1SessionId);
        query.setMachineUID(user1MachineUid);
        query.setRoom(room2);

        response = iqHandler.handleIq(query);

        // REPLY with null - no errors, session-id in response(repeated)
        assertTrue(response instanceof ConferenceIq);
        assertEquals(user1SessionId, ((ConferenceIq) response).getSessionId());

        // CASE 5: guest jid, invalid session-id, room exists
        query.setFrom(user2GuestJid);
        query.setSessionId("someinvalidsessionid");
        query.setMachineUID(user2MachineUid);

        response = iqHandler.handleIq(query);

        // REPLY with session-invalid
        assertNotNull(response.getError().getExtension(
                SessionInvalidPacketExtension.ELEMENT_NAME,
                SessionInvalidPacketExtension.NAMESPACE));

        // CASE 6: do not allow to use session-id from different machine
        query.setSessionId(user1SessionId);
        query.setFrom(user2GuestJid);
        query.setMachineUID(user2MachineUid);

        response = iqHandler.handleIq(query);

        // not-acceptable
        assertEquals(
                XMPPError.Condition.not_acceptable,
                response.getError().getCondition());

        // CASE 7: auth jid, but stolen session id
        query.setSessionId(user1SessionId);
        query.setFrom(user2GuestJid);
        query.setMachineUID(user2MachineUid);

        response = iqHandler.handleIq(query);

        // not-acceptable
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                response.getError().getCondition());

        // CASE 8: guest jid, session used without machine UID
        query.setFrom(user1GuestJid);
        query.setSessionId(user1SessionId);
        query.setMachineUID(null);

        response = iqHandler.handleIq(query);

        // not-acceptable
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                response.getError().getCondition());

        // CASE 9: auth jid, try to create session without machine UID
        query.setRoom(room3);
        query.setFrom(user2AuthJid);
        query.setSessionId(null);
        query.setMachineUID(null);

        response = iqHandler.handleIq(query);

        // not-acceptable
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                response.getError().getCondition());

        // CASE 10: same user, different machine UID - assign separate session
        String user3MachineUID = "user3machineUID";
        query.setFrom(user1AuthJid);
        query.setMachineUID(user3MachineUID);
        query.setSessionId(null);

        response = iqHandler.handleIq(query);

        assertTrue(response instanceof ConferenceIq);
        String user3SessionId = ((ConferenceIq) response).getSessionId();

        assertNotNull(user3SessionId);
        assertNotEquals(user1SessionId, user3SessionId);
    }
}
