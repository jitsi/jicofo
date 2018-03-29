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
import mock.xmpp.*;
import mock.xmpp.pubsub.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.stats.*;

import org.jivesoftware.smack.packet.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for bridge selection logic.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class BridgeSelectorTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

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

        Bridge bridgeState = selector.selectBridge(null);
        assertTrue(workingBridges.contains(bridgeState.getJid()));

        // Bridge 1 is down !!!
        workingBridges.remove(jvb1Jid);
        jvb1.setIsOperational(false);

        assertTrue(workingBridges.contains(
                selector.selectBridge(null).getJid()));

        // Bridge 2 is down !!!
        workingBridges.remove(jvb2Jid);
        jvb2.setIsOperational(false);

        assertEquals(jvb3Jid, selector.selectBridge(null).getJid());

        // Bridge 1 is up again, but 3 is down instead
        workingBridges.add(jvb1Jid);
        jvb1.setIsOperational(true);

        workingBridges.remove(jvb3Jid);
        jvb3.setIsOperational(false);

        assertEquals(jvb1Jid, selector.selectBridge(null).getJid());

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

        assertEquals(jvb3Jid, selector.selectBridge(null).getJid());

        // Now Jvb 3 gets occupied the most
        mockSubscriptions.fireSubscriptionNotification(
            jvb3PubSubNode, itemId, createJvbStats(300));

        assertEquals(jvb1Jid, selector.selectBridge(null).getJid());

        // Jvb 1 is gone
        jvb1.setIsOperational(false);

        assertEquals(jvb2Jid, selector.selectBridge(null).getJid());

        // TEST all bridges down
        jvb2.setIsOperational(false);
        jvb3.setIsOperational(false);
        assertEquals(null, selector.selectBridge(null));

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
                jvb1PubSubNode, selector.selectBridge(null).getJid());

        // JVB 2 least occupied
        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, itemId, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, itemId, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, itemId, createJvbStats(1));

        assertEquals(jvb2Jid, selector.selectBridge(null).getJid());

        // FAILURE RESET THRESHOLD
        testFailureResetThreshold(selector, mockSubscriptions);

        // Test drain bridges queue
        int maxCount = selector.getKnownBridgesCount();
        while (selector.selectBridge(null) != null)
        {
            Bridge bridge = selector.selectBridge(null);
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
                    selector.selectBridge(null).getJid());
            // Wait for faulty status reset
            Thread.sleep(150);
            // Test node should recover
            assertEquals(
                    nodes[testNode],
                    selector.selectBridge(null).getJid());
        }

        selector.setFailureResetThreshold(
            BridgeSelector.DEFAULT_FAILURE_RESET_THRESHOLD);
    }

    ExtensionElement createJvbStats(int videoStreamCount)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                VideobridgeStatistics.VIDEOSTREAMS, "" + videoStreamCount));

        return statsExtension;
    }
}

