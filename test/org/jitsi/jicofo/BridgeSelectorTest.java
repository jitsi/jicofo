/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import mock.*;
import mock.xmpp.*;
import mock.xmpp.pubsub.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.osgi.*;
import org.jitsi.videobridge.stats.*;

import org.jivesoftware.smack.packet.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for bridge selection logic.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class BridgeSelectorTest
{
    static OSGiHandler osgi = new OSGiHandler();

    private static String jvbPreConfigured = "config.jvb.test.domain.net";
    private static String jvb1Jid = "jvb1.test.domain.net";
    private static String jvb2Jid = "jvb2.test.domain.net";
    private static String jvb3Jid = "jvb3.test.domain.net";
    private static String jvb1PubSubNode = "jvb1";
    private static String jvb2PubSubNode = "jvb2";
    private static String jvb3PubSubNode = "jvb3";

    @BeforeClass
    public static void setUpClass()
        throws InterruptedException
    {
        String bridgeMapping
            = jvb1Jid + ":" + jvb1PubSubNode + ";" +
              jvb2Jid + ":" + jvb2PubSubNode + ";" +
              jvb3Jid + ":" + jvb3PubSubNode + ";";

        System.setProperty(
            BridgeSelector.BRIDGE_TO_PUBSUB_PNAME, bridgeMapping);

        OSGi.setUseMockProtocols(true);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
    {
        osgi.shutdown();
    }

    private void createMockJvbNodes(JitsiMeetServices meetServices)
    {
        MockSetSimpleCapsOpSet capsOpSet
            = (MockSetSimpleCapsOpSet) meetServices.getCapsOpSet();

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

        meetServices.getBridgeSelector().addJvbAddress(jvb1Jid);
        meetServices.getBridgeSelector().addJvbAddress(jvb2Jid);
        meetServices.getBridgeSelector().addJvbAddress(jvb3Jid);
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

        createMockJvbNodes(meetServices);

        BridgeSelector selector = meetServices.getBridgeSelector();

        // Set pre-configured bridge
        selector.setPreConfiguredBridge(jvbPreConfigured);

        // Check pub-sub nodes mapping
        assertEquals(jvb1Jid,
                     selector.getBridgeForPubSubNode(jvb1PubSubNode));
        assertEquals(jvb2Jid,
                     selector.getBridgeForPubSubNode(jvb2PubSubNode));
        assertEquals(jvb3Jid,
                     selector.getBridgeForPubSubNode(jvb3PubSubNode));

        // Test bridge operational status
        List<String> workingBridges = new ArrayList<String>();
        workingBridges.add(jvb1Jid);
        workingBridges.add(jvb2Jid);
        workingBridges.add(jvb3Jid);

        assertTrue(workingBridges.contains(selector.selectVideobridge()));

        // Bridge 1 is down !!!
        workingBridges.remove(jvb1Jid);
        selector.updateBridgeOperationalStatus(jvb1Jid, false);

        assertTrue(workingBridges.contains(selector.selectVideobridge()));

        // Bridge 2 is down !!!
        workingBridges.remove(jvb2Jid);
        selector.updateBridgeOperationalStatus(jvb2Jid, false);

        assertEquals(jvb3Jid, selector.selectVideobridge());
        assertEquals(jvb3Jid, selector.getPrioritizedBridgesList().get(0));

        // Bridge 1 is up again, but 3 is down instead
        workingBridges.add(jvb1Jid);
        selector.updateBridgeOperationalStatus(jvb1Jid, true);

        workingBridges.remove(jvb3Jid);
        selector.updateBridgeOperationalStatus(jvb3Jid, false);

        assertEquals(jvb1Jid, selector.selectVideobridge());
        assertEquals(jvb1Jid, selector.getPrioritizedBridgesList().get(0));

        // Reset all bridges - now we'll select based on conference count
        workingBridges.clear();
        workingBridges.add(jvb1Jid);
        workingBridges.add(jvb2Jid);
        workingBridges.add(jvb3Jid);
        selector.updateBridgeOperationalStatus(jvb1Jid, true);
        selector.updateBridgeOperationalStatus(jvb2Jid, true);
        selector.updateBridgeOperationalStatus(jvb3Jid, true);

        MockSubscriptionOpSetImpl mockSubscriptions
            = mockProvider.getMockSubscriptionOpSet();

        // Jvb 1 and 3 are occupied by some conferences, 2 is free
        mockSubscriptions.fireSubscriptionNotification(
            jvb1PubSubNode, createJvbStats(10));
        mockSubscriptions.fireSubscriptionNotification(
            jvb2PubSubNode, createJvbStats(23));
        mockSubscriptions.fireSubscriptionNotification(
            jvb3PubSubNode, createJvbStats(0));

        assertEquals(jvb3Jid, selector.selectVideobridge());
        assertEquals(jvb3Jid, selector.getPrioritizedBridgesList().get(0));

        // Now Jvb 3 gets occupied the most
        mockSubscriptions.fireSubscriptionNotification(
            jvb3PubSubNode, createJvbStats(300));

        assertEquals(jvb1Jid, selector.selectVideobridge());
        assertEquals(jvb1Jid, selector.getPrioritizedBridgesList().get(0));

        // Jvb 1 is gone
        selector.updateBridgeOperationalStatus(jvb1Jid, false);

        assertEquals(jvb2Jid, selector.selectVideobridge());
        assertEquals(jvb2Jid, selector.getPrioritizedBridgesList().get(0));

        // TEST pre-configured bridge
        selector.updateBridgeOperationalStatus(jvb2Jid, false);
        selector.updateBridgeOperationalStatus(jvb3Jid, false);
        // Use pre-configured bridge if all others are down
        assertEquals(jvbPreConfigured,
                     selector.getPrioritizedBridgesList().get(0));
        // Pre-configured bridge is never removed from the list
        selector.updateBridgeOperationalStatus(jvbPreConfigured, false);
        assertEquals(jvbPreConfigured,
                     selector.getPrioritizedBridgesList().get(0));

        // Now bridges are up and select based on conference count
        // with pre-configured bridge
        selector.updateBridgeOperationalStatus(jvb1Jid, true);
        selector.updateBridgeOperationalStatus(jvb2Jid, true);
        selector.updateBridgeOperationalStatus(jvb3Jid, true);
        selector.updateBridgeOperationalStatus(jvbPreConfigured, true);

        mockSubscriptions.fireSubscriptionNotification(
                jvbPreConfigured, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, createJvbStats(0));

        // Pre-configured one should not be in front
        assertNotEquals(jvbPreConfigured,
                selector.getPrioritizedBridgesList().get(0));

        // JVB 2 least occupied
        mockSubscriptions.fireSubscriptionNotification(
                jvbPreConfigured, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, createJvbStats(1));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, createJvbStats(0));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, createJvbStats(1));

        assertEquals(jvb2Jid,
                selector.getPrioritizedBridgesList().get(0));

        // FAILURE RESET THRESHOLD
        // Bridge 3 has failed, bridges config, 1 & 2 are heavily occupied
        // Bridge 3 will recover from failure and take over new jobs
        selector.updateBridgeOperationalStatus(jvb1Jid, true);
        selector.updateBridgeOperationalStatus(jvb2Jid, true);
        selector.updateBridgeOperationalStatus(jvb3Jid, true);

        mockSubscriptions.fireSubscriptionNotification(
                jvbPreConfigured, createJvbStats(100));
        mockSubscriptions.fireSubscriptionNotification(
                jvb1PubSubNode, createJvbStats(100));
        mockSubscriptions.fireSubscriptionNotification(
                jvb2PubSubNode, createJvbStats(100));
        mockSubscriptions.fireSubscriptionNotification(
                jvb3PubSubNode, createJvbStats(0));

        selector.updateBridgeOperationalStatus(jvb3Jid, false);
        // Jvb 3 is not working
        assertNotEquals(jvb3Jid, selector.selectVideobridge());
        // Now wait for recovery
        selector.setFailureResetThreshold(100);
        Thread.sleep(150);
        // Jvb 3 should recover
        assertEquals(jvb3Jid, selector.selectVideobridge());
    }

    PacketExtension createJvbStats(int conferenceCount)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                VideobridgeStatistics.CONFERENCES, "" + conferenceCount));

        return statsExtension;
    }
}

