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
package org.jitsi.jicofo;

import static org.junit.jupiter.api.Assertions.*;

import mock.*;
import mock.muc.*;
import mock.util.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.junit.jupiter.api.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

public class RolesTest
{
    private final JicofoHarness harness = new JicofoHarness();

    @AfterEach
    public void tearDown()
    {
        harness.shutdown();
    }

    @Test
    public void testPassModeratorRole()
        throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom("testroom@conference.pawel.jitsi.net");
        TestConference testConference = new TestConference(harness, roomName);
        MockChatRoom chatRoom = testConference.getChatRoom();

        // Join with all users
        MockParticipant[] users = new MockParticipant[4];
        for (int i=0; i < users.length; i++)
        {
            users[i] = new MockParticipant("User" + i);

            users[i].join(chatRoom);
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
                MemberRoleKt.hasModeratorRights(users[i].getChatMember().getRole()),
                i + " user should have moderator role(" + users[i].getNickname() + ")");

            users[i].leave();
        }

        testConference.stop();
    }
}
