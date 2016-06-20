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
import mock.jvb.*;
import mock.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.colibri.*;
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
    public void testChannelAllocation()
        throws Exception
    {
        String roomName = "testroom@conference.pawel.jitsi.net";
        String serverName = "test-server";
        JitsiMeetConfig config
            = new JitsiMeetConfig(new HashMap<String,String>());

        TestConference testConference
            = TestConference.allocate(osgi.bc, serverName, roomName);

        MockProtocolProvider pps
            = testConference.getFocusProtocolProvider();

        OperationSetColibriConference colibriTool
            = pps.getOperationSet(OperationSetColibriConference.class);

        ColibriConference colibriConf = colibriTool.createNewConference();

        colibriConf.setConfig(config);

        colibriConf.setJitsiVideobridge(
            testConference.getMockVideoBridge().getBridgeJid());

        List<ContentPacketExtension> contents = new ArrayList<>();

        ContentPacketExtension audio
            = JingleOfferFactory.createAudioContent(false, true, false);
        ContentPacketExtension video
            = JingleOfferFactory.createVideoContent(false, true, false, -1, -1);
        ContentPacketExtension data
            = JingleOfferFactory.createDataContent(false, true);

        contents.add(audio);
        contents.add(video);
        contents.add(data);

        MockVideobridge mockBridge = testConference.getMockVideoBridge();

        boolean peer1UseBundle = true;
        String peer1 = "endpoint1";
        boolean peer2UseBundle = true;
        String peer2 = "endpoint2";

        ColibriConferenceIQ peer1Channels
            = colibriConf.createColibriChannels(
                peer1UseBundle, peer1, true, contents);

        assertEquals(3 , mockBridge.getChannelsCount());

        ColibriConferenceIQ peer2Channels
            = colibriConf.createColibriChannels(
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

        colibriConf.expireChannels(peer2Channels);

        //FIXME: fix unreliable sleep call
        Thread.sleep(1000);

        assertEquals(3, mockBridge.getChannelsCount());

        colibriConf.expireChannels(peer1Channels);

        //FIXME: fix unreliable sleep call
        Thread.sleep(1000);

        assertEquals(0 , mockBridge.getChannelsCount());

        testConference.stop();
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
