/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import mock.*;
import mock.muc.*;
import mock.util.*;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.osgi.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 *
 */
@RunWith(JUnit4.class)
public class RolesTest
{
    private final static Logger logger = Logger.getLogger(BundleTest.class);

    static OSGiHandler osgi = new OSGiHandler();

    @BeforeClass
    public static void setUpClass()
        throws InterruptedException
    {
        OSGi.setUseMockProtocols(true);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
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
        String roomName = "testroom@conference.pawel.jitsi.net";
        String serverName = "test-server";

        TestConference testConference = new TestConference();

        testConference.allocateMockConference(osgi, serverName, roomName);

        MockProtocolProvider pps
            = testConference.getFocusProtocolProvider();

        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();

        MockMultiUserChat chat
            = (MockMultiUserChat) mucOpSet.findRoom(roomName);

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
            assertNotNull(user.acceptInvite(4000));
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
    }
}
