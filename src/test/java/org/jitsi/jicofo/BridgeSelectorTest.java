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
import org.jxmpp.stringprep.*;

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

    private static DomainBareJid jvb1Jid;
    private static DomainBareJid jvb2Jid;
    private static DomainBareJid jvb3Jid;
    private static BridgeState jvb1State;
    private static BridgeState jvb2State;
    private static BridgeState jvb3State;
    private static String jvb1PubSubNode = "jvb1";
    private static String jvb2PubSubNode = "jvb2";
    private static String jvb3PubSubNode = "jvb3";

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        jvb1Jid = JidCreate.domainBareFrom("jvb1.test.domain.net");
        jvb2Jid = JidCreate.domainBareFrom("jvb2.test.domain.net");
        jvb3Jid = JidCreate.domainBareFrom("jvb3.test.domain.net");
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

        jvb1State = meetServices.getBridgeSelector().addJvbAddress(jvb1Jid);
        jvb2State = meetServices.getBridgeSelector().addJvbAddress(jvb2Jid);
        jvb3State = meetServices.getBridgeSelector().addJvbAddress(jvb3Jid);
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

        BridgeState bridgeState = selector.selectVideobridge(null);
        assertTrue(workingBridges.contains(bridgeState.getJid()));

        // Bridge 1 is down !!!
        workingBridges.remove(jvb1Jid);
        jvb1State.setIsOperational(false);

        assertTrue(workingBridges.contains(
                selector.selectVideobridge(null).getJid()));

        // Bridge 2 is down !!!
        workingBridges.remove(jvb2Jid);
        jvb2State.setIsOperational(false);

        assertEquals(jvb3Jid, selector.selectVideobridge(null).getJid());

        // Bridge 1 is up again, but 3 is down instead
        workingBridges.add(jvb1Jid);
        jvb1State.setIsOperational(true);

        workingBridges.remove(jvb3Jid);
        jvb3State.setIsOperational(false);

        assertEquals(jvb1Jid, selector.selectVideobridge(null).getJid());

        // Reset all bridges - now we'll select based on conference count
        workingBridges.clear();
        workingBridges.add(jvb1Jid);
        workingBridges.add(jvb2Jid);
        workingBridges.add(jvb3Jid);
        jvb1State.setIsOperational(true);
        jvb2State.setIsOperational(true);
        jvb3State.setIsOperational(true);

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

        assertEquals(jvb3Jid, selector.selectVideobridge(null).getJid());

        // Now Jvb 3 gets occupied the most
        mockSubscriptions.fireSubscriptionNotification(
            jvb3PubSubNode, itemId, createJvbStats(300));

        assertEquals(jvb1Jid, selector.selectVideobridge(null).getJid());

        // Jvb 1 is gone
        jvb1State.setIsOperational(false);

        assertEquals(jvb2Jid, selector.selectVideobridge(null).getJid());

        // TEST all bridges down
        jvb2State.setIsOperational(false);
        jvb3State.setIsOperational(false);
        assertEquals(null, selector.selectVideobridge(null));

        // Now bridges are up and select based on conference count
        // with pre-configured bridge
        jvb1State.setIsOperational(true);
        jvb2State.setIsOperational(true);
        jvb3State.setIsOperational(true);

        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, itemId, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, itemId, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, itemId, createJvbStats(0));

        // JVB 1 should not be in front
        assertNotEquals(
                jvb1PubSubNode, selector.selectVideobridge(null).getJid());

        // JVB 2 least occupied
        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, itemId, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, itemId, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, itemId, createJvbStats(1));

        assertEquals(jvb2Jid, selector.selectVideobridge(null).getJid());

        // FAILURE RESET THRESHOLD
        testFailureResetThreshold(selector, mockSubscriptions);

        // Test drain bridges queue
        int maxCount = selector.getKnownBridgesCount();
        while (selector.selectVideobridge(null) != null)
        {
            BridgeState bridge = selector.selectVideobridge(null);
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
        DomainBareJid[] nodes = new DomainBareJid[]{ jvb1Jid, jvb2Jid, jvb3Jid};
        BridgeState[] states
            = new BridgeState[] {jvb1State, jvb2State, jvb3State};

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
                    selector.selectVideobridge(null).getJid());
            // Wait for faulty status reset
            Thread.sleep(150);
            // Test node should recover
            assertEquals(
                    nodes[testNode],
                    selector.selectVideobridge(null).getJid());
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

