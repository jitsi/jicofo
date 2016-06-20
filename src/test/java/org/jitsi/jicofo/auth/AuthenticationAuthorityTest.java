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
import net.java.sip.communicator.util.*;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.*;

import org.jitsi.jicofo.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import static org.junit.Assert.*;

/**
 * FIXME: tests have to be run separately or there are problems with OSGi
 *
 * Tests for authentication modules.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class AuthenticationAuthorityTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    // This is called inside of the tests here
    //@BeforeClass
    public static void setUpClass()
        throws Exception
    {
        osgi.init();
    }

    // This is called inside of the tests here
    //@AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    @Test
    public void testShibbolethAuthenticationModule()
        throws Exception
    {
        // Enable shibboleth authentication
        System.setProperty(
            AuthBundleActivator.LOGIN_URL_PNAME, "shibboleth:default");
        System.setProperty(
            AuthBundleActivator.LOGOUT_URL_PNAME, "shibboleth:default");

        setUpClass();

        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        ShibbolethAuthAuthority shibbolethAuth
            = (ShibbolethAuthAuthority) ServiceUtils.getService(
                    FocusBundleActivator.bundleContext,
                    AuthenticationAuthority.class);

        assertNotNull(shibbolethAuth);

        String user1Jid = "user1@server.net";
        String user1MachineUid="machine1uid";
        String user1ShibbolethIdentity = "user1@shibboleth.idp.com";

        String user2Jid = "user2@server.net";
        String user2MachineUid="machine2uid";
        String user2ShibbolethIdentity = "user2@shibboleth.idp.com";

        boolean roomExists = false;
        String room1 = "testroom1";

        ConferenceIq query = new ConferenceIq();
        ConferenceIq response = new ConferenceIq();

        query.setFrom(user1Jid);
        query.setMachineUID(user1MachineUid);
        query.setRoom(room1);

        // CASE 1: No session-id passed and room does not exist
        IQ authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY WITH: 'not-authorized'
        assertNotNull(authError);
        assertEquals(
                XMPPError.Condition.not_authorized.toString(),
                authError.getError().getCondition());

        // CASE 2: Valid session-id passed and room does not exist

        // create session-id
        String user1Session
            = shibbolethAuth.authenticateUser
                (user1MachineUid, user1ShibbolethIdentity, room1, null);

        query.setSessionId(user1Session);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY WITH: null - no errors
        assertNull(authError);

        // CASE 3: no session-id, room exists
        roomExists = true;
        query.setSessionId(null);
        query.setFrom(user2Jid);
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY with null - no errors
        assertNull(authError);

        // CASE 4: invalid session-id, room exists
        roomExists = true;
        query.setSessionId("someinvalidsessionid");
        query.setFrom(user2Jid);
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // REPLY with session-invalid
        assertNotNull(authError);
        assertNotNull(authError.getError().getExtension(
                SessionInvalidPacketExtension.ELEMENT_NAME,
                SessionInvalidPacketExtension.NAMESPACE));

        // CASE 5: valid session, room exists
        roomExists = true;
        String user2Session
            = shibbolethAuth.authenticateUser(
                    user2MachineUid, user2ShibbolethIdentity, room1, null);

        query.setSessionId(user2Session);
        query.setFrom(user2Jid);
        query.setMachineUID(user2MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);
        // REPLY with null - no error
        assertNull(authError);

        // CASE 6: do not allow to use session-id from different machine
        query.setSessionId(user2Session);
        query.setFrom(user1Jid);
        query.setMachineUID(user1MachineUid);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // not-acceptable
        assertNotNull(authError);
        assertEquals(
                XMPPError.Condition.no_acceptable.toString(),
                authError.getError().getCondition());

        // CASE 8: session used without machine UID
        query.setFrom(user1Jid);
        query.setSessionId(user1ShibbolethIdentity);
        query.setMachineUID(null);

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        // not-acceptable
        assertNotNull(authError);
        assertNotNull(
                XMPPError.Condition.no_acceptable.toString(),
                authError.getError().getCondition());

        // CASE 9: authenticate for the same user from different machine
        String user3machineUID = "machine3UID";
        String user3Session
            = shibbolethAuth.authenticateUser(
                    user3machineUID,
                    user1ShibbolethIdentity,
                    room1, null);

        assertNotNull(user3Session);
        assertNotEquals(user1Session, user3Session);

        // And it gets accepted by the handler
        query.setFrom(user1Jid);
        query.setMachineUID(user3machineUID);
        query.setSessionId(user3Session);
        roomExists = false;

        authError
            = focusComponent.processExtensions(query, response, roomExists);

        assertNull(authError);

        // Shutdown OSGi
        System.setProperty(
                AuthBundleActivator.LOGIN_URL_PNAME, "");
        System.setProperty(
                AuthBundleActivator.LOGOUT_URL_PNAME, "");

        tearDownClass();
    }

    @Test
    public void testXmppDomainAuthentication()
        throws Exception
    {
        String authDomain = "auth.server.net";
        String guestDomain = "guest.server.net";

        // Enable XMPP authentication
        System.setProperty(
                AuthBundleActivator.LOGIN_URL_PNAME, "XMPP:" + authDomain);

        setUpClass();

        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        XMPPDomainAuthAuthority xmppAuth
            = (XMPPDomainAuthAuthority) ServiceUtils.getService(
                FocusBundleActivator.bundleContext,
                AuthenticationAuthority.class);

        assertNotNull(xmppAuth);

        String user1GuestJid = "user1@" + guestDomain;
        String user1AuthJid = "user1@" + authDomain;
        String user1MachineUid="machine1uid";

        String user2GuestJid = "user2@" + guestDomain;
        String user2AuthJid = "user2@" + authDomain;
        String user2MachineUid="machine2uid";

        boolean roomExists = false;
        String room1 = "testroom1";

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
                XMPPError.Condition.not_authorized.toString(),
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
                XMPPError.Condition.no_acceptable.toString(),
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
                XMPPError.Condition.no_acceptable.toString(),
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
                XMPPError.Condition.no_acceptable.toString(),
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
                XMPPError.Condition.no_acceptable.toString(),
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

        System.setProperty(AuthBundleActivator.LOGIN_URL_PNAME, "");

        tearDownClass();
    }
}
