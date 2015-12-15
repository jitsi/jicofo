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

import static org.junit.Assert.*;

/**
 * Basic test for bridge discovery through PubSub stats.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class PubSubBridgeSelectorTest
{
    static OSGiHandler osgi = new OSGiHandler();

    private static String jvb1Jid = "jvb1.test.domain.net";
    private static String jvb2Jid = "jvb2.test.domain.net";
    private static String jvb3Jid = "jvb3.test.domain.net";

    private static String sharedPubSubNode = "sharedJvbStats";

    private static final long MAX_STATS_AGE = 200;

    private static final long x2_MAX_STATS_AGE = MAX_STATS_AGE * 2;

    private MockSubscriptionOpSetImpl subOpSet;

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        System.clearProperty(
            BridgeSelector.BRIDGE_TO_PUBSUB_PNAME);

        System.setProperty(
            FocusManager.SHARED_STATS_PUBSUB_NODE_PNAME, sharedPubSubNode);

        System.setProperty(
            ComponentsDiscovery.ThroughPubSubDiscovery.MAX_STATS_REPORT_AGE_PNAME,
            String.valueOf(MAX_STATS_AGE));

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    private void createMockJvbNodes(MockProtocolProvider protocolProvider)
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

        BridgeSelector selector = meetServices.getBridgeSelector();

        this.subOpSet
            = mockProvider.getMockSubscriptionOpSet();

        Thread.sleep(2000);

        createMockJvbNodes(mockProvider);

        triggerJvbStats(jvb1Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb1Jid));

        triggerJvbStats(jvb2Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb2Jid));

        triggerJvbStats(jvb3Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb3Jid));

        Thread.sleep(x2_MAX_STATS_AGE);

        assertFalse(selector.isJvbOnTheList(jvb1Jid));
        assertFalse(selector.isJvbOnTheList(jvb2Jid));
        assertFalse(selector.isJvbOnTheList(jvb3Jid));

        triggerJvbStats(jvb2Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb2Jid));
    }

    PacketExtension triggerJvbStats(String itemId, int conferenceCount)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                VideobridgeStatistics.CONFERENCES, "" + conferenceCount));

        subOpSet.fireSubscriptionNotification(
            sharedPubSubNode, itemId, statsExtension);

        return statsExtension;
    }
}
