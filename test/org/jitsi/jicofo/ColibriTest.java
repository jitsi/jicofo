/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import mock.*;
import mock.jvb.*;
import mock.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.osgi.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;

import org.jitsi.service.neomedia.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * FIXME: include into test suite(problems between OSGi restarts)
 *
 * Tests colibri tools used for channel management.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class ColibriTest
{
    /**
     * The logger
     */
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

    @Test
    public void testChannelAllocation()
        throws Exception
    {
        String roomName = "testroom@conference.pawel.jitsi.net";
        String serverName = "test-server";

        TestConference testConference = new TestConference();

        testConference.allocateMockConference(osgi, serverName, roomName);

        MockProtocolProvider pps
            = testConference.getFocusProtocolProvider();

        OperationSetColibriConference colibriTool
            = pps.getOperationSet(OperationSetColibriConference.class);

        colibriTool.setJitsiVideobridge(
            testConference.getMockVideoBridge().getBridgeJid());

        List<ContentPacketExtension> contents
            = new ArrayList<ContentPacketExtension>();

        ContentPacketExtension audio
            = JingleOfferFactory.createContentForMedia(
                    MediaType.AUDIO, false);
        ContentPacketExtension video
            = JingleOfferFactory.createContentForMedia(
                    MediaType.VIDEO, false);
        ContentPacketExtension data
            = JingleOfferFactory.createContentForMedia(
                    MediaType.DATA, false);

        contents.add(audio);
        contents.add(video);
        contents.add(data);

        MockVideobridge mockBridge = testConference.getMockVideoBridge();

        boolean peer1UseBundle = true;
        String peer1 = "endpoint1";
        boolean peer2UseBundle = true;
        String peer2 = "endpoint2";

        ColibriConferenceIQ peer1Channels
            = colibriTool.createColibriChannels(
                peer1UseBundle, peer1, true, contents);

        assertEquals(3 , mockBridge.getChannelsCount());

        ColibriConferenceIQ peer2Channels
            = colibriTool.createColibriChannels(
                peer2UseBundle, peer2, true, contents);

        assertEquals(6 , mockBridge.getChannelsCount());

        assertEquals("Peer 1 should have 3 channels allocated",
                     3, countChannels(peer1Channels));
        assertEquals("Peer 2 should have 3 channels allocated",
                     3, countChannels(peer2Channels));

        assertEquals("Peer 1 should have single bundle allocated !",
                     1, peer1Channels.getChannelBundles().size());
        assertEquals("Peer 2 should have single bundle allocated !",
                     1, peer2Channels.getChannelBundles().size());

        colibriTool.expireChannels(peer2Channels);

        //FIXME: fix unreliable sleep call
        Thread.sleep(1000);

        assertEquals(3, mockBridge.getChannelsCount());

        colibriTool.expireChannels(peer1Channels);

        //FIXME: fix unreliable sleep call
        Thread.sleep(1000);

        assertEquals(0 , mockBridge.getChannelsCount());
    }

    private static int countChannels(ColibriConferenceIQ conferenceIq)
    {
        int count = 0;
        for (ColibriConferenceIQ.Content content : conferenceIq.getContents())
        {
            count += content.getChannelCount();
            count += content.getSctpConnections().size();
        }
        return count;
    }
}
