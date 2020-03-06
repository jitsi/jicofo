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
    public void preferLowestStress()
    {
        Bridge localBridge = bridge1,
            lowStressBridge = bridge3,
            mediumStressBridge = bridge2,
            highStressBridge = bridge1;

        String lowStressRegion = region3,
            mediumStressRegion = region2,
            highStressRegion = region1;

        // Here we specify 3 bridges in 3 different regions: one high-stressed,
        // one medium-stressed and one low-stressed. The numbers bellow are
        // bitrates, as our stress calculation is based on bitrate.
        //
        // The exact values don't really matter and they're only meaningful when
        // interpreted relative to each other; i.e. 75_000 is high compared to
        // 50_000, but low compared to 750_000.
        //
        // The selector takes care of splitting the available bridges into
        // groups of bridges with similar stress level and allocate participants
        // to a bridge in the least stressed group.
        highStressBridge.setStats(
            createJvbStats1(20, 5, highStressRegion));

        mediumStressBridge.setStats(
            createJvbStats1(10, 2, mediumStressRegion));

        lowStressBridge.setStats(
            createJvbStats1(1, 0, lowStressRegion));


        BridgeSelectionStrategy strategy
            = new RegionBasedBridgeSelectionStrategy();
        strategy.setLocalRegion(localBridge.getRegion());

        List<Bridge> conferenceBridges = new LinkedList<>();

        // Initial selection should select a bridge in the participant's region.

        assertEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, highStressRegion, true));
        assertEquals(
            mediumStressBridge,
            strategy.select(allBridges, conferenceBridges, mediumStressRegion, true));
        assertEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
        assertEquals(
            localBridge,
            strategy.select(allBridges, conferenceBridges, null, true));

        // Now assume that the low-stressed bridge is in the conference.
        conferenceBridges.add(lowStressBridge);
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
        conferenceBridges.add(mediumStressBridge);
        // A participant in an unknown region should be allocated on the least
        // loaded (according to the order of 'allBridges') existing conference
        // bridge.
        assertEquals(
            mediumStressBridge,
            strategy.select(allBridges, conferenceBridges, null, true));
        // A participant in a region with no bridges should also be allocated
        // on the least loaded (according to the order of 'allBridges') existing
        // conference bridge.
        assertEquals(
            mediumStressBridge,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
    }


    private ColibriStatsExtension createJvbStats1(int numberOfLocalSenders, int numberOfLocalReceivers, String region)
    {
        MaxBitrateCalculator maxBitrateCalculator = new MaxBitrateCalculator(
            4 /* numberOfConferenceBridges */,
            20 /* numberOfGlobalSenders */,
            2 /* numberOfSpeakers */
        );

        int maxDownload = maxBitrateCalculator.computeMaxDownload(numberOfLocalSenders)
            , maxUpload = maxBitrateCalculator.computeMaxUpload(numberOfLocalSenders, numberOfLocalReceivers)
            , maxOctoSendBitrate = maxBitrateCalculator.computeMaxOctoSendBitrate(numberOfLocalSenders)
            , maxOctoReceiveBitrate = maxBitrateCalculator.computeMaxOctoReceiveBitrate(numberOfLocalSenders);

        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                BITRATE_DOWNLOAD, maxDownload));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                BITRATE_UPLOAD, maxUpload));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                OCTO_RECEIVE_BITRATE, maxOctoReceiveBitrate));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                OCTO_SEND_BITRATE, maxOctoSendBitrate));


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
    public void preferRegionWhenStressIsEqual()
    {
        Bridge localBridge = bridge1,
            mediumStressBridge3 = bridge3,
            mediumStressBridge2 = bridge2,
            highStressBridge = bridge1;

        String mediumStressRegion3 = region3,
            mediumStressRegion2 = region2,
            highStressRegion = region1;

        // Here we specify 3 bridges in 3 different regions: one high-stressed
        // and two medium-stressed. The numbers bellow are bitrates, as our
        // stress calculation is based on bitrate.
        //
        // The exact values don't really matter and they're only meaningful when
        // interpreted relative to each other; i.e. 75_000 is high compared to
        // 50_000, but low compared to 750_000.
        //
        // The selector takes care of splitting the available bridges into
        // groups of bridges with similar stress level and allocate participants
        // to a bridge in the least stressed group.
        highStressBridge.setStats(
            createJvbStats1(20, 5, highStressRegion));
        mediumStressBridge3.setStats(
            createJvbStats1(10, 2, mediumStressRegion3));
        mediumStressBridge2.setStats(
            createJvbStats1(10, 2, mediumStressRegion2));

        BridgeSelectionStrategy strategy
            = new RegionBasedBridgeSelectionStrategy();
        strategy.setLocalRegion(localBridge.getRegion());

        List<Bridge> conferenceBridges = new LinkedList<>();

        // Initial selection should select a bridge in the participant's region.

        assertEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, highStressRegion, true));

        Bridge b = strategy.select(allBridges, conferenceBridges, mediumStressRegion2, true);
        assertEquals(
            mediumStressBridge2, b);

        assertEquals(
            highStressBridge,
            strategy.select(allBridges, conferenceBridges, invalidRegion, true));
        assertEquals(
            highStressBridge,
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
