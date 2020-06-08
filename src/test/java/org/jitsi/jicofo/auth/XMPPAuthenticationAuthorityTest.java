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

import mock.*;
import org.jitsi.osgi.*;
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
        System.setProperty(
                AuthBundleActivator.LOGIN_URL_PNAME, "XMPP:" + authDomain);
        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
        System.clearProperty(AuthBundleActivator.LOGIN_URL_PNAME);
    }

    @Test
    public void testXmppDomainAuthentication()
        throws Exception
    {
        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        XMPPDomainAuthAuthority xmppAuth
            = (XMPPDomainAuthAuthority) ServiceUtils2.getService(
                FocusBundleActivator.bundleContext,
                AuthenticationAuthority.class);

        assertNotNull(xmppAuth);

        Jid user1GuestJid = JidCreate.from("user1@" + guestDomain);
        Jid user1AuthJid = JidCreate.from("user1@" + authDomain);
        String user1MachineUid="machine1uid";

        Jid user2GuestJid = JidCreate.from("user2@" + guestDomain);
        Jid user2AuthJid = JidCreate.from("user2@" + authDomain);
        String user2MachineUid="machine2uid";

        boolean roomExists = false;
        EntityBareJid room1 = JidCreate.entityBareFrom("testroom1@example.com");

        ConferenceIq query = new ConferenceIq();
        ConferenceIq response = new ConferenceIq();



        // CASE 1: guest Domain, no session-id passed and room does not exist
        query.setFrom(user1GuestJid);
        query.setSessionId(null);
        roomExists = false;
        query.setRoom(room1);
        query.setMachineUID(user1MachineUid);


        IQ authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY WITH: not-authorized
        assertNotNull(authError);
        assertEquals(
                XMPPError.Condition.not_authorized,
                authError.getError().getCondition());

        // CASE 2: Auth domain, no session-id and room does not exist
        query.setFrom(user1AuthJid);
        query.setSessionId(null);
        roomExists = false;
        query.setRoom(room1);
        query.setMachineUID(user1MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY WITH: null - no errors, session-id set in response
        assertNull(authError);
        String user1SessionId = response.getSessionId();
        assertNotNull(user1SessionId);

        response = new ConferenceIq(); // reset

        // CASE 3: guest domain, no session-id, room exists
        query.setFrom(user2GuestJid);
        query.setSessionId(null);
        roomExists = true;
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY with null - no errors, no session-id in response
        assertNull(authError);
        assertNull(response.getSessionId());


        //CASE 4: guest domain, session-id, room does not exists
        query.setFrom(user1GuestJid);
        query.setSessionId(user1SessionId);
        roomExists = false;
        query.setMachineUID(user1MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY with null - no errors, session-id in response(repeated)
        assertNull(authError);
        assertEquals(user1SessionId, response.getSessionId());

        response = new ConferenceIq(); // reset

        // CASE 5: guest jid, invalid session-id, room exists
        query.setFrom(user2GuestJid);
        query.setSessionId("someinvalidsessionid");
        roomExists = true;
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY with session-invalid
        assertNotNull(authError);
        assertNotNull(authError.getError().getExtension(
                SessionInvalidPacketExtension.ELEMENT_NAME,
                SessionInvalidPacketExtension.NAMESPACE));

        // CASE 6: do not allow to use session-id from different machine
        query.setSessionId(user1SessionId);
        query.setFrom(user2GuestJid);
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // not-acceptable
        assertNotNull(authError);
        assertEquals(
                XMPPError.Condition.not_acceptable,
                authError.getError().getCondition());

        // CASE 7: auth jid, but stolen session id
        query.setSessionId(user1SessionId);
        query.setFrom(user2GuestJid);
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // not-acceptable
        assertNotNull(authError);
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                authError.getError().getCondition());

        // CASE 8: guest jid, session used without machine UID
        query.setFrom(user1GuestJid);
        query.setSessionId(user1SessionId);
        query.setMachineUID(null);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // not-acceptable
        assertNotNull(authError);
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                authError.getError().getCondition());

        // CASE 9: auth jid, try to create session without machine UID
        roomExists = false;
        query.setFrom(user2AuthJid);
        query.setSessionId(null);
        query.setMachineUID(null);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // not-acceptable
        assertNotNull(authError);
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                authError.getError().getCondition());

        // CASE 10: same user, different machine UID - assign separate session
        String user3MachineUID = "user3machineUID";
        roomExists = true;
        query.setFrom(user1AuthJid);
        query.setMachineUID(user3MachineUID);
        query.setSessionId(null);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        assertNull(authError);

        String user3SessionId = response.getSessionId();

        assertNotNull(user3SessionId);
        assertNotEquals(user1SessionId, user3SessionId);
    }
}
