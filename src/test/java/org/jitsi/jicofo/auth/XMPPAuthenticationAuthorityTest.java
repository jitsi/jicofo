/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import mock.xmpp.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.*;
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
    static JicofoHarness osgi = new JicofoHarness();

    private String authDomain = "auth.server.net";
    private String guestDomain = "guest.server.net";

    private MockXmppConnectionWrapper xmppConnection = new MockXmppConnectionWrapper();

    @Before
    public void setUpClass()
        throws Exception
    {
        // Enable XMPP authentication
        System.setProperty(AuthConfig.legacyLoginUrlPropertyName, "XMPP:" + authDomain);
        osgi.init();
    }

    @After
    public void tearDownClass()
    {
        xmppConnection.shutdown();
        osgi.shutdown();
        System.clearProperty(AuthConfig.legacyLoginUrlPropertyName);
    }

    @Test
    public void testXmppDomainAuthentication()
        throws Exception
    {
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
        query.setTo(osgi.jicofoServices.getJicofoJid());
        query.setType(IQ.Type.set);

        IQ errorResponse = xmppConnection.sendIqAndGetResponse(query);

        // REPLY WITH: not-authorized
        assertNotNull(errorResponse);
        assertEquals(
                XMPPError.Condition.not_authorized,
                errorResponse.getError().getCondition());

        // CASE 2: Auth domain, no session-id and room does not exist
        query.setFrom(user1AuthJid);
        query.setSessionId(null);
        query.setRoom(room1);
        query.setMachineUID(user1MachineUid);

        ConferenceIq conferenceIqResponse = (ConferenceIq) xmppConnection.sendIqAndGetResponse(query);

        // REPLY WITH: null - no errors, session-id set in response
        String user1SessionId = conferenceIqResponse.getSessionId();
        assertNotNull(user1SessionId);

        // CASE 3: guest domain, no session-id, room exists
        query.setFrom(user2GuestJid);
        query.setSessionId(null);
        query.setMachineUID(user2MachineUid);

        conferenceIqResponse = (ConferenceIq) xmppConnection.sendIqAndGetResponse(query);

        // REPLY with null - no errors, no session-id in response
        assertNull(conferenceIqResponse.getSessionId());


        //CASE 4: guest domain, session-id, room does not exists
        query.setFrom(user1GuestJid);
        query.setSessionId(user1SessionId);
        query.setMachineUID(user1MachineUid);
        query.setRoom(room2);

        conferenceIqResponse = (ConferenceIq) xmppConnection.sendIqAndGetResponse(query);

        // REPLY with null - no errors, session-id in response(repeated)
        assertEquals(user1SessionId, conferenceIqResponse.getSessionId());

        // CASE 5: guest jid, invalid session-id, room exists
        query.setFrom(user2GuestJid);
        query.setSessionId("someinvalidsessionid");
        query.setMachineUID(user2MachineUid);

        errorResponse = xmppConnection.sendIqAndGetResponse(query);

        // REPLY with session-invalid
        assertNotNull(errorResponse.getError().getExtension(
                SessionInvalidPacketExtension.ELEMENT_NAME,
                SessionInvalidPacketExtension.NAMESPACE));

        // CASE 6: do not allow to use session-id from different machine
        query.setSessionId(user1SessionId);
        query.setFrom(user2GuestJid);
        query.setMachineUID(user2MachineUid);

        errorResponse = xmppConnection.sendIqAndGetResponse(query);

        // not-acceptable
        assertEquals(
                XMPPError.Condition.not_acceptable,
                errorResponse.getError().getCondition());

        // CASE 7: auth jid, but stolen session id
        query.setSessionId(user1SessionId);
        query.setFrom(user2GuestJid);
        query.setMachineUID(user2MachineUid);

        errorResponse = xmppConnection.sendIqAndGetResponse(query);

        // not-acceptable
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                errorResponse.getError().getCondition());

        // CASE 8: guest jid, session used without machine UID
        query.setFrom(user1GuestJid);
        query.setSessionId(user1SessionId);
        query.setMachineUID(null);

        errorResponse = xmppConnection.sendIqAndGetResponse(query);

        // not-acceptable
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                errorResponse.getError().getCondition());

        // CASE 9: auth jid, try to create session without machine UID
        query.setRoom(room3);
        query.setFrom(user2AuthJid);
        query.setSessionId(null);
        query.setMachineUID(null);

        errorResponse = xmppConnection.sendIqAndGetResponse(query);

        // not-acceptable
        assertNotNull(
                XMPPError.Condition.not_acceptable.toString(),
                errorResponse.getError().getCondition());

        // CASE 10: same user, different machine UID - assign separate session
        String user3MachineUID = "user3machineUID";
        query.setFrom(user1AuthJid);
        query.setMachineUID(user3MachineUID);
        query.setSessionId(null);

        conferenceIqResponse = (ConferenceIq) xmppConnection.sendIqAndGetResponse(query);
        String user3SessionId = conferenceIqResponse.getSessionId();

        assertNotNull(user3SessionId);
        assertNotEquals(user1SessionId, user3SessionId);
    }
}
