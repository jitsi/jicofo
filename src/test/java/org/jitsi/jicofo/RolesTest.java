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
import mock.muc.*;
import mock.util.*;

import net.java.sip.communicator.service.protocol.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 *
 */
@RunWith(JUnit4.class)
public class RolesTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
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
    public void testPassModeratorRole()
        throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom(
                "testroom@conference.pawel.jitsi.net");
        String serverName = "test-server";

        TestConference testConference
            = TestConference.allocate(osgi.bc, serverName, roomName);

        MockProtocolProvider pps
            = testConference.getFocusProtocolProvider();

        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();

        MockMultiUserChat chat
            = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());

        // Join with all users
        MockParticipant users[] = new MockParticipant[4];
        for (int i=0; i < users.length; i++)
        {
            users[i] = new MockParticipant("User" + i);

            users[i].join(chat);
        }
        // Accept invite with all users
        for (MockParticipant user : users)
        {
            assertNotNull(user.acceptInvite(10000));
        }

        for (int i = 0; i < users.length; i++)
        {
            // FIXME: wait for role change otherwise we might randomly fail here
            assertTrue(
                i + " user should have moderator role("
                    + users[i].getNickname() + ")",
                ChatRoomMemberRole.MODERATOR.compareTo(
                    users[i].getChatMember().getRole()) >= 0);

            users[i].leave();
        }

        testConference.stop();
    }
}
