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
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.*;

/**
 * Tests for authentication modules.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class ShibbolethAuthenticationAuthorityTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        // Enable shibboleth authentication
        System.setProperty(
                AuthBundleActivator.LOGIN_URL_PNAME, "shibboleth:default");
        System.setProperty(
                AuthBundleActivator.LOGOUT_URL_PNAME, "shibboleth:default");
        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
        System.clearProperty(AuthBundleActivator.LOGIN_URL_PNAME);
        System.clearProperty(AuthBundleActivator.LOGOUT_URL_PNAME);
    }

    @Test
    public void testShibbolethAuthenticationModule()
        throws Exception
    {
        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        ShibbolethAuthAuthority shibbolethAuth
            = (ShibbolethAuthAuthority) ServiceUtils2.getService(
                    FocusBundleActivator.bundleContext,
                    AuthenticationAuthority.class);

        assertNotNull(shibbolethAuth);

        EntityBareJid user1Jid = JidCreate.entityBareFrom("user1@server.net");
        String user1MachineUid="machine1uid";
        String user1ShibbolethIdentity = "user1@shibboleth.idp.com";

        Jid user2Jid = JidCreate.from("user2@server.net");
        String user2MachineUid="machine2uid";
        String user2ShibbolethIdentity = "user2@shibboleth.idp.com";

        boolean roomExists = false;
        EntityBareJid room1 = JidCreate.entityBareFrom("testroom1@example.com");

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
                XMPPError.Condition.not_authorized,
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
                XMPPError.Condition.not_acceptable,
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
                XMPPError.Condition.not_acceptable.toString(),
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
    }
}
