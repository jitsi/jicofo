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
import mock.xmpp.*;

import net.java.sip.communicator.util.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.event.*;

import org.jitsi.jicofo.util.*;
import org.junit.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.util.concurrent.*;

import static org.junit.Assert.*;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link JvbDoctor} class.
 *
 * @author Pawel Domas
 * FIXME jvbDoctorTest fail randomly on ci (works locally on dev machine)
 */
public class JvbDoctorTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    private static final int HEALTH_CHECK_INT = 300;

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        System.setProperty(
            JvbDoctor.HEALTH_CHECK_INTERVAL_PNAME, "" + HEALTH_CHECK_INT);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    /**
     * The role of <tt>JvbDoctor</tt> is to schedule health-check task for each
     * new bridge discovered and advertised with {@link BridgeEvent#BRIDGE_UP}
     * event. Then a health-check failure should trigger
     * {@link BridgeEvent#HEALTH_CHECK_FAILED} event which after processed by
     * the {@link BridgeSelector} should result in
     * {@link BridgeEvent#BRIDGE_DOWN}. The last event is handled by all
     * {@link JitsiMeetConference} which will restart if are found to be using
     * faulty bridge.
     * FIXME this tests fail randomly on ci (works locally on dev machine)
     */
    public void jvbDoctorTest()
        throws Exception
    {
        Jid jvb1 = JidCreate.from("jvb1.example.com");

        FocusManager focusManager
            = ServiceUtils.getService(osgi.bc, FocusManager.class);

        assertNotNull(focusManager);

        MockProtocolProvider focusPps
            = (MockProtocolProvider) focusManager.getProtocolProvider();

        assertNotNull(focusPps);

        MockVideobridge mockBridge
            = new MockVideobridge(new MockXmppConnection(jvb1), jvb1);

        // Make sure that jvb advertises health-check support
        MockSetSimpleCapsOpSet mockCaps = focusPps.getMockCapsOpSet();
        MockCapsNode jvbNode
            = new MockCapsNode(
                    jvb1,
                    new String[] { DiscoveryUtil.FEATURE_HEALTH_CHECK });
        mockCaps.addChildNode(jvbNode);

        // Make mock JVB return error response to health-check IQ
        mockBridge.setReturnServerError(true);

        mockBridge.start(osgi.bc);

        JitsiMeetServices meetServices
            = ServiceUtils.getService(osgi.bc, JitsiMeetServices.class);

        EventHandler eventSpy = mock(EventHandler.class);
        EventUtil.registerEventHandler(
            osgi.bc,
            new String[] {
                BridgeEvent.BRIDGE_UP,
                BridgeEvent.BRIDGE_DOWN,
                BridgeEvent.HEALTH_CHECK_FAILED },
            eventSpy);

        BridgeSelector selector = meetServices.getBridgeSelector();
        selector.addJvbAddress(jvb1);

        // Verify that BridgeSelector has triggered bridge up event
        verify(eventSpy, timeout(5000))
            .handleEvent(BridgeEvent.createBridgeUp(jvb1));

        TestConference[] testConfs = new TestConference[5];
        String roomName = "testHealth@conference.pawel.jitsi.net";
        String serverName = "test-server";

        for (int i=0; i<testConfs.length; i++)
        {
            testConfs[i] = TestConference.allocate(
                    osgi.bc,
                    serverName,
                    JidCreate.entityBareFrom(roomName + i),
                    mockBridge);

            testConfs[i].addParticipant();
            testConfs[i].addParticipant();
        }

        for (TestConference testConf1 : testConfs)
        {
            assertNotNull(
                focusManager.getConference(testConf1.getRoomName()));
        }

        // Here we verify that first there was HEALTH_CHECK_FAILED event
        // send by JvbDoctor
        verify(eventSpy, timeout(HEALTH_CHECK_INT + 100))
            .handleEvent(BridgeEvent.createHealthFailed(jvb1));
        // and after that BRIDGE_DOWN should be triggered
        // by BridgeSelector
        verify(eventSpy, timeout(100))
            .handleEvent(BridgeEvent.createBridgeDown(jvb1));

        // We have no secondary bridge, so all restarts should fail
        // We'll know that by having null returned for currently used bridge in
        // the conference
        for (TestConference testConf : testConfs)
        {
            JitsiMeetConference conference
                = focusManager.getConference(testConf.getRoomName());

            assertNotNull(conference);

            // No jvb currently in use
            assertTrue(testConf.conference.getBridges().isEmpty());
        }

        // Bridge is now healthy again
        mockBridge.setReturnServerError(false);
        selector.addJvbAddress(jvb1);

        // Wait for all test conferences to restart
        ConferenceRoomListener confRoomListener = new ConferenceRoomListener();
        confRoomListener.await(
            osgi.bc(), testConfs.length, 5, TimeUnit.SECONDS);

        for (TestConference testConf : testConfs)
        {
            JitsiMeetConference conference
                = focusManager.getConference(testConf.getRoomName());

            assertNotNull(conference);

            assertFalse(testConf.conference.getBridges().isEmpty());
        }

        mockBridge.stop(osgi.bc);
    }
}
