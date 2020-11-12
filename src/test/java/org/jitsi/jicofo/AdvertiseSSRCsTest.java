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

import org.jitsi.osgi.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

import org.jitsi.protocol.xmpp.util.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.*;

/**
 *
 */
@RunWith(JUnit4.class)
public class AdvertiseSSRCsTest
{
    private final OSGiHandler osgi = OSGiHandler.getInstance();

    @Before
    public void setUpClass()
        throws Exception
    {
        osgi.init();
    }

    @After
    public void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    @Test
    public void testOneToOneConference()
        throws Exception
    {
        //FIXME: test when there is participant without contents

        EntityBareJid roomName = JidCreate.entityBareFrom("testSSRCs@conference.pawel.jitsi.net");
        String serverName = "test-server";

        TestConference testConf = TestConference.allocate(osgi.bc, serverName, roomName);

        MockProtocolProvider pps = testConf.getFocusProtocolProvider();

        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();

        MockMultiUserChat chat = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.setSsrcVideoType(SSRCInfoPacketExtension.CAMERA_VIDEO_TYPE);
        user1.join(chat);

        MockParticipant user2 = new MockParticipant("User2");
        user2.setSsrcVideoType(SSRCInfoPacketExtension.SCREEN_VIDEO_TYPE);
        user2.join(chat);

        // Accept invite with all users
        assertNotNull(user1.acceptInvite(4000));
        assertNotNull(user2.acceptInvite(4000));

        user1.waitForAddSource(2000);
        user2.waitForAddSource(2000);

        assertEquals(2, user1.getRemoteSSRCs("audio").size());
        // No groups
        assertEquals(0, user1.getRemoteSSRCGroups("audio").size());

        // Verify SSRC owners and video types
        // From user 1 perspective
        assertEquals(
            user2.getMyJid(),
            SSRCSignaling.getSSRCOwner(user1.getRemoteSSRCs("audio").get(1)));
        assertEquals(
            user2.getMyJid(),
            SSRCSignaling.getSSRCOwner(user1.getRemoteSSRCs("video").get(1)));
        assertEquals(
            user2.getSsrcVideoType(),
            SSRCSignaling.getVideoType(user1.getRemoteSSRCs("video").get(1)));
        // From user 2 perspective
        assertEquals(
            user1.getMyJid(),
            SSRCSignaling.getSSRCOwner(user2.getRemoteSSRCs("audio").get(1)));
        assertEquals(
            user1.getMyJid(),
            SSRCSignaling.getSSRCOwner(user2.getRemoteSSRCs("video").get(1)));
        assertEquals(
            user1.getSsrcVideoType(),
            SSRCSignaling.getVideoType(user2.getRemoteSSRCs("video").get(1)));

        user2.leave();

        assertNotNull(user1.waitForRemoveSource(500));

        assertEquals(1, user1.getRemoteSSRCs("audio").size());
        // No groups
        assertEquals(0, user1.getRemoteSSRCGroups("audio").size());

        MockParticipant user3 = new MockParticipant("User3");
        user3.join(chat);
        assertNotNull(user3.acceptInvite(4000));

        user1.waitForAddSource(2000);

        assertEquals(2, user1.getRemoteSSRCs("audio").size());
        assertEquals(2, user3.getRemoteSSRCs("audio").size());
        // No groups
        assertEquals(0, user1.getRemoteSSRCGroups("audio").size());
        assertEquals(0, user3.getRemoteSSRCGroups("audio").size());

        user3.leave();
        user1.leave();

        testConf.stop();
    }

    @Test
    public void testSourceRemoval()
            throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom("testSSRCremoval@conference.pawel.jitsi.net");
        String serverName = "test-server";
        TestConference testConf = TestConference.allocate(osgi.bc, serverName, roomName);
        MockProtocolProvider pps = testConf.getFocusProtocolProvider();
        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();
        MockMultiUserChat chat = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.setSsrcVideoType(SSRCInfoPacketExtension.CAMERA_VIDEO_TYPE);
        user1.join(chat);

        MockParticipant user2 = new MockParticipant("User2");
        user2.setSsrcVideoType(SSRCInfoPacketExtension.SCREEN_VIDEO_TYPE);
        user2.join(chat);

        // Accept invite with all users
        assertNotNull(user1.acceptInvite(4000));
        assertNotNull(user2.acceptInvite(4000));

        user1.waitForAddSource(2000);
        user2.waitForAddSource(2000);

        user2.audioSourceRemove(1);

        assertNotNull(user1.waitForRemoveSource(500));

        assertEquals(1, user1.getRemoteSSRCs("audio").size());
        assertEquals(0, user1.getRemoteSSRCGroups("audio").size());

        MockParticipant user3 = new MockParticipant("User3");
        user3.join(chat);
        assertNotNull(user3.acceptInvite(4000));

        user1.waitForAddSource(2000);

        assertEquals(2, user1.getRemoteSSRCs("audio").size());
        assertEquals(2, user3.getRemoteSSRCs("audio").size());
        // No groups
        assertEquals(0, user1.getRemoteSSRCGroups("audio").size());
        assertEquals(0, user3.getRemoteSSRCGroups("audio").size());

        user3.leave();
        user2.leave();
        user1.leave();

        testConf.stop();
    }

    @Test
    public void testDuplicatedSSRCs()
        throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom("testSSRCs@conference.pawel.jitsi.net");
        String serverName = "test-server";
        TestConference testConf = TestConference.allocate(osgi.bc, serverName, roomName);
        MockProtocolProvider pps = testConf.getFocusProtocolProvider();
        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();
        MockMultiUserChat chat = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.join(chat);

        MockParticipant user2 = new MockParticipant("User2");
        user2.join(chat);

        // Accept invite with all users
        long u1VideoSSRC = MockParticipant.nextSSRC();
        user1.addLocalVideoSSRC(u1VideoSSRC, null);

        long u1VideoSSRC2 = MockParticipant.nextSSRC();
        user1.addLocalVideoSSRC(u1VideoSSRC2, null);

        assertNotNull(user1.acceptInvite(4000));

        assertNotNull(user2.acceptInvite(4000));

        assertNotNull(user1.waitForAddSource(1000));
        assertNotNull(user2.waitForAddSource(1000));

        // There is 1 + 2 extra we've created here in the test
        assertEquals(1 /* jvb */ + 3, user2.getRemoteSSRCs("video").size());
        // No groups
        assertEquals(0, user2.getRemoteSSRCGroups("video").size());

        user1.videoSourceAdd(new long[]{ u1VideoSSRC }, false);

        user1.videoSourceAdd(
            new long[]{
                u1VideoSSRC, u1VideoSSRC2, u1VideoSSRC,
                u1VideoSSRC, u1VideoSSRC, u1VideoSSRC2
            }, false);

        user1.videoSourceAdd(new long[]{ u1VideoSSRC2, u1VideoSSRC }, false);

        // There should be no source-add notifications sent
        assertNull(user2.waitForAddSource(500));

        assertEquals(1 + /* jvb */ + 1, user2.getRemoteSSRCs("audio").size());
        // There is 1 + 2 extra we've created here in the test
        assertEquals(1 + /* jvb */ + 3, user2.getRemoteSSRCs("video").size());

        user2.leave();
        user1.leave();

        testConf.stop();
    }

    @Test
    public void testSSRCLimit()
        throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom("testSSRCs@conference.pawel.jitsi.net");
        String serverName = "test-server";
        TestConference testConf = TestConference.allocate(osgi.bc, serverName, roomName);

        MockProtocolProvider pps = testConf.getFocusProtocolProvider();
        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();
        MockMultiUserChat chat = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.join(chat);
        user1.waitForJoinThread(5000);

        MockParticipant user2 = new MockParticipant("User2");
        user2.join(chat);
        user2.waitForJoinThread(5000);

        int maxSSRCs = ConferenceConfig.config.getMaxSsrcsPerUser();

        // Accept invite with all users
        // Add many SSRCs to both users

        // Video:
        // User 1 will fit into the limit on accept, but we'll try to exceed
        // it later
        int user1ExtraVideoSSRCCount = maxSSRCs / 2;
        // User 2 will exceed SSRC limit on accept already
        int user2ExtraVideoSSRCCount = maxSSRCs + 3;
        user1.addMultipleVideoSSRCs(user1ExtraVideoSSRCCount);
        user2.addMultipleVideoSSRCs(user2ExtraVideoSSRCCount);

        // Audio: the opposite scenario
        int user1ExtraAudioSSRCCount = maxSSRCs + 5;
        int user2ExtraAudioSSRCCount = maxSSRCs / 2;
        user1.addMultipleAudioSSRCs(user1ExtraAudioSSRCCount);
        user2.addMultipleAudioSSRCs(user2ExtraAudioSSRCCount);

        assertNotNull(user1.acceptInvite(10000));
        assertNotNull(user2.acceptInvite(10000));

        assertNotNull(user1.waitForAddSource(4000));
        assertNotNull(user2.waitForAddSource(4000));

        int expectedMax
            = 1 /* jvb's mixed */
                + maxSSRCs /* max that can come from 1 participant */;

        // Verify User1's SSRCs seen by User2
        assertEquals(1 /* jvb's mixed */ + 1 + user1ExtraVideoSSRCCount,
                     user2.getRemoteSSRCs("video").size());
        assertEquals(expectedMax,
                     user2.getRemoteSSRCs("audio").size());
        // Verify User1's SSRCs seen by User1
        assertEquals(expectedMax,
            user1.getRemoteSSRCs("video").size());
        assertEquals(1 /* jvb's mixed */ + 1 + user2ExtraAudioSSRCCount,
            user1.getRemoteSSRCs("audio").size());

        // No groups
        assertEquals(0, user2.getRemoteSSRCGroups("video").size());
        assertEquals(0, user1.getRemoteSSRCGroups("video").size());
        assertEquals(0, user2.getRemoteSSRCGroups("audio").size());
        assertEquals(0, user1.getRemoteSSRCGroups("audio").size());

        // Now let's test the limits for source-add
        // User1 will have video SSRCs filled and audio are filled already
        user1.videoSourceAdd(maxSSRCs / 2);
        assertNotNull(user2.waitForAddSource(300));
        assertEquals(expectedMax, user2.getRemoteSSRCs("video").size());

        user1.audioSourceAdd(5);
        assertNull(user2.waitForAddSource(300));
        assertEquals(expectedMax, user2.getRemoteSSRCs("audio").size());

        // User2 has video SSRCs filled already and audio will be filled
        user2.videoSourceAdd(maxSSRCs / 2);
        assertNull(user1.waitForAddSource(300));
        assertEquals(expectedMax, user1.getRemoteSSRCs("video").size());

        user2.audioSourceAdd(maxSSRCs / 2);
        assertNotNull(user1.waitForAddSource(300));
        assertEquals(expectedMax, user1.getRemoteSSRCs("audio").size());

        user2.leave();
        user1.leave();

        // stopping the conference also stops the bridge,
        // but the users leaving still want the bridge to disconnect properly
        Thread.sleep(5000);

        testConf.stop();
    }
}
