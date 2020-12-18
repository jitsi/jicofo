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
        // TODO port to withLegacyConfig
        System.setProperty(AuthConfig.legacyLoginUrlPropertyName, ShibbolethAuthAuthority.DEFAULT_URL_CONST);
        System.setProperty(AuthConfig.legacyLogoutUrlPropertyName, ShibbolethAuthAuthority.DEFAULT_URL_CONST);
        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
        System.clearProperty(AuthConfig.legacyLoginUrlPropertyName);
        System.clearProperty(AuthConfig.legacyLogoutUrlPropertyName);
    }

    @Test
    public void testShibbolethAuthenticationModule()
        throws Exception
    {
        IqHandler conferenceIqHandler = osgi.jicofoServices.getIqHandler();

        ShibbolethAuthAuthority shibbolethAuth
            = (ShibbolethAuthAuthority) osgi.jicofoServices.getAuthenticationAuthority();

        assertNotNull(shibbolethAuth);

        EntityBareJid user1Jid = JidCreate.entityBareFrom("user1@server.net");
        String user1MachineUid="machine1uid";
        String user1ShibbolethIdentity = "user1@shibboleth.idp.com";

        Jid user2Jid = JidCreate.from("user2@server.net");
        String user2MachineUid="machine2uid";
        String user2ShibbolethIdentity = "user2@shibboleth.idp.com";
        EntityBareJid room1 = JidCreate.entityBareFrom("testroom1@example.com");

        ConferenceIq query = new ConferenceIq();

        query.setFrom(user1Jid);
        query.setMachineUID(user1MachineUid);
        query.setRoom(room1);

        // CASE 1: No session-id passed and room does not exist
        IQ response = conferenceIqHandler.handleIq(query);

        // REPLY WITH: 'not-authorized'
        assertNotNull(response);
        assertEquals(
                XMPPError.Condition.not_authorized,
                response.getError().getCondition());

        // CASE 2: Valid session-id passed and room does not exist

        // create session-id
        String user1Session
            = shibbolethAuth.authenticateUser(user1MachineUid, user1ShibbolethIdentity, room1);

        query.setSessionId(user1Session);

        response = conferenceIqHandler.handleIq(query);
        assertTrue(response instanceof ConferenceIq);

        // CASE 3: no session-id, room exists
        query.setSessionId(null);
        query.setFrom(user2Jid);
        query.setMachineUID(user2MachineUid);

        response = conferenceIqHandler.handleIq(query);

        // REPLY with null - no errors
        assertTrue(response instanceof ConferenceIq);

        // CASE 4: invalid session-id, room exists
        query.setSessionId("someinvalidsessionid");
        query.setFrom(user2Jid);
        query.setMachineUID(user2MachineUid);

        response = conferenceIqHandler.handleIq(query);

        // REPLY with session-invalid
        assertNotNull(response);
        assertNotNull(response.getError().getExtension(
                SessionInvalidPacketExtension.ELEMENT_NAME,
                SessionInvalidPacketExtension.NAMESPACE));

        // CASE 5: valid session, room exists
        String user2Session = shibbolethAuth.authenticateUser(user2MachineUid, user2ShibbolethIdentity, room1);

        query.setSessionId(user2Session);
        query.setFrom(user2Jid);
        query.setMachineUID(user2MachineUid);

        response = conferenceIqHandler.handleIq(query);
        // REPLY with null - no error
        assertTrue(response instanceof ConferenceIq);

        // CASE 6: do not allow to use session-id from different machine
        query.setSessionId(user2Session);
        query.setFrom(user1Jid);
        query.setMachineUID(user1MachineUid);

        response = conferenceIqHandler.handleIq(query);

        // not-acceptable
        assertNotNull(response);
        assertEquals(
                XMPPError.Condition.not_acceptable,
                response.getError().getCondition());

        // CASE 8: session used without machine UID
        query.setFrom(user1Jid);
        query.setSessionId(user1ShibbolethIdentity);
        query.setMachineUID(null);

        response = conferenceIqHandler.handleIq(query);

        // not-acceptable
        assertNotNull(response);
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                response.getError().getCondition());

        // CASE 9: authenticate for the same user from different machine
        String user3machineUID = "machine3UID";
        String user3Session = shibbolethAuth.authenticateUser(user3machineUID, user1ShibbolethIdentity, room1);

        assertNotNull(user3Session);
        assertNotEquals(user1Session, user3Session);

        // And it gets accepted by the handler
        query.setFrom(user1Jid);
        query.setMachineUID(user3machineUID);
        query.setSessionId(user3Session);

        response = conferenceIqHandler.handleIq(query);

        assertTrue(response instanceof ConferenceIq);
    }
}
