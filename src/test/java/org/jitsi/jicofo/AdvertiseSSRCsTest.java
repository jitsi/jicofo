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

import mock.*;
import mock.muc.*;
import mock.util.*;

import org.jitsi.jicofo.conference.source.*;
import org.jitsi.utils.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class AdvertiseSSRCsTest
{
    private final JicofoHarness harness = new JicofoHarness();

    @After
    public void tearDown()
    {
        harness.shutdown();
    }

    @Test
    public void testOneToOneConference()
        throws Exception
    {
        //FIXME: test when there is participant without contents

        EntityBareJid roomName = JidCreate.entityBareFrom("testSSRCs@conference.pawel.jitsi.net");
        TestConference testConf = new TestConference(harness, roomName);
        MockChatRoom chatRoom = testConf.getChatRoom();

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.join(chatRoom);

        MockParticipant user2 = new MockParticipant("User2");
        user2.join(chatRoom);

        // Accept invite with all users
        assertNotNull(user1.acceptInvite(4000));
        assertNotNull(user2.acceptInvite(4000));

        user1.waitForAddSource(2000);
        user2.waitForAddSource(2000);

        ConferenceSourceMap user1RemoteSources = user1.getRemoteSources();
        assertEquals(2, user1.numRemoteSourcesOfType(MediaType.AUDIO));
        assertEquals(0, user1.numRemoteSourceGroupsOfType(MediaType.AUDIO));

        // Verify SSRC owners and video types
        // From user 1 perspective
        EndpointSourceSet user1User2Sources = user1RemoteSources.get(user2.getMyJid());
        Source user1User2AudioSource = ExtensionsKt.getFirstSourceOfType(user1User2Sources, MediaType.AUDIO);
        assertNotNull(user1User2AudioSource);
        Source user1User2VideoSource = ExtensionsKt.getFirstSourceOfType(user1User2Sources, MediaType.VIDEO);
        assertNotNull(user1User2VideoSource);

        // From user 2 perspective
        EndpointSourceSet user2User1Sources = user2.getRemoteSources().get(user1.getMyJid());
        Source user2User1AudioSource = ExtensionsKt.getFirstSourceOfType(user2User1Sources, MediaType.AUDIO);
        assertNotNull(user2User1AudioSource);
        Source user2User1VideoSource = ExtensionsKt.getFirstSourceOfType(user2User1Sources, MediaType.VIDEO);
        assertNotNull(user2User1VideoSource);

        user2.leave();

        // We no longer send source-remove when a member leaves, it is up to the participants to remove a member's
        // sources when it leaves.
        // assertNotNull(user1.waitForRemoveSource(500));

        assertEquals(1, user1.numRemoteSourcesOfType(MediaType.AUDIO));
        assertEquals(0, user1.numRemoteSourceGroupsOfType(MediaType.AUDIO));

        MockParticipant user3 = new MockParticipant("User3");
        user3.join(chatRoom);
        assertNotNull(user3.acceptInvite(4000));

        user1.waitForAddSource(2000);

        assertEquals(2, user1.numRemoteSourcesOfType(MediaType.AUDIO));
        assertEquals(0, user1.numRemoteSourceGroupsOfType(MediaType.AUDIO));

        assertEquals(2, user3.numRemoteSourcesOfType(MediaType.AUDIO));
        assertEquals(0, user3.numRemoteSourceGroupsOfType(MediaType.AUDIO));

        user3.leave();
        user1.leave();

        testConf.stop();
    }

    @Test
    public void testSourceRemoval()
            throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom("testSSRCremoval@conference.pawel.jitsi.net");
        TestConference testConf = new TestConference(harness, roomName);
        MockChatRoom chat = testConf.getChatRoom();

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.join(chat);

        MockParticipant user2 = new MockParticipant("User2");
        user2.join(chat);

        // Accept invite with all users
        assertNotNull(user1.acceptInvite(4000));
        assertNotNull(user2.acceptInvite(4000));

        user1.waitForAddSource(2000);
        user2.waitForAddSource(2000);

        user2.audioSourceRemove();

        assertNotNull(user1.waitForRemoveSource(500));

        assertEquals(1, user1.numRemoteSourcesOfType(MediaType.AUDIO));
        assertEquals(0, user1.numRemoteSourceGroupsOfType(MediaType.AUDIO));

        MockParticipant user3 = new MockParticipant("User3");
        user3.join(chat);
        assertNotNull(user3.acceptInvite(4000));

        user1.waitForAddSource(2000);

        assertEquals(2, user1.numRemoteSourcesOfType(MediaType.AUDIO));
        assertEquals(2, user3.numRemoteSourcesOfType(MediaType.AUDIO));
        // No groups
        assertEquals(0, user1.numRemoteSourceGroupsOfType(MediaType.AUDIO));
        assertEquals(0, user3.numRemoteSourceGroupsOfType(MediaType.AUDIO));

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
        TestConference testConf = new TestConference(harness, roomName);
        MockChatRoom chatRoom = testConf.getChatRoom();

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.join(chatRoom);

        MockParticipant user2 = new MockParticipant("User2");
        user2.join(chatRoom);

        // Accept invite with all users
        long u1VideoSSRC = MockParticipant.nextSSRC();
        user1.addLocalVideoSSRC(u1VideoSSRC);

        long u1VideoSSRC2 = MockParticipant.nextSSRC();
        user1.addLocalVideoSSRC(u1VideoSSRC2);

        assertNotNull(user1.acceptInvite(4000));

        assertNotNull(user2.acceptInvite(4000));

        assertNotNull(user1.waitForAddSource(1000));
        assertNotNull(user2.waitForAddSource(1000));

        // There is 1 + 2 extra we've created here in the test
        assertEquals(1 /* jvb */ + 3, user2.numRemoteSourcesOfType(MediaType.VIDEO));
        // No groups
        assertEquals(0, user2.numRemoteSourceGroupsOfType(MediaType.VIDEO));

        user1.videoSourceAdd(new long[]{ u1VideoSSRC });

        user1.videoSourceAdd(
            new long[]{
                u1VideoSSRC, u1VideoSSRC2, u1VideoSSRC,
                u1VideoSSRC, u1VideoSSRC, u1VideoSSRC2
            });

        user1.videoSourceAdd(new long[]{ u1VideoSSRC2, u1VideoSSRC });

        // There should be no source-add notifications sent
        assertNull(user2.waitForAddSource(500));

        assertEquals(1 + /* jvb */ + 1, user2.numRemoteSourcesOfType(MediaType.AUDIO));
        // There is 1 + 2 extra we've created here in the test
        assertEquals(1 + /* jvb */ + 3, user2.numRemoteSourcesOfType(MediaType.VIDEO));

        user2.leave();
        user1.leave();

        testConf.stop();
    }
}
