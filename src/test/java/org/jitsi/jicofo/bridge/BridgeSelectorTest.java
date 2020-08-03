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

import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;
import org.jitsi.xmpp.extensions.colibri.*;

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
    private OSGiHandler osgi = OSGiHandler.getInstance();

    private Jid jvb1Jid;
    private Jid jvb2Jid;
    private Jid jvb3Jid;
    private Bridge jvb1;
    private Bridge jvb2;
    private Bridge jvb3;
    private JitsiMeetServices meetServices;

    @Before
    public void setUp()
        throws Exception
    {
        // Everything should work regardless of the type of jid.
        jvb1Jid = JidCreate.from("jvb.example.com");
        jvb2Jid = JidCreate.from("jvb@example.com");
        jvb3Jid = JidCreate.from("jvb@example.com/goldengate");
        osgi.init();

        this.meetServices
            = ServiceUtils2.getService(osgi.bc, JitsiMeetServices.class);

        BridgeSelector bridgeSelector = meetServices.getBridgeSelector();
        jvb1 = bridgeSelector.addJvbAddress(jvb1Jid);
        jvb2 = bridgeSelector.addJvbAddress(jvb2Jid);
        jvb3 = bridgeSelector.addJvbAddress(jvb3Jid);
    }

    @After
    public void tearDown()
        throws Exception
    {
        osgi.shutdown();
    }

    @Test
    public void selectorTest()
    {
        BridgeSelector selector = meetServices.getBridgeSelector();
        JitsiMeetConference conference = new MockJitsiMeetConference();

        // Test bridge operational status
        List<Jid> workingBridges = new ArrayList<>();
        workingBridges.add(jvb1Jid);
        workingBridges.add(jvb2Jid);
        workingBridges.add(jvb3Jid);

        // This part of the test doesn't care about reset threshold
        selector.setFailureResetThreshold(0);
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

        // Jvb 1 and 3 are occupied by some conferences, 2 is free
        jvb1.setStats(createJvbStats(10));
        jvb2.setStats(createJvbStats(23));
        jvb3.setStats(createJvbStats(0));

        assertEquals(jvb3Jid, selector.selectBridge(conference).getJid());

        // Now Jvb 3 gets occupied the most
        jvb3.setStats(createJvbStats(300));

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

        jvb1.setStats(createJvbStats(1));
        jvb2.setStats(createJvbStats(0));
        jvb3.setStats(createJvbStats(0));

        // JVB 1 should not be in front
        assertNotEquals(jvb1Jid, selector.selectBridge(conference).getJid());

        // JVB 2 least occupied
        jvb1.setStats(createJvbStats(1));
        jvb2.setStats(createJvbStats(0));
        jvb3.setStats(createJvbStats(1));

        assertEquals(jvb2Jid, selector.selectBridge(conference).getJid());

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

    @Test
    public void notOperationalThresholdTest()
            throws InterruptedException
    {
        JitsiMeetServices meetServices
                = ServiceUtils2.getService(osgi.bc, JitsiMeetServices.class);

        BridgeSelector selector = meetServices.getBridgeSelector();
        Bridge[] bridges = new Bridge[] {jvb1, jvb2, jvb3};

        // Will restore failure status after 100 ms
        selector.setFailureResetThreshold(100);

        for (int testedIdx = 0; testedIdx < bridges.length; testedIdx++)
        {
            for (int idx=0; idx < bridges.length; idx++)
            {
                boolean isTestNode = idx == testedIdx;

                // Test node has 0 load...
                bridges[idx].setStats(createJvbStats(isTestNode ? 0 : 100));

                // ... and is not operational
                bridges[idx].setIsOperational(!isTestNode);
            }
            // Should not be selected now
            assertNotEquals(
                    bridges[testedIdx].getJid(),
                    selector.selectBridge(new MockJitsiMeetConference()).getJid());

            for (int idx=0; idx < bridges.length; idx++)
            {
                // try to mark as operational before the blackout period passed
                bridges[idx].setIsOperational(true);
            }

            // Should still not be selected
            assertNotEquals(
                    bridges[testedIdx].getJid(),
                    selector.selectBridge(new MockJitsiMeetConference()).getJid());

            // Wait for faulty status reset
            Thread.sleep(150);
            // Test node should recover
            assertEquals(
                    bridges[testedIdx].getJid(),
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
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                PACKET_RATE_DOWNLOAD, bitrate));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                PACKET_RATE_UPLOAD, bitrate));

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
        JitsiMeetServices meetServices = ServiceUtils2.getService(osgi.bc, JitsiMeetServices.class);
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
        BridgeSelectionStrategy strategy = new RegionBasedBridgeSelectionStrategy();


        List<Bridge> allBridges = Arrays.asList(bridge1, bridge2, bridge3);
        Map<Bridge, Integer> conferenceBridges = new HashMap<>();

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

        conferenceBridges.put(bridge3, 1);
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

        conferenceBridges.put(bridge2, 1);
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

