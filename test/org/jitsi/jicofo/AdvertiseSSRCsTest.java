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

import org.jitsi.jicofo.osgi.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 *
 */
@RunWith(JUnit4.class)
public class AdvertiseSSRCsTest
{
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

    @Test
    public void testOneToOneConference()
        throws OperationFailedException, OperationNotSupportedException
    {
        String roomName = "testSSRCs@conference.pawel.jitsi.net";
        String serverName = "test-server";

        TestConference testConf = new TestConference();
        testConf.allocateMockConference(osgi, serverName, roomName);

        MockProtocolProvider pps
            = testConf.getFocusProtocolProvider();

        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();

        MockMultiUserChat chat
            = (MockMultiUserChat) mucOpSet.findRoom(roomName);

        // Join with all users
        MockParticipant user1 = new MockParticipant("User1");
        user1.join(chat);
        MockParticipant user2 = new MockParticipant("User2");
        user2.join(chat);

        // Accept invite with all users
        assertNotNull(user1.acceptInvite(2000));
        assertNotNull(user2.acceptInvite(2000));

        user1.waitForAddSource(2000);

        assertEquals(1, user1.getRemoteSSRCs("audio").size());

        user2.leave();

        assertEquals(0, user1.getRemoteSSRCs("audio").size());

        MockParticipant user3 = new MockParticipant("User3");
        user3.join(chat);
        assertNotNull(user3.acceptInvite(2000));

        user1.waitForAddSource(2000);

        assertEquals(1, user1.getRemoteSSRCs("audio").size());

        user3.leave();
        user1.leave();
    }
}
