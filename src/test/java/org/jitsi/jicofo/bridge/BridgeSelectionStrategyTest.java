/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import net.java.sip.communicator.util.*;
import org.jitsi.jicofo.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.junit.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.RELAY_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class BridgeSelectionStrategyTest
{
    private final OSGiHandler osgi = OSGiHandler.getInstance();

    private final String region1 = "region1",
        region2 = "region2",
        region3 = "region3",
        invalidRegion = "invalid region";

    private Bridge bridge1, bridge2, bridge3;

    private List<Bridge> allBridges;

    private float precision = 0.01f;

    @Before
    public void init()
        throws Exception
    {
        osgi.init();

        JitsiMeetServices meetServices
            = ServiceUtils.getService(osgi.bc, JitsiMeetServices.class);

        BridgeSelector selector = meetServices.getBridgeSelector();

        bridge1 = selector.addJvbAddress(JidCreate.from("bridge1"));
        bridge2 = selector.addJvbAddress(JidCreate.from("bridge2"));
        bridge3 = selector.addJvbAddress(JidCreate.from("bridge3"));
        allBridges = Arrays.asList(bridge1, bridge2, bridge3);
    }

    @After
    public void tearDown()
        throws Exception
    {
        osgi.shutdown();
    }

    /**
     * No matter where the participant is located and no matter where the bridge
     * is located, a low-stressed bridge should be preferred.
     */
    @Test
    public void preferLowestStressLevel()
    {
        Bridge localBridge = bridge1,
            lowStressBridge = bridge3,
            mediumStressBridge = bridge2,
            highStressBridge = bridge1;

        String lowStressRegion = region3,
            mediumStressRegion = region2,
            highStressRegion = region1;

        highStressBridge.setStats(
            createJvbStats(StressLevels.HIGH, 80, highStressRegion));
        assertEquals(
            highStressBridge.getStressLevel(), StressLevels.HIGH, precision);

        mediumStressBridge.setStats(
            createJvbStats(StressLevels.MEDIUM, 50, mediumStressRegion));
        assertEquals(
            mediumStressBridge.getStressLevel(), StressLevels.MEDIUM, precision);

        lowStressBridge.setStats(
            createJvbStats(StressLevels.LOW, 10, lowStressRegion));
        assertEquals(
            lowStressBridge.getStressLevel(), StressLevels.LOW, precision);


        BridgeSelectionStrategy strategy
            = new RegionBasedBridgeSelectionStrategy();
        strategy.setLocalRegion(localBridge.getRegion());

        List<Bridge> conferenceBridges = new LinkedList<>();

        // Initial selection should select a bridge in the participant's region,
        // unless there are free bridges elsewhere that are less stressed, i.e.
        // as long as there are bridges that have a stress level N-1, the
        // algorithm should not select a bridge with a stress level of N.

        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, highStressRegion, true));
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true));
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
        assertNotEquals(
            localBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that the low-stressed bridge is in the conference.
        conferenceBridges.add(lowStressBridge);
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, lowStressRegion, true));
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true));
        // A participant in an unknown region should be allocated on the
        // existing conference bridge.
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that a medium-stressed bridge is in the conference.
        conferenceBridges.add(mediumStressBridge);
        // A participant in an unknown region should be allocated on the least
        // loaded (according to the order of 'allBridges') existing conference
        // bridge.
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, null, true));
        // A participant in a region with no bridges should also be allocated
        // on the least loaded (according to the order of 'allBridges') existing
        // conference bridge.
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
    }

    private ColibriStatsExtension createJvbStats(
        float stressLevel, int videoStreams, String region)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                STRESS_LEVEL, stressLevel));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                VIDEO_STREAMS, videoStreams));

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
    public void preferRegionWhenStressLevelIsEqual()
    {
        Bridge localBridge = bridge1,
            mediumStressBridge3 = bridge3,
            mediumStressBridge2 = bridge2,
            highStressBridge = bridge1;

        String mediumStressRegion3 = region3,
            mediumStressRegion2 = region2,
            highStressRegion = region1;

        highStressBridge.setStats(
            createJvbStats(StressLevels.HIGH, 100, highStressRegion));
        assertEquals(
            highStressBridge.getStressLevel(), StressLevels.HIGH, precision);
        mediumStressBridge3.setStats(
            createJvbStats(StressLevels.MEDIUM, 50, mediumStressRegion3));
        assertEquals(
            mediumStressBridge3.getStressLevel(), StressLevels.MEDIUM, precision);
        mediumStressBridge2.setStats(
            createJvbStats(StressLevels.MEDIUM, 50, mediumStressRegion2));
        assertEquals(
            mediumStressBridge3.getStressLevel(), StressLevels.MEDIUM, precision);

        BridgeSelectionStrategy strategy
            = new RegionBasedBridgeSelectionStrategy();
        strategy.setLocalRegion(localBridge.getRegion());

        List<Bridge> conferenceBridges = new LinkedList<>();

        // Initial selection should select a bridge in the participant's region,
        // unless there are free bridges elsewhere that are less stressed, i.e.
        // as long as there are bridges that have a stress level N-1, the
        // algorithm should not select a bridge with a stress level of N.

        assertNotEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, highStressRegion, true));

        Bridge b = strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true);
        assertEquals(
            mediumStressBridge2, b);
            ;
        assertNotEquals(
            highStressRegion,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
        assertNotEquals(
            localBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        conferenceBridges.add(mediumStressBridge2);
        assertEquals(
            mediumStressBridge3,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion3, true));
        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true));
        // A participant in an unknown region should be allocated on the existing
        // conference bridge.
        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that a medium-stressed bridge is in the conference.
        conferenceBridges.add(highStressBridge);
        // A participant in an unknown region should be allocated on the least
        // loaded (according to the order of 'allBridges') existing conference
        // bridge.
        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, null, true));
        // A participant in a region with no bridges should also be allocated
        // on the least loaded (according to the order of 'allBridges') existing
        // conference bridge.
        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
    }
}
