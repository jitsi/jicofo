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
package org.jitsi.jicofo.bridge;

import mock.*;
import mock.xmpp.*;
import mock.xmpp.pubsub.*;

import org.jitsi.jicofo.*;
import org.jitsi.xmpp.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.junit.Assert.*;

/**
 * Tests for bridge selection logic.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class BridgeSelectorTest
{
    private static OSGiHandler osgi = OSGiHandler.getInstance();

    private static Jid jvb1Jid;
    private static Jid jvb2Jid;
    private static Jid jvb3Jid;
    private static Bridge jvb1;
    private static Bridge jvb2;
    private static Bridge jvb3;
    private static String jvb1PubSubNode = "jvb1";
    private static String jvb2PubSubNode = "jvb2";
    private static String jvb3PubSubNode = "jvb3";

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        // Everything should work regardless of the type of jid.
        jvb1Jid = JidCreate.from("jvb.example.com");
        jvb2Jid = JidCreate.from("jvb@example.com");
        jvb3Jid = JidCreate.from("jvb@example.com/goldengate");
        String bridgeMapping
            = jvb1Jid + ":" + jvb1PubSubNode + ";" +
              jvb2Jid + ":" + jvb2PubSubNode + ";" +
              jvb3Jid + ":" + jvb3PubSubNode + ";";

        System.setProperty(
            BridgeSelector.BRIDGE_TO_PUBSUB_PNAME, bridgeMapping);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    private void createMockJvbNodes(JitsiMeetServices meetServices,
                                    MockProtocolProvider protocolProvider)
    {
        MockSetSimpleCapsOpSet capsOpSet = protocolProvider.getMockCapsOpSet();

        MockCapsNode jvb1Node
            = new MockCapsNode(
                    jvb1Jid, JitsiMeetServices.VIDEOBRIDGE_FEATURES);

        MockCapsNode jvb2Node
            = new MockCapsNode(
                    jvb2Jid, JitsiMeetServices.VIDEOBRIDGE_FEATURES);

        MockCapsNode jvb3Node
            = new MockCapsNode(
                    jvb3Jid, JitsiMeetServices.VIDEOBRIDGE_FEATURES);

        capsOpSet.addChildNode(jvb1Node);
        capsOpSet.addChildNode(jvb2Node);
        capsOpSet.addChildNode(jvb3Node);

        jvb1 = meetServices.getBridgeSelector().addJvbAddress(jvb1Jid);
        jvb2 = meetServices.getBridgeSelector().addJvbAddress(jvb2Jid);
        jvb3 = meetServices.getBridgeSelector().addJvbAddress(jvb3Jid);
    }

    @Test
    public void selectorTest()
        throws InterruptedException
    {
        JitsiMeetServices meetServices
            = ServiceUtils.getService(osgi.bc, JitsiMeetServices.class);

        ProviderListener providerListener
            = new ProviderListener(FocusBundleActivator.bundleContext);

        MockProtocolProvider mockProvider
            = (MockProtocolProvider) providerListener.obtainProvider(1000);

        createMockJvbNodes(meetServices, mockProvider);

        BridgeSelector selector = meetServices.getBridgeSelector();
        JitsiMeetConference conference = new MockJitsiMeetConference();

        // Check pub-sub nodes mapping
        assertEquals(jvb1Jid,
                     selector.getBridgeForPubSubNode(jvb1PubSubNode));
        assertEquals(jvb2Jid,
                     selector.getBridgeForPubSubNode(jvb2PubSubNode));
        assertEquals(jvb3Jid,
                     selector.getBridgeForPubSubNode(jvb3PubSubNode));

        // Test bridge operational status
        List<Jid> workingBridges = new ArrayList<>();
        workingBridges.add(jvb1Jid);
        workingBridges.add(jvb2Jid);
        workingBridges.add(jvb3Jid);

        Bridge bridgeState = selector.selectBridge(conference);
        assertTrue(workingBridges.contains(bridgeState.getJid()));

        // Bridge 1 is down !!!
        workingBridges.remove(jvb1Jid);
        jvb1.setIsOperational(false);

        assertTrue(workingBridges.contains(
                selector.selectBridge(conference).getJid()));

        // Bridge 2 is down !!!
        workingBridges.remove(jvb2Jid);
        jvb2.setIsOperational(false);

        assertEquals(jvb3Jid, selector.selectBridge(conference).getJid());

        // Bridge 1 is up again, but 3 is down instead
        workingBridges.add(jvb1Jid);
        jvb1.setIsOperational(true);

        workingBridges.remove(jvb3Jid);
        jvb3.setIsOperational(false);

        assertEquals(jvb1Jid, selector.selectBridge(conference).getJid());

        // Reset all bridges - now we'll select based on conference count
        workingBridges.clear();
        workingBridges.add(jvb1Jid);
        workingBridges.add(jvb2Jid);
        workingBridges.add(jvb3Jid);
        jvb1.setIsOperational(true);
        jvb2.setIsOperational(true);
        jvb3.setIsOperational(true);

        MockSubscriptionOpSetImpl mockSubscriptions
            = mockProvider.getMockSubscriptionOpSet();

        // When PubSub mapping is used itemId is not important
        String itemId = "randomNodeForMappingTest";

        // Jvb 1 and 3 are occupied by some conferences, 2 is free
        mockSubscriptions.fireSubscriptionNotification(
            jvb1PubSubNode,itemId, createJvbStats(10));
        mockSubscriptions.fireSubscriptionNotification(
            jvb2PubSubNode, itemId, createJvbStats(23));
        mockSubscriptions.fireSubscriptionNotification(
            jvb3PubSubNode, itemId, createJvbStats(0));

        assertEquals(jvb3Jid, selector.selectBridge(conference).getJid());

        // Now Jvb 3 gets occupied the most
        mockSubscriptions.fireSubscriptionNotification(
            jvb3PubSubNode, itemId, createJvbStats(300));

        assertEquals(jvb1Jid, selector.selectBridge(conference).getJid());

        // Jvb 1 is gone
        jvb1.setIsOperational(false);

        assertEquals(jvb2Jid, selector.selectBridge(conference).getJid());

        // TEST all bridges down
        jvb2.setIsOperational(false);
        jvb3.setIsOperational(false);
        assertNull(selector.selectBridge(conference));

        // Now bridges are up and select based on conference count
        // with pre-configured bridge
        jvb1.setIsOperational(true);
        jvb2.setIsOperational(true);
        jvb3.setIsOperational(true);

        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, itemId, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, itemId, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, itemId, createJvbStats(0));

        // JVB 1 should not be in front
        assertNotEquals(
                jvb1PubSubNode, selector.selectBridge(conference).getJid());

        // JVB 2 least occupied
        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, itemId, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, itemId, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, itemId, createJvbStats(1));

        assertEquals(jvb2Jid, selector.selectBridge(conference).getJid());

        // FAILURE RESET THRESHOLD
        testFailureResetThreshold(selector, mockSubscriptions);

        // Test drain bridges queue
        int maxCount = selector.getKnownBridgesCount();
        while (selector.selectBridge(conference) != null)
        {
            Bridge bridge = selector.selectBridge(conference);
            bridge.setIsOperational(false);
            if (--maxCount < 0)
            {
                fail("Max count exceeded");
            }
        }
    }

    private void testFailureResetThreshold(
        BridgeSelector selector, MockSubscriptionOpSetImpl mockSubscriptions)
            throws InterruptedException
    {
        Jid[] nodes = new Jid[]{ jvb1Jid, jvb2Jid, jvb3Jid};
        Bridge[] states
            = new Bridge[] {jvb1, jvb2, jvb3};

        String[] pubSubNodes
            = new String[] { jvb1PubSubNode, jvb2PubSubNode, jvb3PubSubNode};

        // Will restore failure status after 100 ms
        selector.setFailureResetThreshold(100);

        for (int testNode = 0; testNode < nodes.length; testNode++)
        {
            for (int idx=0; idx < nodes.length; idx++)
            {
                boolean isTestNode = idx == testNode;

                // Test node has 0 load...
                mockSubscriptions.fireSubscriptionNotification(
                    pubSubNodes[idx],
                    "randomItemId",
                    createJvbStats(isTestNode ? 0 : 100));

                // ... and is not operational
                states[idx].setIsOperational(!isTestNode);
            }
            // Should not be selected now
            assertNotEquals(
                    nodes[testNode],
                    selector.selectBridge(new MockJitsiMeetConference()).getJid());
            // Wait for faulty status reset
            Thread.sleep(150);
            // Test node should recover
            assertEquals(
                    nodes[testNode],
                    selector.selectBridge(new MockJitsiMeetConference()).getJid());
        }

        selector.setFailureResetThreshold(
            BridgeSelector.DEFAULT_FAILURE_RESET_THRESHOLD);
    }

    private ColibriStatsExtension createJvbStats(int bitrate)
    {
        return createJvbStats(bitrate, null);
    }

    private ColibriStatsExtension createJvbStats(int bitrate, String region)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                BITRATE_DOWNLOAD, bitrate));
        statsExtension.addStat(
                new ColibriStatsExtension.Stat(
                BITRATE_UPLOAD, bitrate));

        if (region != null)
        {
            statsExtension.addStat(
                    new ColibriStatsExtension.Stat(
                           REGION, region));
            statsExtension.addStat(
                    new ColibriStatsExtension.Stat(
                            RELAY_ID, region));
        }

        return statsExtension;
    }

    @Test
    public void testRegionBasedSelection()
            throws Exception
    {
        JitsiMeetServices meetServices
                = ServiceUtils.getService(osgi.bc, JitsiMeetServices.class);
        BridgeSelector selector = meetServices.getBridgeSelector();

        String region1 = "region1";
        Bridge bridge1 = selector.addJvbAddress(JidCreate.from("bridge1"));
        bridge1.setStats(createJvbStats(0, region1));

        String region2 = "region2";
        Bridge bridge2 = selector.addJvbAddress(JidCreate.from("bridge2"));
        bridge2.setStats(createJvbStats(0, region2));

        String region3 = "region3";
        Bridge bridge3 = selector.addJvbAddress(JidCreate.from("bridge3"));
        bridge3.setStats(createJvbStats(0, region3));

        Bridge localBridge = bridge1;
        BridgeSelectionStrategy strategy
                = new RegionBasedBridgeSelectionStrategy();
        strategy.setLocalRegion(localBridge.getRegion());


        List<Bridge> allBridges
                = Arrays.asList(bridge1, bridge2, bridge3);
        List<Bridge> conferenceBridges = new LinkedList<>();

        // Initial selection should select a bridge in the participant's region
        // if possible
        assertEquals(
            bridge1,
            strategy.select(allBridges, conferenceBridges, region1, true));
        assertEquals(
            bridge2,
            strategy.select(allBridges, conferenceBridges, region2, true));
        // Or a bridge in the local region otherwise
        assertEquals(
            localBridge,
            strategy.select(allBridges, conferenceBridges, "invalid region", true));
        assertEquals(
            localBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        conferenceBridges.add(bridge3);
        assertEquals(
                bridge3,
                strategy.select(allBridges, conferenceBridges, region3, true));
        assertEquals(
                bridge2,
                strategy.select(allBridges, conferenceBridges, region2, true));
        // A participant in an unknown region should be allocated on the existing
        // conference bridge.
        assertEquals(
                bridge3,
                strategy.select(allBridges, conferenceBridges, null, true));

        conferenceBridges.add(bridge2);
        // A participant in an unknown region should be allocated on the least
        // loaded (according to the order of 'allBridges') existing conference
        // bridge.
        assertEquals(
                bridge2,
                strategy.select(allBridges, conferenceBridges, null, true));
        // A participant in a region with no bridges should also be allocated
        // on the least loaded (according to the order of 'allBridges') existing
        // conference bridge.
        assertEquals(
                bridge2,
                strategy.select(allBridges, conferenceBridges, "invalid region", true));
    }
}

