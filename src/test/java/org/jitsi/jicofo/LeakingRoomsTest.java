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
import mock.xmpp.*;
import org.junit.*;

import static org.junit.Assert.assertEquals;

/**
 * @author Pawel Domas
 */
public class LeakingRoomsTest
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

    @Test
    public void testOneToOneConference()
            throws Exception
    {
        String roomName = "testLeaks@conference.pawel.jitsi.net";
        String serverName = "test-server";

        TestConference testConf
            = TestConference.allocate(osgi.bc, serverName, roomName);

        MockProtocolProvider pps
                = testConf.getFocusProtocolProvider();

        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();

        MockMultiUserChat chat
                = (MockMultiUserChat) mucOpSet.findRoom(roomName);

        // Add discovery delay
        MockSetSimpleCapsOpSet discoOpSet = pps.getMockCapsOpSet();
        discoOpSet.addDiscoveryDelay(30);

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.joinInNewThread(chat);
        MockParticipant user2 = new MockParticipant("User2");
        user2.joinInNewThread(chat);
        MockParticipant user3 = new MockParticipant("User3");
        user3.joinInNewThread(chat);

        Thread.sleep(30);

        MockParticipant user4 = new MockParticipant("User4");
        user4.joinInNewThread(chat);
        MockParticipant user5 = new MockParticipant("User5");
        user5.joinInNewThread(chat);

        long joinTimeout = 5000;
        // User 1 and 4 leaves before channels are allocated for him
        user4.waitForJoinThread(joinTimeout);
        user4.leave();
        user1.waitForJoinThread(joinTimeout);
        user1.leave();

        user2.waitForJoinThread(joinTimeout);
        user3.waitForJoinThread(joinTimeout);
        user5.waitForJoinThread(joinTimeout);

        // Accept invite with all users
        long acceptInviteTimeout = 5000;
        user2.acceptInvite(acceptInviteTimeout);
        user3.acceptInvite(acceptInviteTimeout);
        user5.acceptInvite(acceptInviteTimeout);

        assertEquals(3, testConf.getParticipantCount());
        //FIXME: implement waiting for allocation/expiration in order to verify
        //assertEquals(3,
          //  testConf.getMockVideoBridge().getChannelCountByContent("audio"));
        //assertEquals(3,
          //  testConf.getMockVideoBridge().getChannelCountByContent("video"));
        //assertEquals(3,
          //  testConf.getMockVideoBridge().getChannelCountByContent("data"));

        user2.leave();
        user3.leave();
        user5.leave();

        assertEquals(0, testConf.getParticipantCount());

        //assertEquals(0, testConf.getMockVideoBridge().getChannelsCount());

        testConf.stop();
    }
}
