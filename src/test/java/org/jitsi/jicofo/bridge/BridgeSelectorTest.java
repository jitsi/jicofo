/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.test.time.*;
import org.jitsi.xmpp.extensions.colibri.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.time.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bridge selection logic.
 *
 * @author Pawel Domas
 */
public class BridgeSelectorTest
{
    private Bridge jvb1;
    private Bridge jvb2;
    private Bridge jvb3;
    private BridgeSelector bridgeSelector;
    private FakeClock clock;

    @BeforeEach
    public void setUp()
        throws Exception
    {
        // Everything should work regardless of the type of jid.
        Jid jvb1Jid = JidCreate.from("jvb.example.com");
        Jid jvb2Jid = JidCreate.from("jvb@example.com");
        Jid jvb3Jid = JidCreate.from("jvb@example.com/goldengate");

        clock = new FakeClock();
        bridgeSelector = new BridgeSelector(clock);
        jvb1 = bridgeSelector.addJvbAddress(jvb1Jid);
        jvb2 = bridgeSelector.addJvbAddress(jvb2Jid);
        jvb3 = bridgeSelector.addJvbAddress(jvb3Jid);
    }

    @Test
    public void notOperationalThresholdTest()
            throws InterruptedException
    {
        Bridge[] bridges = new Bridge[] {jvb1, jvb2, jvb3};

        // Will restore failure status after 100 ms
        Bridge.setFailureResetThreshold(Duration.ofMillis(100));

        for (int testedIdx = 0; testedIdx < bridges.length; testedIdx++)
        {
            for (int idx=0; idx < bridges.length; idx++)
            {
                boolean isTestNode = idx == testedIdx;

                // Test node has 0 load...
                bridges[idx].setStats(createJvbStats(isTestNode ? 0 : .1));

                // ... and is not operational
                bridges[idx].setIsOperational(!isTestNode);
            }
            bridgeSelector.selectBridge();
            // Should not be selected now
            assertNotEquals(
                    bridges[testedIdx].getJid(),
                    bridgeSelector.selectBridge().getJid());

            for (Bridge bridge : bridges)
            {
                // try to mark as operational before the blackout period passed
                bridge.setIsOperational(true);
            }

            // Should still not be selected
            assertNotEquals(
                    bridges[testedIdx].getJid(),
                    bridgeSelector.selectBridge().getJid());

            // Wait for faulty status reset
            clock.elapse(Duration.ofMillis(150));
            // Test node should recover
            assertEquals(
                bridges[testedIdx].getJid(),
                bridgeSelector.selectBridge().getJid()
            );
        }

        Bridge.setFailureResetThreshold(BridgeConfig.config.failureResetThreshold());
    }

    private ColibriStatsExtension createJvbStats(double stress)
    {
        return createJvbStats(stress, null);
    }

    private ColibriStatsExtension createJvbStats(double stress, String region)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
            "stress_level", stress
            )
        );

        if (region != null)
        {
            statsExtension.addStat(new ColibriStatsExtension.Stat(REGION, region));
            statsExtension.addStat(new ColibriStatsExtension.Stat(RELAY_ID, region));
        }

        return statsExtension;
    }
}

