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
import org.jitsi.xmpp.extensions.colibri.*;
import org.junit.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.junit.Assert.assertEquals;

public class BridgeSelectionStrategyTest
{
    private static Random RANDOM = new Random(23571113);

    private static Bridge createBridge(String region, double stress)
    {
        return createBridge(region, stress, "jvb-" + RANDOM.nextInt());
    }

    private static Bridge createBridge(String region, double stress, String jid)
    {
        try
        {
            return new Bridge(JidCreate.from(jid)){{
                setStats(createJvbStats(region, stress));
            }};
        }
        catch (Exception e)
        {
            System.err.println("Failed to create jid: " + e);
            return null;
        }
    }

    private static ColibriStatsExtension createJvbStats(String region, double stress)
    {
        // Divide by two because we use half of it for upload, half for download
        int packetRate = (int) (Bridge.MAX_TOTAL_PACKET_RATE_PPS * stress / 2);

        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
                new ColibriStatsExtension.Stat(
                        PACKET_RATE_DOWNLOAD, packetRate));
        statsExtension.addStat(
                new ColibriStatsExtension.Stat(
                        PACKET_RATE_UPLOAD, packetRate));

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

    private static void setOctoVersion(Bridge bridge, int octoVersion)
    {
        ColibriStatsExtension stats = new ColibriStatsExtension();
        stats.addStat(new ColibriStatsExtension.Stat("octo_version", octoVersion));
        bridge.setStats(stats);
    }

    @Test
    public void createBridgeStress()
    {
        // Make sure createBridge sets the stress level as expected.
        for (double stress = 0.1; stress < 1.0; stress += 0.1)
        {
            Bridge bridge = createBridge("region", stress);
            assertEquals(stress, bridge.getStress(), 0.01);
        }
    }

    /**
     * No matter where the participant is located and no matter where the bridge
     * is located, a low-stressed bridge should be preferred.
     */
    @Test
    public void preferLowestStress()
    {
        // Here we specify 3 bridges in 3 different regions: one high-stressed,
        // one medium-stressed and one low-stressed.
        String lowStressRegion = "lowStressRegion";
        String mediumStressRegion = "mediumStressRegion";
        String highStressRegion = "highStressRegion";

        Bridge lowStressBridge = createBridge(lowStressRegion, 0.1);
        Bridge mediumStressBridge = createBridge(mediumStressRegion, 0.3);
        Bridge highStressBridge = createBridge(highStressRegion, 0.8);

        List<Bridge> allBridges = Arrays.asList(lowStressBridge, mediumStressBridge, highStressBridge);
        Collections.sort(allBridges);

        BridgeSelectionStrategy strategy
            = new RegionBasedBridgeSelectionStrategy();

        Map<Bridge, Integer> conferenceBridges = new HashMap<>();
        // Initial selection should select a bridge in the participant's region.
        assertEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, highStressRegion, true));
        assertEquals(
            mediumStressBridge,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true));
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, "invalid region", true));
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that the low-stressed bridge is in the conference.
        conferenceBridges.put(lowStressBridge, 1);
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, lowStressRegion, true));
        assertEquals(
            mediumStressBridge,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true));
        // A participant in an unknown region should be allocated on the
        // existing conference bridge.
        assertEquals(
            lowStressBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that a medium-stressed bridge is in the conference.
        conferenceBridges.put(mediumStressBridge, 1);
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
            strategy.select(allBridges, conferenceBridges, "invalid region", true));
    }


    @Test
    public void preferRegionWhenStressIsEqual()
    {
        // Here we specify 3 bridges in 3 different regions: one high-stressed
        // and two medium-stressed.
        String mediumStressRegion1 = "mediumStressRegion1";
        String mediumStressRegion2 = "mediumStressRegion2";
        String highStressRegion = "highStressRegion";

        Bridge mediumStressBridge1 = createBridge(mediumStressRegion1, 0.25);
        Bridge mediumStressBridge2 = createBridge(mediumStressRegion2, 0.3);
        Bridge highStressBridge = createBridge(highStressRegion, 0.8);

        List<Bridge> allBridges = Arrays.asList(mediumStressBridge1, mediumStressBridge2, highStressBridge);
        Collections.sort(allBridges);

        BridgeSelectionStrategy strategy = new RegionBasedBridgeSelectionStrategy();

        Map<Bridge, Integer> conferenceBridges = new HashMap<>();

        // Initial selection should select a bridge in the participant's region.
        assertEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, highStressRegion, true));

        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true));

        assertEquals(
            mediumStressBridge1,
            strategy.select(allBridges, conferenceBridges, "invalid region", true));
        assertEquals(
            mediumStressBridge1,
            strategy.select(allBridges, conferenceBridges, null, true));

        conferenceBridges.put(mediumStressBridge2, 1);
        assertEquals(
            mediumStressBridge1,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion1, true));
        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true));
        // A participant in an unknown region should be allocated on the existing
        // conference bridge.
        assertEquals(
            mediumStressBridge2,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that a medium-stressed bridge is in the conference.
        conferenceBridges.put(highStressBridge, 1);
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
            strategy.select(allBridges, conferenceBridges, "invalid region", true));
    }

    @Test
    public void doNotMixOctoVersions()
    {
        Bridge highStressBridge = createBridge("region", 0.9);
        Bridge lowStressBridge = createBridge("region", 0.1);

        setOctoVersion(highStressBridge, 13);
        setOctoVersion(lowStressBridge, 12);

        List<Bridge> allBridges = Arrays.asList(lowStressBridge, highStressBridge);
        Collections.sort(allBridges);

        Map<Bridge, Integer> conferenceBridges = new HashMap<>();
        conferenceBridges.put(highStressBridge, 1);

        BridgeSelectionStrategy strategy = new RegionBasedBridgeSelectionStrategy();

        // lowStressBridge must not be selected, because the conference already
        // has a bridge and its octo_version does not match.
        assertEquals(
                highStressBridge,
                strategy.select(allBridges, conferenceBridges, "region", true));
    }
}
